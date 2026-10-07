// Generated from soc/src/main/scala/card/contract/Contract.scala by Generate.scala. Do not edit.
#![allow(dead_code)]

/// Contract version, read at BAR0 VERSION; cardd refuses any other.
pub const VERSION: u32 = 1;
/// BAR0 MAGIC: "CARD".
pub const MAGIC: u32 = 0x44524143;

/// The guest's address map (QEMU virt's).
pub mod map {
    pub const CLINT: u64 = 0x2000000;
    pub const PLIC: u64 = 0xc000000;
    pub const UART: u64 = 0x10000000;
    pub const UART_IRQ: u32 = 10;
    pub const VIRTIO_BASE: u64 = 0x10001000;
    pub const VIRTIO_STRIDE: u64 = 0x1000;
    pub const VIRTIO_SLOTS: usize = 8;
    pub const fn virtio_irq(slot: usize) -> u32 { 1 + slot as u32 }
    pub const RING_REGION: u64 = 0x30000000;
    pub const RING_REGION_SIZE: u64 = 0x10000;
    pub const RAM: u64 = 0x80000000;
    pub const RAM_SIZE: u64 = 0x80000000;
    pub const DMA_REGION: u64 = 0x100000000;
    pub const DMA_REGION_SIZE: u64 = 0x80000000;
}

/// virtio-mmio v2, as each shim presents it to the guest.
pub mod virtio_mmio {
    pub const MAGIC_VALUE: u32 = 0x74726976;
    pub const VERSION: u32 = 2;
    pub const VENDOR_ID: u32 = 0x44524143;
    pub const CONFIG_BYTES: usize = 64;
    pub const QUEUES_PER_SLOT: usize = 2;
    pub const STATUS_ACKNOWLEDGE: u8 = 1;
    pub const STATUS_DRIVER: u8 = 2;
    pub const STATUS_DRIVER_OK: u8 = 4;
    pub const STATUS_FEATURES_OK: u8 = 8;
    pub const STATUS_FAILED: u8 = 128;
    pub const F_EVENT_IDX: u32 = 29;
    pub const F_VERSION_1: u32 = 32;
    pub const F_ACCESS_PLATFORM: u32 = 33;
    pub const DEVICE_NET: u32 = 1;
    pub const DEVICE_BLK: u32 = 2;
    pub mod reg {
        /// R 0x74726976
        pub const MAGIC_VALUE: usize = 0x0;
        /// R 2
        pub const VERSION: usize = 0x4;
        /// R virtio device type, programmed by the host
        pub const DEVICE_ID: usize = 0x8;
        /// R programmed by the host
        pub const VENDOR_ID: usize = 0xc;
        /// R 32 bits of the host's features, selected by DeviceFeaturesSel
        pub const DEVICE_FEATURES: usize = 0x10;
        /// W
        pub const DEVICE_FEATURES_SEL: usize = 0x14;
        /// W forwarded as a FEATURES record when the driver sets FEATURES_OK
        pub const DRIVER_FEATURES: usize = 0x20;
        /// W
        pub const DRIVER_FEATURES_SEL: usize = 0x24;
        /// W
        pub const QUEUE_SEL: usize = 0x30;
        /// R programmed by the host
        pub const QUEUE_NUM_MAX: usize = 0x34;
        /// W
        pub const QUEUE_NUM: usize = 0x38;
        /// RW a write of 1 forwards a QUEUE record
        pub const QUEUE_READY: usize = 0x44;
        /// W forwarded as a NOTIFY record
        pub const QUEUE_NOTIFY: usize = 0x50;
        /// R set by the host's INTERRUPT command; drives the slot's PLIC line
        pub const INTERRUPT_STATUS: usize = 0x60;
        /// W clears InterruptStatus bits
        pub const INTERRUPT_ACK: usize = 0x64;
        /// RW every write is forwarded as a STATUS record; 0 resets the device
        pub const STATUS: usize = 0x70;
        /// W
        pub const QUEUE_DESC_LOW: usize = 0x80;
        /// W
        pub const QUEUE_DESC_HIGH: usize = 0x84;
        /// W
        pub const QUEUE_DRIVER_LOW: usize = 0x90;
        /// W
        pub const QUEUE_DRIVER_HIGH: usize = 0x94;
        /// W
        pub const QUEUE_DEVICE_LOW: usize = 0xa0;
        /// W
        pub const QUEUE_DEVICE_HIGH: usize = 0xa4;
        /// R 0
        pub const CONFIG_GENERATION: usize = 0xfc;
        /// R device configuration space, programmed by the host (64 bytes)
        pub const CONFIG: usize = 0x100;
    }
}

