//! A virtio slot as the card's records describe it: the guest driver's status, the features it
//! accepted, and each queue's size and ring addresses (guest addresses, never dereferenced here).

use crate::contract::{record, virtio_mmio};
use crate::message::{self, Error, SIZE};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Queue {
    pub size: u16,
    pub ready: bool,
    pub desc: u64,
    pub driver: u64,
    pub device: u64,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Slot {
    pub status: u8,
    pub features: u64,
    pub queues: [Queue; virtio_mmio::QUEUES_PER_SLOT],
    /// queues the guest has notified since the backend last looked
    pub notified: Vec<u16>,
}

/// What a record changed.
#[derive(Debug, PartialEq, Eq)]
pub enum Event {
    Status(u8),
    Reset,
    Features(u64),
    QueueReady(u16),
    Notify(u16),
    Other,
}

impl Slot {
    /// Applies one record addressed to this slot. A queue index outside the slot is refused.
    pub fn apply(&mut self, kind: u16, b: &[u8; SIZE]) -> Result<Event, Error> {
        let f = |l, n| message::field_u64(l, b, n);
        Ok(match kind {
            k if k == record::STATUS.kind => {
                let s = f(&record::STATUS, "status")? as u8;
                self.status = s;
                if s == 0 {
                    *self = Slot::default();
                    Event::Reset
                } else {
                    Event::Status(s)
                }
            }
            k if k == record::FEATURES.kind => {
                self.features = f(&record::FEATURES, "features")?;
                Event::Features(self.features)
            }
            k if k == record::QUEUE.kind => {
                let i = f(&record::QUEUE, "queue")? as usize;
                let q = self.queues.get_mut(i).ok_or(Error::UnknownKind(kind))?;
                *q = Queue {
                    size: f(&record::QUEUE, "size")? as u16,
                    ready: f(&record::QUEUE, "ready")? != 0,
                    desc: f(&record::QUEUE, "desc")?,
                    driver: f(&record::QUEUE, "driver")?,
                    device: f(&record::QUEUE, "device")?,
                };
                Event::QueueReady(i as u16)
            }
            k if k == record::NOTIFY.kind => {
                let q = f(&record::NOTIFY, "queue")? as u16;
                self.notified.push(q);
                Event::Notify(q)
            }
            _ => Event::Other,
        })
    }
}
