//! The backend: the host link driven as one synchronous loop. Phase 1 reaches guest memory through
//! copy commands ([`CopyMem`]), and serves each slot's device when the guest notifies it.

use std::collections::VecDeque;

use crate::bar::Bar;
use crate::contract::{command, copy_status, record, virtio_mmio};
use crate::dev::blk::{self, Disk};
use crate::link::{self, Commands, HostMem, Inbox, LinkError};
use crate::mem::{self, GuestMem, MemError};
use crate::message::{self, Header, SIZE};
use crate::queue::{QueueError, SplitQueue};
use crate::slot::{Event, Slot};

/// How many times a wait polls the inbox before giving up.
pub const MAX_POLLS: usize = 1_000_000;
const LINE: u64 = 64;

pub struct Link {
    pub bar: Box<dyn Bar>,
    pub inbox_mem: Box<dyn HostMem>,
    pub staging: Box<dyn HostMem>,
    pub staging_size: usize,
    inbox: Inbox,
    commands: Commands,
    next_tag: u32,
    /// records that arrived while a copy was in flight
    pending: VecDeque<(Header, [u8; SIZE])>,
}

impl Link {
    pub fn new(bar: Box<dyn Bar>, inbox_mem: Box<dyn HostMem>, inbox_log2: u32, staging: Box<dyn HostMem>, staging_size: usize) -> Self {
        Link { bar, inbox_mem, staging, staging_size, inbox: Inbox::new(inbox_log2), commands: Commands::default(), next_tag: 1, pending: VecDeque::new() }
    }

    /// The next record, CMD_ACKs absorbed. `None` if the inbox holds nothing new.
    fn poll(&mut self) -> Result<Option<(Header, [u8; SIZE])>, LinkError> {
        loop {
            let Some((h, b)) = self.inbox.poll(self.inbox_mem.as_mut(), self.bar.as_mut())? else { return Ok(None) };
            if h.kind == record::CMD_ACK.kind {
                self.commands.acked(message::field_u64(&record::CMD_ACK, &b, "cmd_seq")? as u32);
                continue;
            }
            return Ok(Some((h, b)));
        }
    }

    /// The next record for the devices: queued ones first.
    pub fn next_record(&mut self) -> Result<Option<(Header, [u8; SIZE])>, LinkError> {
        if let Some(r) = self.pending.pop_front() {
            return Ok(Some(r));
        }
        self.poll()
    }

    /// Sends a command, waiting for ring space if the card is behind.
    pub fn send(&mut self, layout: &crate::contract::Layout, slot: u16, values: &[(&str, &[u8])]) -> Result<u32, LinkError> {
        for _ in 0..MAX_POLLS {
            match self.commands.send(self.bar.as_mut(), layout, slot, values) {
                Err(LinkError::RingFull) => {
                    if let Some(r) = self.poll()? {
                        self.pending.push_back(r);
                    }
                }
                other => return other,
            }
        }
        Err(LinkError::RingFull)
    }

    /// Waits for the COPY_DONE carrying `tag`; other records wait in the queue.
    fn wait_copy(&mut self, tag: u32) -> Result<u16, MemError> {
        for _ in 0..MAX_POLLS {
            match self.poll() {
                Ok(Some((h, b))) if h.kind == record::COPY_DONE.kind => {
                    let t = message::field_u64(&record::COPY_DONE, &b, "tag").map_err(|e| MemError::Link(format!("{e:?}")))?;
                    if t as u32 == tag {
                        return Ok(message::field_u64(&record::COPY_DONE, &b, "status").unwrap_or(u64::MAX) as u16);
                    }
                    return Err(MemError::Link(format!("COPY_DONE for tag {t}, waiting for {tag}")));
                }
                Ok(Some(r)) => self.pending.push_back(r),
                Ok(None) => {}
                Err(e) => return Err(MemError::Link(format!("{e:?}"))),
            }
        }
        Err(MemError::Timeout)
    }

    fn copy(&mut self, to_host: bool, guest: u64, len: usize, staging: usize) -> Result<(), MemError> {
        let tag = self.next_tag;
        self.next_tag = self.next_tag.wrapping_add(1).max(1);
        let layout = if to_host { &command::COPY_TO_HOST } else { &command::COPY_FROM_HOST };
        let vals: [(&str, &[u8]); 4] = [
            ("tag", &tag.to_le_bytes()),
            ("len", &(len as u32).to_le_bytes()),
            ("guest", &guest.to_le_bytes()),
            ("staging", &(staging as u32).to_le_bytes()),
        ];
        self.send(layout, 0, &vals).map_err(|e| MemError::Link(format!("{e:?}")))?;
        match self.wait_copy(tag)? {
            copy_status::DONE => Ok(()),
            s => Err(MemError::Copy(s)),
        }
    }
}