/// BAR0: the mailbox.
pub mod bar0 {
    pub const SIZE: usize = 0x10000;
    pub const CMD_RING: usize = 0x1000;
    pub const CMD_ENTRIES_LOG2: u32 = 6;
    pub const SLOT_PROG_BASE: usize = 0x4000;
    pub const SLOT_PROG_STRIDE: usize = 0x100;
    pub mod reg {
        /// R 0x44524143, "CARD"
        pub const MAGIC: usize = 0x0;
        /// R contract version
        pub const VERSION: usize = 0x4;
        /// R number of virtio-mmio slots
        pub const SLOTS: usize = 0x8;
        /// R command ring entries, log2
        pub const CMD_ENTRIES_LOG2: usize = 0xc;
        /// W host inbox base (host bus address, 64-byte aligned)
        pub const INBOX_ADDR_LO: usize = 0x10;
        /// W
        pub const INBOX_ADDR_HI: usize = 0x14;
        /// W host inbox entries, log2
        pub const INBOX_ENTRIES_LOG2: usize = 0x18;
        /// W sequence number of the last record the host has consumed
        pub const INBOX_CONSUMED: usize = 0x1c;
        /// W host staging base (host bus address)
        pub const STAGING_ADDR_LO: usize = 0x20;
        /// W
        pub const STAGING_ADDR_HI: usize = 0x24;
        /// W host staging size in bytes
        pub const STAGING_SIZE: usize = 0x28;
        /// W doorbell: sequence number of the last command written
        pub const CMD_PRODUCED: usize = 0x30;
        /// W 1 once the registers above are set; 0 stops the card's writes
        pub const ENABLE: usize = 0x34;
        /// R bit 0 enabled, bit 1 inbox full, bit 2 a command was refused
        pub const STATUS: usize = 0x38;
    }
    /// Per-slot programming registers, relative to SLOT_PROG_BASE + slot * SLOT_PROG_STRIDE.
    pub mod slot_reg {
        /// W 0 leaves the slot empty
        pub const DEVICE_ID: usize = 0x0;
        /// W device features, bits 0-31
        pub const FEATURES_LO: usize = 0x8;
        /// W device features, bits 32-63
        pub const FEATURES_HI: usize = 0xc;
        /// W largest queue size the device accepts
        pub const QUEUE_NUM_MAX: usize = 0x10;
        /// W device configuration space (64 bytes)
        pub const CONFIG: usize = 0x40;
    }
}

/// COPY_DONE status codes.
pub mod copy_status {
    pub const DONE: u16 = 0;
    pub const OUTSIDE_WINDOW: u16 = 1;
    pub const BAD_LENGTH: u16 = 2;
    pub const MISALIGNED: u16 = 3;
}

/// The DMA windows (base, size) a guest range in a copy or a used ring must lie wholly inside.
pub const DMA_WINDOWS: &[(u64, u64)] = &[(0x100000000, 0x80000000), (0x30000000, 0x10000)];

/// Records and commands: 64 bytes, little-endian, sequence number at both ends.
pub mod message {
    pub const SIZE: usize = 64;
    pub const SEQ_OFFSET: usize = 0;
    pub const KIND_OFFSET: usize = 4;
    pub const SLOT_OFFSET: usize = 6;
    pub const TAIL_OFFSET: usize = 60;
}

