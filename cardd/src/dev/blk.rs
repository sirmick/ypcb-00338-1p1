//! virtio-blk (virtio 1.2, 5.2): requests read from the guest's chain, served from a disk.

use crate::mem::{GuestMem, MemError};
use crate::queue::Chain;

pub const T_IN: u32 = 0;
pub const T_OUT: u32 = 1;
pub const T_FLUSH: u32 = 4;
pub const T_GET_ID: u32 = 8;
pub const S_OK: u8 = 0;
pub const S_IOERR: u8 = 1;
pub const S_UNSUPP: u8 = 2;
pub const SECTOR: u64 = 512;
/// GET_ID's answer.
pub const ID: &[u8] = b"cardd-ypcb";

/// A disk: an image file or a partition in cardd, a vector in tests.
pub trait Disk {
    fn sectors(&self) -> u64;
    fn read(&mut self, sector: u64, buf: &mut [u8]) -> Result<(), ()>;
    fn write(&mut self, sector: u64, data: &[u8]) -> Result<(), ()>;
    fn flush(&mut self) -> Result<(), ()>;
}

/// Serves one request; returns the bytes written into the guest's buffers (the used length).
/// A malformed request gets IOERR in its status byte if it has one; a chain with no writable
/// status byte at all is an error for the caller.
pub fn serve(chain: &Chain, mem: &mut dyn GuestMem, disk: &mut dyn Disk) -> Result<u32, MemError> {
    let status = match chain.segments.last() {
        Some(s) if s.write && s.len >= 1 => *s,
        _ => return Err(MemError::Copy(u16::MAX)),
    };
    let header = match chain.segments.first() {
        Some(s) if !s.write && s.len >= 16 => *s,
        _ => {
            mem.write(status.addr + status.len as u64 - 1, &[S_IOERR])?;
            return Ok(1);
        }
    };
    let mut h = [0u8; 16];
    mem.read(header.addr, &mut h)?;
    let kind = u32::from_le_bytes(h[0..4].try_into().unwrap());
    let sector = u64::from_le_bytes(h[8..16].try_into().unwrap());
    let data: Vec<_> = chain.segments[1..chain.segments.len() - 1].to_vec();
    let bytes: u64 = data.iter().map(|s| s.len as u64).sum();
    let in_disk = |n: u64| bytes % SECTOR == 0 && sector.checked_add(n / SECTOR).map_or(false, |e| e <= disk.sectors());
    let mut written = 0u32;
    let st = match kind {
        T_IN if in_disk(bytes) && data.iter().all(|s| s.write) => {
            let mut off = 0u64;
            let mut ok = S_OK;
            for s in &data {
                let mut buf = vec![0u8; s.len as usize];
                if disk.read(sector + off / SECTOR, &mut buf).is_err() { ok = S_IOERR; break; }
                mem.write(s.addr, &buf)?;
                off += s.len as u64;
                written += s.len;
            }
            ok
        }
        T_OUT if in_disk(bytes) && data.iter().all(|s| !s.write) => {
            let mut off = 0u64;
            let mut ok = S_OK;
            for s in &data {
                let mut buf = vec![0u8; s.len as usize];
                mem.read(s.addr, &mut buf)?;
                if disk.write(sector + off / SECTOR, &buf).is_err() { ok = S_IOERR; break; }
                off += s.len as u64;
            }
            ok
        }
        T_FLUSH => if disk.flush().is_ok() { S_OK } else { S_IOERR },
        T_GET_ID if data.len() == 1 && data[0].write => {
            // the serial number: up to 20 bytes, NUL-padded
            let mut id = [0u8; 20];
            id[..ID.len()].copy_from_slice(ID);
            let n = (data[0].len as usize).min(20);
            mem.write(data[0].addr, &id[..n])?;
            written += n as u32;
            S_OK
        }
        T_IN | T_OUT => S_IOERR,
        _ => S_UNSUPP,
    };
    mem.write(status.addr + status.len as u64 - 1, &[st])?;
    Ok(written + 1)
}
