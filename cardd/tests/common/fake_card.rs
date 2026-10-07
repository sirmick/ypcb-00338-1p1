//! A model of the card in Rust: the same command semantics as the RTL (soc/src/main/scala/card/
//! Link.scala), so cardd's backend can be tested end to end without a simulator. The co-simulation
//! (S3) replaces it with the RTL itself.
#![allow(dead_code)]

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use cardd::bar::Bar;
use cardd::backend::inside_boot_window;
use cardd::contract::{bar0, command, copy_status, record, Layout, MAGIC, VERSION};
use cardd::link::HostMem;
use cardd::mem::inside_windows;
use cardd::message::{self, SIZE};

#[derive(Default, Clone, Copy)]
pub struct QueueInfo {
    pub size: u16,
    pub ready: bool,
    pub used: u64,
    pub used_idx: u16,
}

#[derive(Default)]
pub struct State {
    pub regs: HashMap<usize, u32>,
    pub ring: Vec<u8>,
    pub inbox: Vec<u8>,
    pub staging: Vec<u8>,
    pub guest: HashMap<u64, u8>,
    pub seq: u32,
    pub cmd_next: u32,
    pub interrupt: [u32; 8],
    pub queues: [[QueueInfo; 2]; 8],
    pub refused: bool,
    pub commands: Vec<String>,
    /// bytes CONSOLE_RX delivered to the 16550
    pub console_rx: Vec<u8>,
}

impl State {
    pub fn guest_write(&mut self, a: u64, b: &[u8]) {
        for (i, &v) in b.iter().enumerate() { self.guest.insert(a + i as u64, v); }
    }
    pub fn guest_read(&self, a: u64, n: usize) -> Vec<u8> {
        (0..n).map(|i| *self.guest.get(&(a + i as u64)).unwrap_or(&0)).collect()
    }
    fn reg(&self, r: usize) -> u32 { *self.regs.get(&r).unwrap_or(&0) }
    /// GUEST_RESET: held (1) until the host writes 0
    pub fn guest_reset(&self) -> bool { self.regs.get(&bar0::reg::GUEST_RESET).is_none_or(|&v| v & 1 == 1) }
    fn entries(&self) -> usize { 1 << self.reg(bar0::reg::INBOX_ENTRIES_LOG2) }

    /// Writes a record into the host inbox, numbered as the card numbers them.
    pub fn push_record(&mut self, layout: &Layout, slot: u16, values: &[(&str, &[u8])]) {
        self.seq += 1;
        let b = message::encode(layout, self.seq, slot, values).unwrap();
        let off = ((self.seq - 1) as usize % self.entries()) * SIZE;
        if self.inbox.len() < self.entries() * SIZE { self.inbox.resize(self.entries() * SIZE, 0); }
        self.inbox[off..off + SIZE].copy_from_slice(&b);
    }
    /// A record the guest's actions produce (STATUS, FEATURES, QUEUE, NOTIFY), with the card's own
    /// bookkeeping (a QUEUE record enables the queue).
    pub fn guest_record(&mut self, layout: &Layout, slot: u16, values: &[(&str, &[u8])]) {
        let b = message::encode(layout, 1, slot, values).unwrap();
        if layout.kind == record::QUEUE.kind {
            let q = message::field_u64(layout, &b, "queue").unwrap() as usize;
            self.queues[slot as usize][q] = QueueInfo {
                size: message::field_u64(layout, &b, "size").unwrap() as u16,
                ready: message::field_u64(layout, &b, "ready").unwrap() != 0,
                used: message::field_u64(layout, &b, "device").unwrap(),
                used_idx: 0,
            };
        }
        self.push_record(layout, slot, values);
    }

