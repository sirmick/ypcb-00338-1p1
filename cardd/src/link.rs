//! The host link: setting the card up, programming its slots, reading the host inbox and writing
//! the command ring. Records arrive in the inbox as the card writes them; nothing here trusts them
//! until their sequence numbers check out.

use crate::bar::Bar;
use crate::contract::{bar0, message as m, Layout, MAGIC, VERSION};
use crate::message::{self, Error, Header, SIZE};

#[derive(Debug, PartialEq, Eq)]
pub enum LinkError {
    /// BAR0 does not start with the contract's magic number.
    NotTheCard(u32),
    /// The card speaks another version of the contract.
    Version(u32),
    /// A record is ahead of the one expected: records were lost, or the card is misbehaving.
    Skipped { expected: u32, found: u32 },
    /// The command ring has no free entry until the card acknowledges more.
    RingFull,
    Message(Error),
}

impl From<Error> for LinkError {
    fn from(e: Error) -> Self {
        LinkError::Message(e)
    }
}

/// Checks the card and points it at the host's inbox and staging, then enables it: a new session.
pub fn set_up(bar: &mut dyn Bar, inbox: u64, inbox_log2: u32, staging: u64, staging_size: u32) -> Result<(), LinkError> {
    let magic = bar.read32(bar0::reg::MAGIC);
    if magic != MAGIC {
        return Err(LinkError::NotTheCard(magic));
    }
    let version = bar.read32(bar0::reg::VERSION);
    if version != VERSION {
        return Err(LinkError::Version(version));
    }
    // a new session: whatever an earlier host left running stops, and both sequences restart at 1
    bar.write32(bar0::reg::ENABLE, 0);
    bar.write32(bar0::reg::INBOX_ADDR_LO, inbox as u32);
    bar.write32(bar0::reg::INBOX_ADDR_HI, (inbox >> 32) as u32);
    bar.write32(bar0::reg::INBOX_ENTRIES_LOG2, inbox_log2);
    bar.write32(bar0::reg::STAGING_ADDR_LO, staging as u32);
    bar.write32(bar0::reg::STAGING_ADDR_HI, (staging >> 32) as u32);
    bar.write32(bar0::reg::STAGING_SIZE, staging_size);
    bar.write32(bar0::reg::ENABLE, 1);
    Ok(())
}

/// What a slot offers the guest. Written once, before the guest looks.
pub struct Device<'a> {
    pub device_id: u32,
    pub features: u64,
    pub queue_num_max: u32,
    pub config: &'a [u8],
}

pub fn program_slot(bar: &mut dyn Bar, slot: usize, d: &Device) {
    let base = bar0::SLOT_PROG_BASE + slot * bar0::SLOT_PROG_STRIDE;
    bar.write32(base + bar0::slot_reg::DEVICE_ID, d.device_id);
    bar.write32(base + bar0::slot_reg::FEATURES_LO, d.features as u32);
    bar.write32(base + bar0::slot_reg::FEATURES_HI, (d.features >> 32) as u32);
    bar.write32(base + bar0::slot_reg::QUEUE_NUM_MAX, d.queue_num_max);
    for (i, w) in d.config.chunks(4).enumerate() {
        let mut word = [0u8; 4];
        word[..w.len()].copy_from_slice(w);
        bar.write32(base + bar0::slot_reg::CONFIG + 4 * i, u32::from_le_bytes(word));
    }
}

/// Host memory the card writes into (the inbox) or the host and card share (staging): pinned and
/// mapped for the card's DMA in cardd; a fake card's memory in tests.
pub trait HostMem {
    fn read(&mut self, offset: usize, buf: &mut [u8]);
    fn write(&mut self, offset: usize, data: &[u8]);
}

/// The host inbox: a ring of 64-byte records in host memory, written only by the card.
pub struct Inbox {
    log2: u32,
    next: u32,
}

impl Inbox {
    pub fn new(log2: u32) -> Self {
        Inbox { log2, next: 1 }
    }
    pub fn entries(&self) -> usize {
        1 << self.log2
    }
    /// The entry the next record will occupy.
    pub fn slot_of(&self, seq: u32) -> usize {
        (seq.wrapping_sub(1) as usize) & (self.entries() - 1)
    }
    /// The next record, if the card has finished writing it. An empty, torn or older entry means
    /// "not yet"; a record further ahead is an error. A record taken is acknowledged to the card.
    pub fn poll(&mut self, mem: &mut dyn HostMem, bar: &mut dyn Bar) -> Result<Option<(Header, [u8; SIZE])>, LinkError> {
        let mut b = [0u8; SIZE];
        mem.read(self.slot_of(self.next) * SIZE, &mut b);
        let h = match message::decode(&b) {
            Ok(h) => h,
            Err(Error::Empty) | Err(Error::Torn { .. }) => return Ok(None),
            Err(e) => return Err(e.into()),
        };
        if h.seq != self.next {
            // the same entry from an earlier lap is stale; anything else skipped ahead
            if h.seq.wrapping_add(self.entries() as u32) == self.next {
                return Ok(None);
            }
            return Err(LinkError::Skipped { expected: self.next, found: h.seq });
        }
        self.next = self.next.wrapping_add(1);
        bar.write32(bar0::reg::INBOX_CONSUMED, h.seq);
        Ok(Some((h, b)))
    }
}

/// The command ring in BAR0, written only by the host.
pub struct Commands {
    next: u32,
    acked: u32,
}

impl Default for Commands {
    fn default() -> Self {
        Commands { next: 1, acked: 0 }
    }
}

impl Commands {
    pub const ENTRIES: u32 = 1 << bar0::CMD_ENTRIES_LOG2;

    /// Writes a command into its ring entry, then rings CMD_PRODUCED. Returns its sequence number.
    pub fn send(&mut self, bar: &mut dyn Bar, layout: &Layout, slot: u16, values: &[(&str, &[u8])]) -> Result<u32, LinkError> {
        if self.next.wrapping_sub(1).wrapping_sub(self.acked) >= Self::ENTRIES {
            return Err(LinkError::RingFull);
        }
        let seq = self.next;
        let b = message::encode(layout, seq, slot, values)?;
        let base = bar0::CMD_RING + ((seq - 1) % Self::ENTRIES) as usize * m::SIZE;
        for (i, w) in b.chunks(4).enumerate() {
            bar.write32(base + 4 * i, u32::from_le_bytes([w[0], w[1], w[2], w[3]]));
        }
        bar.write32(bar0::reg::CMD_PRODUCED, seq);
        self.next = self.next.wrapping_add(1);
        Ok(seq)
    }
    /// The card's CMD_ACK record: commands up to `cmd_seq` are consumed and their entries free.
    pub fn acked(&mut self, cmd_seq: u32) {
        self.acked = cmd_seq;
    }
}
