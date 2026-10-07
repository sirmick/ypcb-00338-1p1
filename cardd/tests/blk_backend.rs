//! cardd's phase-1 backend serving virtio-blk against a model of the card: the blk_read transcript's
//! guest side drives it, and the guest's memory must end as the transcript says. Then hostile chains.

#[path = "generated/transcripts.rs"]
mod transcripts;
mod common;

use std::cell::RefCell;
use std::rc::Rc;

use cardd::backend::{self, BackendError, BlkSlot, Link};
use cardd::contract::{map::DMA_REGION, record};
use cardd::dev::blk::{self, Disk};
use cardd::link;
use cardd::mem::MemError;
use cardd::queue::QueueError;
use cardd::slot::Slot;
use common::fake_card::{FakeBar, FakeInbox, FakeStaging, State};
use transcripts::{Step, TRANSCRIPTS};

struct VecDisk(Vec<u8>);
impl Disk for VecDisk {
    fn sectors(&self) -> u64 { self.0.len() as u64 / blk::SECTOR }
    fn read(&mut self, s: u64, buf: &mut [u8]) -> Result<(), ()> {
        let o = (s * blk::SECTOR) as usize;
        buf.copy_from_slice(self.0.get(o..o + buf.len()).ok_or(())?);
        Ok(())
    }
    fn write(&mut self, s: u64, data: &[u8]) -> Result<(), ()> {
        let o = (s * blk::SECTOR) as usize;
        self.0.get_mut(o..o + data.len()).ok_or(())?.copy_from_slice(data);
        Ok(())
    }
    fn flush(&mut self) -> Result<(), ()> { Ok(()) }
}

fn rig(disk: Vec<u8>) -> (Rc<RefCell<State>>, Link, BlkSlot) {
    let st = Rc::new(RefCell::new(State::default()));
    let mut link = Link::new(Box::new(FakeBar(st.clone())), Box::new(FakeInbox(st.clone())), 6, Box::new(FakeStaging(st.clone())), 1 << 16);
    link::set_up(link.bar.as_mut(), 0x4000_0000, 6, 0x5000_0000, 1 << 16).unwrap();
    let blk = BlkSlot { slot: 0, state: Slot::default(), queue: None, disk: Box::new(VecDisk(disk)) };
    link::program_slot(link.bar.as_mut(), 0, &blk.device(256));
    (st, link, blk)
}

#[test]
fn blk_read_is_served_as_the_transcript_says() {
    let t = TRANSCRIPTS.iter().find(|t| t.name == "blk_read").unwrap();
    let sector5 = t.steps.iter().find_map(|s| match s { Step::StagingWrite { bytes, .. } if bytes.len() == 512 => Some(*bytes), _ => None }).unwrap();
    let mut disk = vec![0u8; 16 * 512];
    disk[5 * 512..6 * 512].copy_from_slice(sector5);
    let (st, mut link, mut blk) = rig(disk);
    // the guest's side of the transcript: its memory, and the records its register writes produce
    for s in t.steps {
        match s {
            Step::GuestMem { address, bytes } => st.borrow_mut().guest_write(*address, bytes),
            Step::ExpectRecord(m) if [record::STATUS.kind, record::FEATURES.kind, record::QUEUE.kind, record::NOTIFY.kind].contains(&m.layout.kind) => {
                st.borrow_mut().guest_record(m.layout, m.slot, m.values)
            }
            _ => {}
        }
    }
    let served = backend::serve(&mut link, &mut blk).unwrap();
    assert_eq!(served, 1);
    let s = st.borrow();
    for step in t.steps {
        if let Step::ExpectGuestMem { address, bytes } = step {
            assert_eq!(s.guest_read(*address, bytes.len()), *bytes, "guest memory at {address:#x}");
        }
    }
    assert_eq!(s.interrupt[0] & 1, 1, "no interrupt");
    assert!(!s.refused, "the card refused a command: {:?}", s.commands);
}

/// Puts one request (header, data segments, status) on queue 0 and notifies.
fn request(st: &Rc<RefCell<State>>, kind: u32, sector: u64, data: &[(u64, u32, bool)], descs: Option<Vec<[u8; 16]>>) {
    let d = DMA_REGION;
    let mut s = st.borrow_mut();
    let mut table = Vec::new();
    let mut segs = vec![(d + 0x3000, 16u32, false)];
    segs.extend_from_slice(data);
    segs.push((d + 0x3800, 1, true));
    for (i, (a, l, w)) in segs.iter().enumerate() {
        let mut e = [0u8; 16];
        e[0..8].copy_from_slice(&a.to_le_bytes());
        e[8..12].copy_from_slice(&l.to_le_bytes());
        let flags: u16 = if i + 1 < segs.len() { 1 } else { 0 } | if *w { 2 } else { 0 };
        e[12..14].copy_from_slice(&flags.to_le_bytes());
        e[14..16].copy_from_slice(&((i + 1) as u16).to_le_bytes());
        table.push(e);
    }
    for (i, e) in descs.unwrap_or(table).iter().enumerate() { s.guest_write(d + 16 * i as u64, e); }
    let mut h = [0u8; 16];
    h[0..4].copy_from_slice(&kind.to_le_bytes());
    h[8..16].copy_from_slice(&sector.to_le_bytes());
    s.guest_write(d + 0x3000, &h);
    s.guest_write(d + 0x1000, &[0, 0, 1, 0, 0, 0]); // avail: flags 0, idx 1, ring[0] = 0
    s.guest_record(&record::QUEUE, 0, &[("queue", &[0, 0]), ("size", &8u16.to_le_bytes()), ("ready", &[1]),
        ("desc", &d.to_le_bytes()), ("driver", &(d + 0x1000).to_le_bytes()), ("device", &(d + 0x2000).to_le_bytes())]);
    s.guest_record(&record::NOTIFY, 0, &[("queue", &[0, 0])]);
}

