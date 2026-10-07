//! S4 on the real card (designs/soc-s4): cardd's link over VFIO against the card's RTL on PCIe, with
//! 64 KiB of block RAM where guest memory will be. Checks, in order:
//!   1. BAR0 reads back the contract's magic and version, and the link sets up;
//!   2. data survives a round trip both ways through guest memory (COPY_FROM_HOST then COPY_TO_HOST),
//!      over the whole 64 KiB, at odd offsets and lengths, against a model of what guest memory holds;
//!   3. the ring region reaches the same block RAM (both DMA windows alias onto it);
//! then measures a BAR0 read, a small copy's round trip, and copy bandwidth each way.
//!
//! Usage (root, the card bound to vfio-pci): s4 <bdf> [iterations]
//! S4_DIAG=1 instead reads fixed and random fills back repeatedly and counts bits flipped each way.

use std::time::{Duration, Instant};

use cardd::backend::{CopyMem, Link};
use cardd::bar::Bar;
use cardd::contract::{bar0, DMA_WINDOWS, MAGIC};
use cardd::link;
use cardd::mem::GuestMem;
use cardd::vfio::Device;

const INBOX_LOG2: u32 = 6;
const INBOX_IOVA: u64 = 0x1000_0000; // below 4 GiB: the card's records go out as 3-DW MemWr
const STAGING_IOVA: u64 = 0x1_0000_0000; // above: copies use 4-DW MemWr and MemRd
const STAGING_SIZE: usize = 256 * 1024;
const GUEST_BYTES: usize = 64 * 1024;

/// xorshift: repeatable test data without a dependency
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

fn percentiles(mut v: Vec<Duration>) -> String {
    v.sort();
    let at = |p: f64| v[((v.len() - 1) as f64 * p) as usize].as_secs_f64() * 1e6;
    format!("min {:.2} µs, median {:.2} µs, p99 {:.2} µs, max {:.2} µs", at(0.0), at(0.5), at(0.99), at(1.0))
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let bdf = args.get(1).expect("usage: s4 <bdf> [iterations]");
    let iters: usize = args.get(2).map(|s| s.parse().unwrap()).unwrap_or(1000);
    let dev = Device::open(bdf).unwrap_or_else(|e| panic!("{bdf}: {e}"));
    dev.enable_bus_master().expect("enabling bus master");
    let mut bar = dev.bar0().expect("mapping BAR0");
    let magic = bar.read32(bar0::reg::MAGIC);
    let version = bar.read32(bar0::reg::VERSION);
    println!("BAR0: magic {magic:#010x} (want {MAGIC:#010x}), version {version}, status {:#x}", bar.read32(bar0::reg::STATUS));
    let inbox = dev.dma_buffer(4096, INBOX_IOVA).expect("inbox");
    let staging = dev.dma_buffer(STAGING_SIZE, STAGING_IOVA).expect("staging");
    link::set_up(&mut bar, INBOX_IOVA, INBOX_LOG2, STAGING_IOVA, STAGING_SIZE as u32).expect("set_up");
    let mut link = Link::new(Box::new(bar), Box::new(inbox), INBOX_LOG2, Box::new(staging), STAGING_SIZE);
    let d = DMA_WINDOWS[0].0; // the DMA region; the block RAM answers its first 64 KiB
    if std::env::var("S4_DIAG").is_ok() {
        diag(&mut link, d, iters);
        return;
    }
    let mut rng = Rng(0x5eed_cafe_f00d_0001);
    let mut model = vec![0u8; GUEST_BYTES];
    let mut failures = 0;

    // 2. the whole block RAM, then odd pieces of it
    let all = rng.bytes(GUEST_BYTES);
    CopyMem(&mut link).write(d, &all).expect("filling guest memory");
    model.copy_from_slice(&all);
    let mut back = vec![0u8; GUEST_BYTES];
    CopyMem(&mut link).read(d, &mut back).expect("reading guest memory");
    if back != model {
        let first = back.iter().zip(&model).position(|(a, b)| a != b).unwrap();
        println!("FAIL: 64 KiB round trip differs first at offset {first:#x}");
        let at = first & !63;
        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<Vec<_>>().join("");
        for l in 0..4 {
            let o = at + 64 * l;
            println!("  {o:#06x} want {}\n         got  {}", hex(&model[o..o + 64]), hex(&back[o..o + 64]));
        }
        let diffs: Vec<String> = (0..GUEST_BYTES).filter(|&k| back[k] != model[k])
            .map(|k| format!("{k:#06x}:{:02x}->{:02x}", model[k], back[k])).collect();
        println!("  {} of {GUEST_BYTES} bytes differ: {}", diffs.len(), diffs.iter().take(16).cloned().collect::<Vec<_>>().join(" "));
        failures += 1;
    } else {
        println!("ok: 64 KiB written and read back");
    }
    for i in 0..iters {
        let off = rng.below(GUEST_BYTES);
        let len = 1 + rng.below((GUEST_BYTES - off).min(3000));
        if rng.below(2) == 0 {
            let data = rng.bytes(len);
            CopyMem(&mut link).write(d + off as u64, &data).expect("copy to guest");
            model[off..off + len].copy_from_slice(&data);
        } else {
            let mut got = vec![0u8; len];
            CopyMem(&mut link).read(d + off as u64, &mut got).expect("copy from guest");
            if got != model[off..off + len] {
                let diffs: Vec<String> = (0..len).filter(|&k| got[k] != model[off + k])
                    .map(|k| format!("{:#06x}:{:02x}->{:02x}", off + k, model[off + k], got[k])).take(8).collect();
                println!("FAIL: read {len} bytes at {off:#x} (iteration {i}) differs: {}", diffs.join(" "));
                failures += 1;
                if failures > 5 {
                    break;
                }
            }
        }
    }
    let mut back = vec![0u8; GUEST_BYTES];
    CopyMem(&mut link).read(d, &mut back).expect("reading guest memory");
    if back != model {
        println!("FAIL: guest memory differs from the model after {iters} random copies");
        failures += 1;
    } else {
        println!("ok: {iters} random copies (1-3000 bytes, any alignment) agree with the model");
    }

    // 3. the ring region is the same memory
    let ring = DMA_WINDOWS[1].0;
    let mut got = vec![0u8; 256];
    CopyMem(&mut link).read(ring + 0x1000, &mut got).expect("copy from the ring region");
    if got != model[0x1000..0x1100] {
        println!("FAIL: the ring region does not alias the block RAM");
        failures += 1;
    } else {
        println!("ok: the ring region reaches the same block RAM");
    }

    // latency: a non-posted BAR0 read; a 4-byte copy (command out, line in, COPY_DONE back)
    let mut t = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t0 = Instant::now();
        let m = link.bar.read32(bar0::reg::MAGIC);
        t.push(t0.elapsed());
        assert_eq!(m, MAGIC);
    }
    println!("BAR0 read: {}", percentiles(t));
    for (name, to_host) in [("copy to host, 4 bytes", true), ("copy from host, 4 bytes", false)] {
        let mut t = Vec::with_capacity(iters);
        let mut b = [0u8; 4];
        for i in 0..iters {
            let g = d + 64 * (i % 1024) as u64;
            let t0 = Instant::now();
            if to_host { CopyMem(&mut link).read(g, &mut b) } else { CopyMem(&mut link).write(g, &b) }.expect("small copy");
            t.push(t0.elapsed());
        }
        println!("{name}: {}", percentiles(t));
    }

    // bandwidth: 64 KiB copies, which the staging buffer takes in one command
    let reps = (iters / 10).max(10);
    let mut buf = vec![0u8; GUEST_BYTES];
    let t0 = Instant::now();
    for _ in 0..reps {
        CopyMem(&mut link).read(d, &mut buf).expect("bandwidth read");
    }
    let to_host = (reps * GUEST_BYTES) as f64 / t0.elapsed().as_secs_f64() / 1e6;
    let t0 = Instant::now();
    for _ in 0..reps {
        CopyMem(&mut link).write(d, &buf).expect("bandwidth write");
    }
    let from_host = (reps * GUEST_BYTES) as f64 / t0.elapsed().as_secs_f64() / 1e6;
    println!("bandwidth: copy to host {to_host:.1} MB/s, copy from host {from_host:.1} MB/s (64 KiB copies)");

    if failures > 0 {
        println!("{failures} FAILURES");
        std::process::exit(1);
    }
    println!("PASS");
}

