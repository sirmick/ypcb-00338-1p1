//! `cardd`: the host side of the YPCB-00338-1P1 card's virtio devices.
//!
//! The card writes 64-byte records into an inbox in host memory, and the host writes 64-byte
//! commands into the card's BAR0 command ring: each side's inbox lives in its own memory, and only
//! posted writes cross the link. [`contract`] is generated from the RTL's contract
//! (`soc/src/main/scala/card/contract/Contract.scala`); [`message`] encodes and checks messages.
#![forbid(unsafe_code)]

pub mod contract;
pub mod message;
