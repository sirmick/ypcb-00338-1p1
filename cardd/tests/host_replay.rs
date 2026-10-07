//! S2, host side: cardd replays the shared transcripts against a fake card. The RTL replays the
//! same transcripts in SpinalSim (soc/src/test/scala/card/CardLinkSpec.scala).

#[path = "generated/transcripts.rs"]
mod transcripts;

use cardd::bar::Bar;
use cardd::contract::{bar0, record, virtio_mmio, MAGIC, VERSION};
use cardd::link::{self, Commands, Device, HostMem, Inbox, LinkError};
use cardd::message::{self, SIZE};
use cardd::slot::{Event, Slot};
use transcripts::{Step, TRANSCRIPTS};

/// A BAR0 that remembers every write, and answers MAGIC and VERSION as the card does.
#[derive(Default)]
struct FakeBar {
    writes: Vec<(usize, u32)>,
}

impl Bar for FakeBar {
    fn read32(&mut self, offset: usize) -> u32 {
        match offset {
            bar0::reg::MAGIC => MAGIC,
            bar0::reg::VERSION => VERSION,
            _ => 0,
        }
    }
    fn write32(&mut self, offset: usize, value: u32) {
        self.writes.push((offset, value));
    }
}

/// An inbox in plain memory.
struct Ring(Vec<[u8; SIZE]>);
impl HostMem for Ring {
    fn read(&mut self, offset: usize, buf: &mut [u8]) {
        buf.copy_from_slice(&self.0[offset / SIZE][..buf.len()]);
    }
    fn write(&mut self, offset: usize, data: &[u8]) {
        self.0[offset / SIZE][..data.len()].copy_from_slice(data);
    }
}

fn u32_of(v: &[u8]) -> u32 {
    v.iter().rev().fold(0u32, |a, &x| (a << 8) | x as u32)
}

#[test]
fn transcripts_replay_on_the_host_side() {
    for t in TRANSCRIPTS.iter().filter(|t| t.steps.iter().any(|s| matches!(s, Step::HostProgram { .. }))) {
        let mut bar = FakeBar::default();
        link::set_up(&mut bar, 0x4000_0000, 3, 0x5000_0000, 1 << 20).unwrap();
        assert_eq!(bar.writes.last(), Some(&(bar0::reg::ENABLE, 1)), "{}", t.name);
        bar.writes.clear();

        // the slot programming the transcript expects, and what cardd writes for the same device
        let programmed: Vec<(usize, u32)> = t
            .steps
            .iter()
            .filter_map(|s| match s {
                Step::HostProgram { slot, reg, value } => Some((bar0::SLOT_PROG_BASE + slot * bar0::SLOT_PROG_STRIDE + reg, *value)),
                _ => None,
            })
            .collect();
        let get = |r: usize| programmed.iter().find(|(o, _)| *o == bar0::SLOT_PROG_BASE + r).map(|p| p.1).unwrap_or(0);
        let device = Device {
            device_id: get(bar0::slot_reg::DEVICE_ID),
            features: get(bar0::slot_reg::FEATURES_LO) as u64 | (get(bar0::slot_reg::FEATURES_HI) as u64) << 32,
            queue_num_max: get(bar0::slot_reg::QUEUE_NUM_MAX),
            config: &[],
        };
        link::program_slot(&mut bar, 0, &device);
        assert_eq!(bar.writes, programmed, "{}: slot programming", t.name);
        bar.writes.clear();

        let mut inbox = Inbox::new(3);
        let mut mem = Ring(vec![[0u8; SIZE]; inbox.entries()]);
        let mut commands = Commands::default();
        let mut slots = vec![Slot::default(); 8];
        for s in t.steps {
            match s {
                Step::ExpectRecord(m) => {
                    // the card writes the record; cardd must take exactly it, and acknowledge it
                    assert_eq!(inbox.poll(&mut mem, &mut bar).unwrap(), None, "{}: a record before the card wrote one", t.name);
                    mem.0[inbox.slot_of(m.seq)] = m.bytes;
                    let (h, b) = inbox.poll(&mut mem, &mut bar).unwrap().expect("record not taken");
                    assert_eq!((h.seq, h.slot), (m.seq, m.slot));
                    assert_eq!(bar.writes.pop(), Some((bar0::reg::INBOX_CONSUMED, m.seq)));
                    if h.kind == record::CMD_ACK.kind {
                        commands.acked(message::field_u64(&record::CMD_ACK, &b, "cmd_seq").unwrap() as u32);
                    } else {
                        slots[h.slot as usize].apply(h.kind, &b).unwrap();
                    }
                }
                Step::HostCommand(m) => {
                    let seq = commands.send(&mut bar, m.layout, m.slot, m.values).unwrap();
                    assert_eq!(seq, m.seq);
                    let base = bar0::CMD_RING + ((seq - 1) % Commands::ENTRIES) as usize * SIZE;
                    let mut expected: Vec<(usize, u32)> = m.bytes.chunks(4).enumerate().map(|(i, w)| (base + 4 * i, u32_of(w))).collect();
                    expected.push((bar0::reg::CMD_PRODUCED, seq));
                    assert_eq!(bar.writes, expected, "{}: command {}", t.name, m.layout.name);
                    bar.writes.clear();
                }
                _ => {} // the guest's side and the interrupt line are the RTL's to check
            }
        }
        if t.name == "blk_init" {
            let s = &slots[0];
            let ok = virtio_mmio::STATUS_ACKNOWLEDGE | virtio_mmio::STATUS_DRIVER | virtio_mmio::STATUS_FEATURES_OK | virtio_mmio::STATUS_DRIVER_OK;
            assert_eq!(s.status, ok);
            assert_eq!(s.features, (1 << virtio_mmio::F_EVENT_IDX) | (1 << virtio_mmio::F_VERSION_1) | (1 << virtio_mmio::F_ACCESS_PLATFORM));
            assert_eq!((s.queues[0].size, s.queues[0].ready), (128, true));
            assert_eq!(s.queues[0].desc, cardd::contract::map::DMA_REGION);
            assert_eq!(s.notified, vec![0]);
        }
    }
}