/// Which way do bits go wrong? Fills guest memory with one pattern, then reads it back repeatedly,
/// counting flipped bits by direction and whether the same bytes fail on every read.
fn diag(link: &mut Link, d: u64, reads: usize) {
    let mut rng = Rng(0x5eed_cafe_f00d_0001);
    let random = rng.bytes(GUEST_BYTES);
    for (name, fill) in [("zeros", Some(0x00u8)), ("ones", Some(0xff)), ("random", None), ("random again", None)] {
        let want = match fill { Some(f) => vec![f; GUEST_BYTES], None => random.clone() };
        CopyMem(link).write(d, &want).expect("fill");
        let mut bad_at = std::collections::BTreeMap::<usize, usize>::new();
        let (mut up, mut down) = (0usize, 0usize);
        for _ in 0..reads.max(1) {
            let mut got = vec![0u8; GUEST_BYTES];
            CopyMem(link).read(d, &mut got).expect("read");
            for k in 0..GUEST_BYTES {
                let x = got[k] ^ want[k];
                if x != 0 {
                    *bad_at.entry(k).or_default() += 1;
                    up += (x & got[k]).count_ones() as usize;
                    down += (x & want[k]).count_ones() as usize;
                }
            }
        }
        let every = bad_at.values().filter(|&&n| n == reads.max(1)).count();
        let sample: Vec<String> = bad_at.iter().take(10).map(|(k, n)| format!("{k:#06x}x{n}")).collect();
        println!("{name}: {reads} reads of 64 KiB: {} bytes ever wrong, {every} wrong on every read; bits 0->1 {up}, 1->0 {down}; {}",
                 bad_at.len(), sample.join(" "));
    }
}