    fn execute(&mut self) {
        while self.cmd_next <= self.reg(bar0::reg::CMD_PRODUCED) {
            let n = self.cmd_next;
            let base = ((n - 1) % (1 << bar0::CMD_ENTRIES_LOG2)) as usize * SIZE;
            let b: [u8; SIZE] = self.ring[base..base + SIZE].try_into().unwrap();
            self.cmd_next += 1;
            let h = match message::decode(&b) {
                Ok(h) if h.seq == n && (h.slot as usize) < 8 => h,
                _ => { self.refused = true; self.ack(n); continue }
            };
            let f = |l: &Layout, name| message::field_u64(l, &b, name).unwrap();
            match h.kind {
                k if k == command::INTERRUPT.kind => self.interrupt[h.slot as usize] |= f(&command::INTERRUPT, "bits") as u32,
                k if k == command::COPY_TO_HOST.kind || k == command::COPY_FROM_HOST.kind => {
                    let l = &command::COPY_TO_HOST;
                    let (tag, len, guest, st) = (f(l, "tag") as u32, f(l, "len") as usize, f(l, "guest"), f(l, "staging") as usize);
                    let status = if len == 0 || st + len > self.reg(bar0::reg::STAGING_SIZE) as usize {
                        copy_status::BAD_LENGTH
                    } else if st % 64 != (guest % 64) as usize {
                        copy_status::MISALIGNED
                    } else if !inside_windows(guest, len) && !(self.guest_reset() && inside_boot_window(guest, len)) {
                        copy_status::OUTSIDE_WINDOW
                    } else {
                        if self.staging.len() < st + len { self.staging.resize(st + len, 0); }
                        if k == command::COPY_TO_HOST.kind {
                            let d = self.guest_read(guest, len);
                            self.staging[st..st + len].copy_from_slice(&d);
                        } else {
                            let d = self.staging[st..st + len].to_vec();
                            self.guest_write(guest, &d);
                        }
                        copy_status::DONE
                    };
                    self.commands.push(format!("{} {len}@{guest:#x} -> {status}", if k == command::COPY_TO_HOST.kind { "to_host" } else { "from_host" }));
                    self.push_record(&record::COPY_DONE, h.slot, &[("tag", &tag.to_le_bytes()), ("status", &status.to_le_bytes())]);
                }
                k if k == command::USED_PUSH.kind => {
                    let l = &command::USED_PUSH;
                    let (q, id, len) = (f(l, "queue") as usize, f(l, "id") as u32, f(l, "len") as u32);
                    let qi = self.queues[h.slot as usize].get(q).copied().unwrap_or_default();
                    let ok = qi.ready && qi.size.is_power_of_two() && qi.used % 8 == 0 && inside_windows(qi.used, 6 + 8 * qi.size as usize);
                    if ok {
                        let e = qi.used + 4 + 8 * (qi.used_idx & (qi.size - 1)) as u64;
                        self.guest_write(e, &id.to_le_bytes());
                        self.guest_write(e + 4, &len.to_le_bytes());
                        let idx = qi.used_idx.wrapping_add(1);
                        self.guest_write(qi.used + 2, &idx.to_le_bytes());
                        self.queues[h.slot as usize][q].used_idx = idx;
                        self.commands.push(format!("used_push id {id} len {len}"));
                    } else {
                        self.refused = true;
                    }
                }
                k if k == command::CONSOLE_RX.kind => {
                    let n = (f(&command::CONSOLE_RX, "count") as usize).min(48);
                    let data = message::field(&command::CONSOLE_RX, &b, "data").unwrap();
                    self.console_rx.extend_from_slice(&data[..n]);
                }
                _ => self.refused = true,
            }
            self.ack(n);
        }
    }
    fn ack(&mut self, n: u32) {
        self.push_record(&record::CMD_ACK, 0, &[("cmd_seq", &n.to_le_bytes())]);
    }
}

/// BAR0 of the model.
pub struct FakeBar(pub Rc<RefCell<State>>);
impl Bar for FakeBar {
    fn read32(&mut self, offset: usize) -> u32 {
        let s = self.0.borrow();
        match offset {
            bar0::reg::MAGIC => MAGIC,
            bar0::reg::VERSION => VERSION,
            bar0::reg::GUEST_RESET => s.guest_reset() as u32,
            bar0::reg::STATUS => 8 | (s.refused as u32) << 2 | (s.reg(bar0::reg::ENABLE) & 1), // main memory ready
            _ => 0,
        }
    }
    fn write32(&mut self, offset: usize, value: u32) {
        let mut s = self.0.borrow_mut();
        if offset >= bar0::CMD_RING && offset < bar0::CMD_RING + (SIZE << bar0::CMD_ENTRIES_LOG2) {
            if s.ring.is_empty() { s.ring = vec![0; SIZE << bar0::CMD_ENTRIES_LOG2]; }
            let o = offset - bar0::CMD_RING;
            s.ring[o..o + 4].copy_from_slice(&value.to_le_bytes());
            return;
        }
        s.regs.insert(offset, value);
        if offset == bar0::reg::ENABLE && value == 1 { s.seq = 0; s.cmd_next = 1; }
        if offset == bar0::reg::CMD_PRODUCED { s.execute(); }
    }
}

/// The model's host inbox, as cardd reads it.
pub struct FakeInbox(pub Rc<RefCell<State>>);
impl HostMem for FakeInbox {
    fn read(&mut self, offset: usize, buf: &mut [u8]) {
        let s = self.0.borrow();
        for (i, b) in buf.iter_mut().enumerate() { *b = *s.inbox.get(offset + i).unwrap_or(&0); }
    }
    fn write(&mut self, _: usize, _: &[u8]) { panic!("the host never writes its inbox") }
}

/// Host staging, shared by cardd and the model.
pub struct FakeStaging(pub Rc<RefCell<State>>);
impl HostMem for FakeStaging {
    fn read(&mut self, offset: usize, buf: &mut [u8]) {
        let s = self.0.borrow();
        for (i, b) in buf.iter_mut().enumerate() { *b = *s.staging.get(offset + i).unwrap_or(&0); }
    }
    fn write(&mut self, offset: usize, data: &[u8]) {
        let mut s = self.0.borrow_mut();
        if s.staging.len() < offset + data.len() { s.staging.resize(offset + data.len(), 0); }
        s.staging[offset..offset + data.len()].copy_from_slice(data);
    }
}
