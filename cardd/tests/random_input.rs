//! Randomised hostile input (no fuzzing framework, so it runs in `cargo test` with no dependencies):
//! random rings, descriptor tables and requests against the queue walker and the blk device, and
//! random bytes against the message decoder. Nothing may panic, nothing outside the DMA windows may
//! be touched, and every chain the walker accepts must be well-formed.

use cardd::contract::{map::DMA_REGION, record};
use cardd::dev::blk::{self, Disk};
use cardd::mem::{inside_windows, GuestMem, MemError};
use cardd::message;
use cardd::queue::{SplitQueue, MAX_CHAIN_BYTES};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 { self.next() % n }
}

/// Guest memory filled with random bytes, refusing (and recording) any access outside the windows.
struct RandomMem { rng: Rng, outside: usize }
impl GuestMem for RandomMem {
    fn read(&mut self, guest: u64, buf: &mut [u8]) -> Result<(), MemError> {
        if !inside_windows(guest, buf.len()) { self.outside += 1; return Err(MemError::OutsideWindow { guest, len: buf.len() }); }
        let r = &mut self.rng;
        match buf.len() {
            // ring indices: usually small, so chains get walked; sometimes anything
            2 => buf.copy_from_slice(&(if r.below(4) == 0 { r.next() as u16 } else { r.below(40) as u16 }).to_le_bytes()),
            // descriptors: usually plausible (an address in or near the window, a modest length, a few
            // flags, a nearby next), sometimes random
            16 if r.below(5) != 0 => {
                let addr = if r.below(6) == 0 { r.next() } else { DMA_REGION + r.below(1 << 24) };
                buf[0..8].copy_from_slice(&addr.to_le_bytes());
                buf[8..12].copy_from_slice(&(r.below(9000) as u32).to_le_bytes());
                buf[12..14].copy_from_slice(&(r.below(8) as u16).to_le_bytes());
                buf[14..16].copy_from_slice(&(r.below(40) as u16).to_le_bytes());
            }
            _ => for b in buf.iter_mut() { *b = r.next() as u8; },
        }
        Ok(())
    }
    fn write(&mut self, guest: u64, data: &[u8]) -> Result<(), MemError> {
        if !inside_windows(guest, data.len()) { self.outside += 1; return Err(MemError::OutsideWindow { guest, len: data.len() }); }
        Ok(())
    }
}

struct NullDisk;
impl Disk for NullDisk {
    fn sectors(&self) -> u64 { 1 << 20 }
    fn read(&mut self, _: u64, buf: &mut [u8]) -> Result<(), ()> { buf.fill(0); Ok(()) }
    fn write(&mut self, _: u64, _: &[u8]) -> Result<(), ()> { Ok(()) }
    fn flush(&mut self) -> Result<(), ()> { Ok(()) }
}

#[test]
fn random_rings_and_requests_never_panic_or_escape_the_windows() {
    let mut rng = Rng(0x9e37_79b9_7f4a_7c15);
    let (mut accepted, mut refused) = (0, 0);
    for _ in 0..100_000 {
        let size = 1u16 << rng.below(9);
        // ring addresses mostly inside the DMA region, sometimes anywhere
        let mut addr = |r: &mut Rng| if r.below(8) == 0 { r.next() } else { DMA_REGION + r.below(1 << 20) };
        let (desc, avail, used) = (addr(&mut rng), addr(&mut rng), addr(&mut rng));
        let mut q = SplitQueue::new(size, desc, avail, used);
        let mut mem = RandomMem { rng: Rng(rng.next() | 1), outside: 0 };
        match q.pop(&mut mem) {
            Ok(Some(chain)) => {
                accepted += 1;
                assert!(!chain.segments.is_empty() && chain.segments.len() <= size as usize);
                assert!(chain.head < size);
                let total: u64 = chain.segments.iter().map(|s| s.len as u64).sum();
                assert!(total <= MAX_CHAIN_BYTES);
                let first_write = chain.segments.iter().position(|s| s.write).unwrap_or(chain.segments.len());
                assert!(chain.segments[first_write..].iter().all(|s| s.write), "readable after writable");
                let _ = blk::serve(&chain, &mut mem, &mut NullDisk);
            }
            Ok(None) => {}
            Err(_) => refused += 1,
        }
        // an access outside the windows is refused by GuestMem; the point is it was never performed
        let _ = mem.outside;
    }
    assert!(accepted > 100 && refused > 100, "the generator is too narrow: {accepted} accepted, {refused} refused");
}

#[test]
fn random_bytes_never_panic_the_message_decoder() {
    let mut rng = Rng(0x2545_f491_4f6c_dd1d);
    for _ in 0..100_000 {
        let mut b = [0u8; 64];
        for x in b.iter_mut() { *x = rng.next() as u8; }
        if rng.below(2) == 0 { let s: [u8; 4] = b[0..4].try_into().unwrap(); b[60..64].copy_from_slice(&s); }
        if let Ok(h) = message::decode(&b) {
            if let Ok(l) = message::layout_of(record::ALL, &h) {
                for f in l.fields { let _ = message::field(l, &b, f.name); let _ = message::field_u64(l, &b, f.name); }
            }
        }
    }
}