#[test]
fn a_write_request_reaches_the_disk() {
    let (st, mut link, mut blk) = rig(vec![0u8; 16 * 512]);
    let data: Vec<u8> = (0..1024).map(|i| (i * 3) as u8).collect();
    st.borrow_mut().guest_write(DMA_REGION + 0x4000, &data);
    request(&st, blk::T_OUT, 2, &[(DMA_REGION + 0x4000, 512, false), (DMA_REGION + 0x4200, 512, false)], None);
    assert_eq!(backend::serve(&mut link, &mut blk).unwrap(), 1);
    let mut back = vec![0u8; 1024];
    blk.disk.read(2, &mut back).unwrap();
    assert_eq!(back, data);
    let s = st.borrow();
    assert_eq!(s.guest_read(DMA_REGION + 0x3800, 1), vec![blk::S_OK]);
    assert_eq!(s.guest_read(DMA_REGION + 0x2004, 8), [0, 0, 0, 0, 1, 0, 0, 0], "used entry: head 0, len 1 (the status byte)");
}

#[test]
fn get_id_and_a_read_past_the_end() {
    let (st, mut link, mut blk) = rig(vec![0u8; 4 * 512]);
    request(&st, blk::T_GET_ID, 0, &[(DMA_REGION + 0x4000, 20, true)], None);
    backend::serve(&mut link, &mut blk).unwrap();
    assert_eq!(&st.borrow().guest_read(DMA_REGION + 0x4000, blk::ID.len()), blk::ID);
    let (st, mut link, mut blk) = rig(vec![0u8; 4 * 512]);
    request(&st, blk::T_IN, 4, &[(DMA_REGION + 0x4000, 512, true)], None);
    backend::serve(&mut link, &mut blk).unwrap();
    assert_eq!(st.borrow().guest_read(DMA_REGION + 0x3800, 1), vec![blk::S_IOERR], "sector 4 of a 4-sector disk");
}

fn desc(addr: u64, len: u32, flags: u16, next: u16) -> [u8; 16] {
    let mut e = [0u8; 16];
    e[0..8].copy_from_slice(&addr.to_le_bytes());
    e[8..12].copy_from_slice(&len.to_le_bytes());
    e[12..14].copy_from_slice(&flags.to_le_bytes());
    e[14..16].copy_from_slice(&next.to_le_bytes());
    e
}

#[test]
fn hostile_chains_are_refused_without_a_panic() {
    let d = DMA_REGION;
    let cases: Vec<(&str, Vec<[u8; 16]>, fn(&BackendError) -> bool)> = vec![
        ("a loop", vec![desc(d + 0x3000, 16, 1, 1), desc(d + 0x3100, 16, 1, 0)], |e| matches!(e, BackendError::Queue(QueueError::Loop))),
        ("a buffer in main memory", vec![desc(d + 0x3000, 16, 1, 1), desc(0x8000_0000, 512, 3, 2), desc(d + 0x3800, 1, 2, 0)],
            |e| matches!(e, BackendError::Mem(MemError::OutsideWindow { .. }))),
        ("indirect", vec![desc(d + 0x3000, 16, 4, 0)], |e| matches!(e, BackendError::Queue(QueueError::Indirect))),
        ("readable after writable", vec![desc(d + 0x3000, 16, 3, 1), desc(d + 0x3100, 16, 0, 0)], |e| matches!(e, BackendError::Queue(QueueError::Order))),
        ("next outside the queue", vec![desc(d + 0x3000, 16, 1, 9)], |e| matches!(e, BackendError::Queue(QueueError::BadNext(9)))),
    ];
    for (what, table, expected) in cases {
        let (st, mut link, mut blk) = rig(vec![0u8; 16 * 512]);
        request(&st, blk::T_IN, 0, &[], Some(table));
        let err = backend::serve(&mut link, &mut blk).expect_err(what);
        assert!(expected(&err), "{what}: {err:?}");
        let s = st.borrow();
        assert_eq!(s.guest_read(d + 0x2002, 2), vec![0, 0], "{what}: a used entry was published");
        assert!(!s.commands.iter().any(|c| c.contains("0x80000000")), "{what}: the card was asked to copy main memory");
    }
    // the guest claims more available entries than the queue holds
    let (st, mut link, mut blk) = rig(vec![0u8; 16 * 512]);
    request(&st, blk::T_IN, 0, &[(d + 0x4000, 512, true)], None);
    st.borrow_mut().guest_write(d + 0x1002, &100u16.to_le_bytes());
    assert!(matches!(backend::serve(&mut link, &mut blk), Err(BackendError::Queue(QueueError::Overrun { .. }))));
}