/// Phase 1's guest memory: each access is a copy through host staging. The staging offset keeps the
/// guest address's place in its 64-byte line, as the contract requires.
pub struct CopyMem<'a>(pub &'a mut Link);

impl<'a> CopyMem<'a> {
    fn chunks(&self, guest: u64, len: usize) -> Vec<(u64, usize, usize)> {
        let room = self.0.staging_size - LINE as usize; // the offset within the line needs up to 63 bytes
        let mut out = Vec::new();
        let mut done = 0usize;
        while done < len {
            let g = guest + done as u64;
            let n = (len - done).min(room);
            out.push((g, n, (g % LINE) as usize));
            done += n;
        }
        out
    }
}

impl<'a> GuestMem for CopyMem<'a> {
    fn read(&mut self, guest: u64, buf: &mut [u8]) -> Result<(), MemError> {
        if !mem::inside_windows(guest, buf.len()) {
            return Err(MemError::OutsideWindow { guest, len: buf.len() });
        }
        let mut done = 0;
        for (g, n, st) in self.chunks(guest, buf.len()) {
            self.0.copy(true, g, n, st)?;
            self.0.staging.read(st, &mut buf[done..done + n]);
            done += n;
        }
        Ok(())
    }
    fn write(&mut self, guest: u64, data: &[u8]) -> Result<(), MemError> {
        if !mem::inside_windows(guest, data.len()) {
            return Err(MemError::OutsideWindow { guest, len: data.len() });
        }
        let mut done = 0;
        for (g, n, st) in self.chunks(guest, data.len()) {
            self.0.staging.write(st, &data[done..done + n]);
            self.0.copy(false, g, n, st)?;
            done += n;
        }
        Ok(())
    }
}

#[derive(Debug)]
pub enum BackendError {
    Link(LinkError),
    Mem(MemError),
    Queue(QueueError),
}
impl From<LinkError> for BackendError { fn from(e: LinkError) -> Self { BackendError::Link(e) } }
impl From<MemError> for BackendError { fn from(e: MemError) -> Self { BackendError::Mem(e) } }
impl From<QueueError> for BackendError { fn from(e: QueueError) -> Self { BackendError::Queue(e) } }

/// One virtio-blk slot.
pub struct BlkSlot {
    pub slot: u16,
    pub state: Slot,
    pub queue: Option<SplitQueue>,
    pub disk: Box<dyn Disk>,
}

impl BlkSlot {
    pub fn device(&self, queue_num_max: u32) -> link::Device<'static> {
        link::Device {
            device_id: virtio_mmio::DEVICE_BLK,
            features: (1 << virtio_mmio::F_VERSION_1) | (1 << virtio_mmio::F_ACCESS_PLATFORM),
            queue_num_max,
            config: &[],
        }
    }
}

/// Serves records until the inbox is empty. Returns how many requests it completed.
pub fn serve(link: &mut Link, blk: &mut BlkSlot) -> Result<usize, BackendError> {
    let mut served = 0;
    while let Some((h, b)) = link.next_record()? {
        if h.slot != blk.slot {
            continue;
        }
        match blk.state.apply(h.kind, &b).map_err(LinkError::Message)? {
            Event::QueueReady(0) => {
                let q = blk.state.queues[0];
                blk.queue = q.ready.then(|| SplitQueue::new(q.size, q.desc, q.driver, q.device));
            }
            Event::Reset => blk.queue = None,
            Event::Notify(0) => {
                let mut completed = 0;
                while let Some(q) = blk.queue.as_mut() {
                    let chain = match q.pop(&mut CopyMem(link))? {
                        Some(c) => c,
                        None => break,
                    };
                    let used = blk::serve(&chain, &mut CopyMem(link), blk.disk.as_mut())?;
                    link.send(&command::USED_PUSH, blk.slot, &[
                        ("queue", &0u16.to_le_bytes()),
                        ("id", &(chain.head as u32).to_le_bytes()),
                        ("len", &used.to_le_bytes()),
                    ])?;
                    completed += 1;
                }
                if completed > 0 {
                    link.send(&command::INTERRUPT, blk.slot, &[("bits", &1u32.to_le_bytes())])?;
                }
                served += completed;
            }
            _ => {}
        }
    }
    Ok(served)
}
