<!-- Generated from soc/src/main/scala/card/contract/Contract.scala by Generate.scala. Do not edit. -->
# The host-link contract, version 1

Everything the card's RTL and the host backend (`cardd`) agree on. Source:
[Contract.scala](../src/main/scala/card/contract/Contract.scala).

## Messages

Records (card to host, into the host inbox) and commands (host to card, into the BAR0 command
ring) are 64 bytes, little-endian. Bytes 0-3 hold the sequence number (1, 2, 3, ... per
stream, never 0), 4-5 the kind, 6-7 the slot, and 60-63 the sequence number again: a message whose
two copies differ is torn, and one whose number is not the next expected is stale.

## Records

### NOTIFY (kind 1)

the guest wrote QueueNotify.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `queue` | 2 |  |

### STATUS (kind 2)

the guest wrote Status (0 is a device reset).

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `status` | 1 |  |

### QUEUE (kind 3)

the guest set QueueReady: the queue's size and ring addresses (guest addresses).

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `queue` | 2 |  |
| 10 | `size` | 2 |  |
| 12 | `ready` | 1 |  |
| 16 | `desc` | 8 |  |
| 24 | `driver` | 8 | available ring |
| 32 | `device` | 8 | used ring |

### FEATURES (kind 4)

the guest set FEATURES_OK: the features it accepted.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `features` | 8 |  |

### COPY_DONE (kind 5)

a copy command finished.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `tag` | 4 |  |
| 12 | `status` | 2 | 0 done; 1 outside the DMA windows; 2 bad length or outside staging; 3 misaligned |

### CONSOLE_TX (kind 6)

bytes the guest sent to the 16550.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `count` | 1 |  |
| 12 | `data` | 48 |  |

### CMD_ACK (kind 7)

commands consumed up to this sequence number.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `cmd_seq` | 4 |  |

## Commands

### COPY_TO_HOST (kind 1)

copy a guest range (checked against the window) into host staging.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `tag` | 4 |  |
| 12 | `len` | 4 |  |
| 16 | `guest` | 8 |  |
| 24 | `staging` | 4 | offset in host staging |

### COPY_FROM_HOST (kind 2)

copy from host staging into a guest range (checked against the window).

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `tag` | 4 |  |
| 12 | `len` | 4 |  |
| 16 | `guest` | 8 |  |
| 24 | `staging` | 4 |  |

### USED_PUSH (kind 3)

append {id, len} to a ready queue's used ring, then publish the new used index; refused if the ring lies outside the windows.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `queue` | 2 |  |
| 12 | `id` | 4 |  |
| 16 | `len` | 4 |  |

### INTERRUPT (kind 4)

set InterruptStatus bits (1 used buffer, 2 configuration change).

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `bits` | 4 |  |

### CONSOLE_RX (kind 5)

bytes for the 16550's receive FIFO.

| Offset | Field | Bytes | |
|---|---|---|---|
| 8 | `count` | 1 |  |
| 12 | `data` | 48 |  |

## BAR0 (64 KiB)

| Offset | Name | Access | |
|---|---|---|---|
| `0x0` | `MAGIC` | R | 0x44524143, "CARD" |
| `0x4` | `VERSION` | R | contract version |
| `0x8` | `SLOTS` | R | number of virtio-mmio slots |
| `0xc` | `CMD_ENTRIES_LOG2` | R | command ring entries, log2 |
| `0x10` | `INBOX_ADDR_LO` | W | host inbox base (host bus address, 64-byte aligned) |
| `0x14` | `INBOX_ADDR_HI` | W |  |
| `0x18` | `INBOX_ENTRIES_LOG2` | W | host inbox entries, log2 |
| `0x1c` | `INBOX_CONSUMED` | W | sequence number of the last record the host has consumed |
| `0x20` | `STAGING_ADDR_LO` | W | host staging base (host bus address) |
| `0x24` | `STAGING_ADDR_HI` | W |  |
| `0x28` | `STAGING_SIZE` | W | host staging size in bytes |
| `0x30` | `CMD_PRODUCED` | W | doorbell: sequence number of the last command written |
| `0x34` | `ENABLE` | W | 1 once the registers above are set; 0 stops the card's writes |
| `0x38` | `STATUS` | R | bit 0 enabled, bit 1 inbox full, bit 2 a command was refused |

The command ring is at `0x1000`: 64 entries of 64 bytes.
Slot programming registers are at `0x4000 + slot * 0x100`:

| Offset | Name | Access | |
|---|---|---|---|
| `0x0` | `DEVICE_ID` | W | 0 leaves the slot empty |
| `0x8` | `FEATURES_LO` | W | device features, bits 0-31 |
| `0xc` | `FEATURES_HI` | W | device features, bits 32-63 |
| `0x10` | `QUEUE_NUM_MAX` | W | largest queue size the device accepts |
| `0x40` | `CONFIG` | W | device configuration space (64 bytes) |

Every other BAR0 offset reads as 0 and ignores writes.

## virtio-mmio (what the guest sees at each slot)

| Offset | Name | Access | |
|---|---|---|---|
| `0x0` | `MagicValue` | R | 0x74726976 |
| `0x4` | `Version` | R | 2 |
| `0x8` | `DeviceID` | R | virtio device type, programmed by the host |
| `0xc` | `VendorID` | R | programmed by the host |
| `0x10` | `DeviceFeatures` | R | 32 bits of the host's features, selected by DeviceFeaturesSel |
| `0x14` | `DeviceFeaturesSel` | W |  |
| `0x20` | `DriverFeatures` | W | forwarded as a FEATURES record when the driver sets FEATURES_OK |
| `0x24` | `DriverFeaturesSel` | W |  |
| `0x30` | `QueueSel` | W |  |
| `0x34` | `QueueNumMax` | R | programmed by the host |
| `0x38` | `QueueNum` | W |  |
| `0x44` | `QueueReady` | RW | a write of 1 forwards a QUEUE record |
| `0x50` | `QueueNotify` | W | forwarded as a NOTIFY record |
| `0x60` | `InterruptStatus` | R | set by the host's INTERRUPT command; drives the slot's PLIC line |
| `0x64` | `InterruptACK` | W | clears InterruptStatus bits |
| `0x70` | `Status` | RW | every write is forwarded as a STATUS record; 0 resets the device |
| `0x80` | `QueueDescLow` | W |  |
| `0x84` | `QueueDescHigh` | W |  |
| `0x90` | `QueueDriverLow` | W |  |
| `0x94` | `QueueDriverHigh` | W |  |
| `0xa0` | `QueueDeviceLow` | W |  |
| `0xa4` | `QueueDeviceHigh` | W |  |
| `0xfc` | `ConfigGeneration` | R | 0 |
| `0x100` | `Config` | R | device configuration space, programmed by the host (64 bytes) |

## The guest's address map

| | Base | Size |
|---|---|---|
| CLINT | `0x2000000` | `0x10000` |
| PLIC | `0xc000000` | `0x600000` |
| 16550 UART (IRQ 10) | `0x10000000` | `0x100` |
| virtio-mmio slots 0-7 (IRQ 1-8) | `0x10001000` | `0x1000` each |
| Ring region (block RAM, uncached) | `0x30000000` | `0x10000` |
| RAM (DDR3 channel A) | `0x80000000` | `0x80000000` |
| DMA region (DDR3 channel B, uncached) | `0x100000000` | `0x80000000` |