#[derive(Debug, PartialEq, Eq)]
pub struct Field { pub name: &'static str, pub offset: usize, pub bytes: usize }

#[derive(Debug, PartialEq, Eq)]
pub struct Layout { pub name: &'static str, pub kind: u16, pub fields: &'static [Field] }

/// Card to host, in the host inbox.
pub mod record {
    use super::{Field, Layout};
    /// the guest wrote QueueNotify
    pub const NOTIFY: Layout = Layout { name: "NOTIFY", kind: 1, fields: &[Field { name: "queue", offset: 8, bytes: 2 }] };
    /// the guest wrote Status (0 is a device reset)
    pub const STATUS: Layout = Layout { name: "STATUS", kind: 2, fields: &[Field { name: "status", offset: 8, bytes: 1 }] };
    /// the guest set QueueReady: the queue's size and ring addresses (guest addresses)
    pub const QUEUE: Layout = Layout { name: "QUEUE", kind: 3, fields: &[Field { name: "queue", offset: 8, bytes: 2 }, Field { name: "size", offset: 10, bytes: 2 }, Field { name: "ready", offset: 12, bytes: 1 }, Field { name: "desc", offset: 16, bytes: 8 }, Field { name: "driver", offset: 24, bytes: 8 }, Field { name: "device", offset: 32, bytes: 8 }] };
    /// the guest set FEATURES_OK: the features it accepted
    pub const FEATURES: Layout = Layout { name: "FEATURES", kind: 4, fields: &[Field { name: "features", offset: 8, bytes: 8 }] };
    /// a copy command finished
    pub const COPY_DONE: Layout = Layout { name: "COPY_DONE", kind: 5, fields: &[Field { name: "tag", offset: 8, bytes: 4 }, Field { name: "status", offset: 12, bytes: 2 }] };
    /// bytes the guest sent to the 16550
    pub const CONSOLE_TX: Layout = Layout { name: "CONSOLE_TX", kind: 6, fields: &[Field { name: "count", offset: 8, bytes: 1 }, Field { name: "data", offset: 12, bytes: 48 }] };
    /// commands consumed up to this sequence number
    pub const CMD_ACK: Layout = Layout { name: "CMD_ACK", kind: 7, fields: &[Field { name: "cmd_seq", offset: 8, bytes: 4 }] };
    pub const ALL: &[&Layout] = &[&NOTIFY, &STATUS, &QUEUE, &FEATURES, &COPY_DONE, &CONSOLE_TX, &CMD_ACK];
}

/// Host to card, in the BAR0 command ring.
pub mod command {
    use super::{Field, Layout};
    /// copy a guest range (checked against the window) into host staging
    pub const COPY_TO_HOST: Layout = Layout { name: "COPY_TO_HOST", kind: 1, fields: &[Field { name: "tag", offset: 8, bytes: 4 }, Field { name: "len", offset: 12, bytes: 4 }, Field { name: "guest", offset: 16, bytes: 8 }, Field { name: "staging", offset: 24, bytes: 4 }] };
    /// copy from host staging into a guest range (checked against the window)
    pub const COPY_FROM_HOST: Layout = Layout { name: "COPY_FROM_HOST", kind: 2, fields: &[Field { name: "tag", offset: 8, bytes: 4 }, Field { name: "len", offset: 12, bytes: 4 }, Field { name: "guest", offset: 16, bytes: 8 }, Field { name: "staging", offset: 24, bytes: 4 }] };
    /// append {id, len} to a ready queue's used ring, then publish the new used index; refused if the ring lies outside the windows
    pub const USED_PUSH: Layout = Layout { name: "USED_PUSH", kind: 3, fields: &[Field { name: "queue", offset: 8, bytes: 2 }, Field { name: "id", offset: 12, bytes: 4 }, Field { name: "len", offset: 16, bytes: 4 }] };
    /// set InterruptStatus bits (1 used buffer, 2 configuration change)
    pub const INTERRUPT: Layout = Layout { name: "INTERRUPT", kind: 4, fields: &[Field { name: "bits", offset: 8, bytes: 4 }] };
    /// bytes for the 16550's receive FIFO
    pub const CONSOLE_RX: Layout = Layout { name: "CONSOLE_RX", kind: 5, fields: &[Field { name: "count", offset: 8, bytes: 1 }, Field { name: "data", offset: 12, bytes: 48 }] };
    pub const ALL: &[&Layout] = &[&COPY_TO_HOST, &COPY_FROM_HOST, &USED_PUSH, &INTERRUPT, &CONSOLE_RX];
}
