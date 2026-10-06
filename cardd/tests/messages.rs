//! The shared transcripts (generated from the RTL's contract): cardd lays out and reads every
//! message exactly as the RTL does.

#[path = "generated/transcripts.rs"]
mod transcripts;

use cardd::contract::{command, record};
use cardd::message::{self, Error};
use transcripts::{Step, TRANSCRIPTS};

fn messages() -> impl Iterator<Item = (&'static str, &'static transcripts::Msg, bool)> {
    TRANSCRIPTS.iter().flat_map(|t| {
        t.steps.iter().filter_map(move |s| match s {
            Step::ExpectRecord(m) => Some((t.name, m, true)),
            Step::HostCommand(m) => Some((t.name, m, false)),
            _ => None,
        })
    })
}

#[test]
fn every_message_encodes_to_the_rtls_bytes() {
    let mut n = 0;
    for (t, m, _) in messages() {
        let b = message::encode(m.layout, m.seq, m.slot, m.values).unwrap();
        assert_eq!(b, m.bytes, "{t}: {} seq {}", m.layout.name, m.seq);
        n += 1;
    }
    assert!(n >= 12, "only {n} messages in the transcripts");
}

#[test]
fn every_message_decodes_back_to_its_fields() {
    for (t, m, is_record) in messages() {
        let h = message::decode(&m.bytes).unwrap();
        assert_eq!((h.seq, h.slot), (m.seq, m.slot), "{t}");
        let table = if is_record { record::ALL } else { command::ALL };
        let layout = message::layout_of(table, &h).unwrap();
        assert_eq!(layout, m.layout, "{t}");
        for (name, v) in m.values {
            let f = message::field(layout, &m.bytes, name).unwrap();
            assert_eq!(&f[..v.len()], *v, "{t}: {name}");
        }
    }
}

#[test]
fn sequence_numbers_count_from_one_per_stream() {
    for t in TRANSCRIPTS {
        let (mut r, mut c) = (0, 0);
        for s in t.steps {
            match s {
                Step::ExpectRecord(m) => { r += 1; assert_eq!(m.seq, r, "{}", t.name) }
                Step::HostCommand(m) => { c += 1; assert_eq!(m.seq, c, "{}", t.name) }
                _ => {}
            }
        }
    }
}

#[test]
fn a_torn_or_empty_message_is_refused() {
    let m = messages().next().unwrap().1;
    let mut torn = m.bytes;
    torn[60] ^= 1;
    assert!(matches!(message::decode(&torn), Err(Error::Torn { .. })));
    assert_eq!(message::decode(&[0u8; 64]), Err(Error::Empty));
}

#[test]
fn unknown_fields_and_wide_values_are_refused() {
    assert!(matches!(message::encode(&record::NOTIFY, 1, 0, &[("nope", &[1])]), Err(Error::UnknownField(..))));
    assert!(matches!(message::encode(&record::NOTIFY, 1, 0, &[("queue", &[1, 2, 3])]), Err(Error::TooWide(_))));
    let mut b = message::encode(&record::NOTIFY, 9, 0, &[]).unwrap();
    b[4] = 0xee;
    b[5] = 0xee;
    let h = message::decode(&b).unwrap();
    assert_eq!(message::layout_of(record::ALL, &h), Err(Error::UnknownKind(0xeeee)));
}
