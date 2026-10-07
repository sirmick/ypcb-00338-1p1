//! Guest memory as the backend sees it: ranges it asks the card to copy, never memory it maps.
//! Every range is checked against the DMA windows, with overflow-checked arithmetic, before
//! anything moves (the card checks again).

use crate::contract::DMA_WINDOWS;

#[derive(Debug, PartialEq, Eq)]
pub enum MemError {
    /// The range is not wholly inside one DMA window (or wraps past 2^64).
    OutsideWindow { guest: u64, len: usize },
    /// The card refused or failed the copy (COPY_DONE status).
    Copy(u16),
    /// The card did not answer in time.
    Timeout,
    /// The host link failed.
    Link(String),
}

/// True if `[guest, guest + len)` lies wholly inside one DMA window.
pub fn inside_windows(guest: u64, len: usize) -> bool {
    let Some(end) = guest.checked_add(len as u64) else { return false };
    DMA_WINDOWS.iter().any(|&(base, size)| guest >= base && end <= base.saturating_add(size))
}

/// Read and write guest ranges. Phase 1 implements it with copy commands; tests use a fake.
pub trait GuestMem {
    fn read(&mut self, guest: u64, buf: &mut [u8]) -> Result<(), MemError>;
    fn write(&mut self, guest: u64, data: &[u8]) -> Result<(), MemError>;

    fn read_u16(&mut self, guest: u64) -> Result<u16, MemError> {
        let mut b = [0u8; 2];
        self.read(guest, &mut b)?;
        Ok(u16::from_le_bytes(b))
    }
}