fn notify(seq: u32) -> [u8; SIZE] {
    message::encode(&record::NOTIFY, seq, 0, &[("queue", &[0, 0])]).unwrap()
}

#[test]
fn the_inbox_waits_for_torn_and_stale_records_and_refuses_skips() {
    let mut bar = FakeBar::default();
    let mut inbox = Inbox::new(1); // two entries
    let mut mem = Ring(vec![[0u8; SIZE]; 2]);
    let mut torn = notify(1);
    torn[60] = 7;
    mem.0[0] = torn;
    assert_eq!(inbox.poll(&mut mem, &mut bar), Ok(None), "a torn record is not taken");
    mem.0[0] = notify(1);
    assert!(inbox.poll(&mut mem, &mut bar).unwrap().is_some());
    mem.0[1] = notify(2);
    assert!(inbox.poll(&mut mem, &mut bar).unwrap().is_some());
    assert_eq!(inbox.poll(&mut mem, &mut bar), Ok(None), "record 1 is from the last lap: stale");
    mem.0[0] = notify(5);
    assert_eq!(inbox.poll(&mut mem, &mut bar), Err(LinkError::Skipped { expected: 3, found: 5 }));
}

#[test]
fn the_command_ring_stops_when_the_card_is_behind() {
    let mut bar = FakeBar::default();
    let mut c = Commands::default();
    for _ in 0..Commands::ENTRIES {
        c.send(&mut bar, &cardd::contract::command::INTERRUPT, 0, &[("bits", &[1])]).unwrap();
    }
    assert_eq!(c.send(&mut bar, &cardd::contract::command::INTERRUPT, 0, &[]), Err(LinkError::RingFull));
    c.acked(1);
    assert!(c.send(&mut bar, &cardd::contract::command::INTERRUPT, 0, &[]).is_ok());
}

#[test]
fn a_card_with_the_wrong_magic_or_version_is_refused() {
    struct Wrong(u32, u32);
    impl Bar for Wrong {
        fn read32(&mut self, o: usize) -> u32 {
            if o == bar0::reg::MAGIC { self.0 } else { self.1 }
        }
        fn write32(&mut self, _: usize, _: u32) {
            panic!("wrote to a card that failed the checks");
        }
    }
    assert_eq!(link::set_up(&mut Wrong(0x1234, VERSION), 0, 3, 0, 0), Err(LinkError::NotTheCard(0x1234)));
    assert_eq!(link::set_up(&mut Wrong(MAGIC, 99), 0, 3, 0, 0), Err(LinkError::Version(99)));
}

#[test]
fn a_reset_clears_the_slot() {
    let mut s = Slot::default();
    let st = |v: u8| message::encode(&record::STATUS, 1, 0, &[("status", &[v])]).unwrap();
    assert_eq!(s.apply(record::STATUS.kind, &st(3)), Ok(Event::Status(3)));
    assert_eq!(s.apply(record::STATUS.kind, &st(0)), Ok(Event::Reset));
    assert_eq!(s, Slot::default());
}
