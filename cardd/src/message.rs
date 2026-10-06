//! Records and commands: 64 bytes, little-endian, the sequence number at both ends.
//!
//! What the card writes is hostile until checked: [`decode`] refuses an empty slot (sequence 0) and
//! a torn message (the two copies of the sequence number differ) before anything reads a field.

use crate::contract::{message as m, Layout};

pub const SIZE: usize = m::SIZE;

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    /// The field is not part of this layout.
    UnknownField(&'static str, String),
    /// The value has more bytes than the field holds.
    TooWide(String),
    /// Sequence number 0: nothing has been written here yet.
    Empty,
    /// The sequence number at the head differs from the copy at the tail.
    Torn { head: u32, tail: u32 },
    /// The kind is not one the layout table knows.
    UnknownKind(u16),
}

/// A message's header, once its two sequence numbers agree.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct Header {
    pub seq: u32,
    pub kind: u16,
    pub slot: u16,
}

fn get(b: &[u8; SIZE], off: usize, n: usize) -> u64 {
    b[off..off + n].iter().rev().fold(0u64, |acc, &x| (acc << 8) | x as u64)
}

/// Lays out a message. `values` are little-endian byte strings no longer than their fields; missing
/// fields are zero.
pub fn encode(layout: &Layout, seq: u32, slot: u16, values: &[(&str, &[u8])]) -> Result<[u8; SIZE], Error> {
    let mut b = [0u8; SIZE];
    b[m::SEQ_OFFSET..m::SEQ_OFFSET + 4].copy_from_slice(&seq.to_le_bytes());
    b[m::KIND_OFFSET..m::KIND_OFFSET + 2].copy_from_slice(&layout.kind.to_le_bytes());
    b[m::SLOT_OFFSET..m::SLOT_OFFSET + 2].copy_from_slice(&slot.to_le_bytes());
    b[m::TAIL_OFFSET..m::TAIL_OFFSET + 4].copy_from_slice(&seq.to_le_bytes());
    for (name, v) in values {
        let f = layout
            .fields
            .iter()
            .find(|f| f.name == *name)
            .ok_or_else(|| Error::UnknownField(layout.name, name.to_string()))?;
        if v.len() > f.bytes {
            return Err(Error::TooWide(name.to_string()));
        }
        b[f.offset..f.offset + v.len()].copy_from_slice(v);
    }
    Ok(b)
}

/// Checks a message's header: not empty, not torn.
pub fn decode(b: &[u8; SIZE]) -> Result<Header, Error> {
    let head = get(b, m::SEQ_OFFSET, 4) as u32;
    let tail = get(b, m::TAIL_OFFSET, 4) as u32;
    if head == 0 && tail == 0 {
        return Err(Error::Empty);
    }
    if head != tail {
        return Err(Error::Torn { head, tail });
    }
    Ok(Header { seq: head, kind: get(b, m::KIND_OFFSET, 2) as u16, slot: get(b, m::SLOT_OFFSET, 2) as u16 })
}

/// Finds the layout for a decoded header's kind in a table (`contract::record::ALL` or
/// `contract::command::ALL`).
pub fn layout_of(table: &[&'static Layout], h: &Header) -> Result<&'static Layout, Error> {
    table.iter().copied().find(|l| l.kind == h.kind).ok_or(Error::UnknownKind(h.kind))
}

/// A field's bytes.
pub fn field<'a>(layout: &Layout, b: &'a [u8; SIZE], name: &str) -> Result<&'a [u8], Error> {
    let f = layout
        .fields
        .iter()
        .find(|f| f.name == name)
        .ok_or_else(|| Error::UnknownField(layout.name, name.to_string()))?;
    Ok(&b[f.offset..f.offset + f.bytes])
}

/// A field of up to 8 bytes, as a number.
pub fn field_u64(layout: &Layout, b: &[u8; SIZE], name: &str) -> Result<u64, Error> {
    let v = field(layout, b, name)?;
    if v.len() > 8 {
        return Err(Error::TooWide(name.to_string()));
    }
    Ok(v.iter().rev().fold(0u64, |acc, &x| (acc << 8) | x as u64))
}
