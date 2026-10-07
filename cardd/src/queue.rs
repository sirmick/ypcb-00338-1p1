//! The device side of a split virtqueue (virtio 1.2, 2.7), over [`GuestMem`]. What the guest wrote
//! is hostile: indices are bounded by the queue size, chains by the queue size (which catches a
//! loop) and by a total length, every segment's direction is checked, and INDIRECT (never
//! negotiated) is refused.

use crate::mem::{GuestMem, MemError};

pub const DESC_F_NEXT: u16 = 1;
pub const DESC_F_WRITE: u16 = 2;
pub const DESC_F_INDIRECT: u16 = 4;
/// The most bytes one chain may describe.
pub const MAX_CHAIN_BYTES: u64 = 1 << 22;

#[derive(Debug, PartialEq, Eq)]
pub enum QueueError {
    Mem(MemError),
    /// The available ring's head index points outside the queue.
    BadHead(u16),
    /// A descriptor's `next` points outside the queue.
    BadNext(u16),
    /// The chain is longer than the queue: a loop.
    Loop,
    Indirect,
    TooLong,
    /// A device-readable segment after a device-writable one.
    Order,
    /// The guest's available index moved further than the queue holds.
    Overrun { avail: u16, seen: u16 },
}

impl From<MemError> for QueueError {
    fn from(e: MemError) -> Self {
        QueueError::Mem(e)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Segment {
    pub addr: u64,
    pub len: u32,
    /// the device writes it (the guest reads it)
    pub write: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Chain {
    pub head: u16,
    pub segments: Vec<Segment>,
}

impl Chain {
    pub fn readable(&self) -> impl Iterator<Item = &Segment> {
        self.segments.iter().filter(|s| !s.write)
    }
    pub fn writable(&self) -> impl Iterator<Item = &Segment> {
        self.segments.iter().filter(|s| s.write)
    }
}

pub struct SplitQueue {
    pub size: u16,
    pub desc: u64,
    pub avail: u64,
    pub used: u64,
    last_avail: u16,
}

impl SplitQueue {
    pub fn new(size: u16, desc: u64, avail: u64, used: u64) -> Self {
        SplitQueue { size, desc, avail, used, last_avail: 0 }
    }

    /// The next available chain, if the guest has offered one.
    pub fn pop(&mut self, mem: &mut dyn GuestMem) -> Result<Option<Chain>, QueueError> {
        let idx = mem.read_u16(self.avail + 2)?;
        let pending = idx.wrapping_sub(self.last_avail);
        if pending == 0 {
            return Ok(None);
        }
        if pending > self.size {
            return Err(QueueError::Overrun { avail: idx, seen: self.last_avail });
        }
        let slot = (self.last_avail % self.size) as u64;
        let head = mem.read_u16(self.avail + 4 + 2 * slot)?;
        if head >= self.size {
            return Err(QueueError::BadHead(head));
        }
        let mut segments = Vec::new();
        let (mut i, mut total, mut seen_write) = (head, 0u64, false);
        loop {
            if segments.len() >= self.size as usize {
                return Err(QueueError::Loop);
            }
            let mut d = [0u8; 16];
            mem.read(self.desc + 16 * i as u64, &mut d)?;
            let addr = u64::from_le_bytes(d[0..8].try_into().unwrap());
            let len = u32::from_le_bytes(d[8..12].try_into().unwrap());
            let flags = u16::from_le_bytes(d[12..14].try_into().unwrap());
            let next = u16::from_le_bytes(d[14..16].try_into().unwrap());
            if flags & DESC_F_INDIRECT != 0 {
                return Err(QueueError::Indirect);
            }
            let write = flags & DESC_F_WRITE != 0;
            if seen_write && !write {
                return Err(QueueError::Order);
            }
            seen_write |= write;
            total += len as u64;
            if total > MAX_CHAIN_BYTES {
                return Err(QueueError::TooLong);
            }
            segments.push(Segment { addr, len, write });
            if flags & DESC_F_NEXT == 0 {
                break;
            }
            if next >= self.size {
                return Err(QueueError::BadNext(next));
            }
            i = next;
        }
        self.last_avail = self.last_avail.wrapping_add(1);
        Ok(Some(Chain { head, segments }))
    }
}
