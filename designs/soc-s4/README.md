# soc-s4: the host link on real PCIe (SoC roadmap S4)

The card's side of [the host-link contract](../../soc/gen/CONTRACT.md) on the PCIE_2_1 hard block, with
64 KiB of block RAM standing in for guest memory (DDR3 comes with S5). Both DMA windows alias onto the
RAM by their low 16 bits. There is no guest CPU yet: the host drives everything through the mailbox.

- [soc_s4.v](soc_s4.v): the top. The hard block from [pcie-x1](../pcie-x1/) (regymm/pcie_7x, CERN-OHL-P,
  copied in by [prebuild.py](prebuild.py)), BAR0 64 KiB, device `10ee:0484`, Bus Master Enable from
  `cfg_command[2]`, and a JTAG USER1 debug register.
- `CardS4` ([soc/…/CardS4.scala](../../soc/src/main/scala/card/CardS4.scala)), generated at build time
  with sbt: PcieLink, the guest RAM, and registers on both sides of the hard block's stream (nextpnr
  does not time the hard block's pins).

Gen1 by default (62.5 MHz user clock; place and route reaches about 90 MHz). `SOC_S4_GEN2=1 FREQ=125`
builds Gen2, which does not meet timing yet.

## Running it

```text
FREQ=62.5 fpga build designs/soc-s4 soc_s4        # on buzz; needs sbt and a JDK
fpga load designs/soc-s4/build/soc_s4.bit
ssh dino sudo systemctl reboot                     # warm reboot: the BIOS enumerates the new endpoint
# on dino:
sudo modprobe vfio-pci
echo vfio-pci | sudo tee /sys/bus/pci/devices/0000:04:00.0/driver_override
echo 0000:04:00.0 | sudo tee /sys/bus/pci/drivers_probe
sudo ./s4 0000:04:00.0 [iterations]                # cardd/examples/s4.rs, built on buzz (cargo build --release --example s4)
```

`S4_DIAG=1` instead fills guest memory with fixed and random patterns and reads it back repeatedly,
counting bits that flip in each direction: the test that found the untimed TX path.

Result on 2026-10-06: PASS. BAR0 read 1.6 µs, a 4-byte copy's round trip 7.9 µs to host and 9.9 µs from
host, 64 KiB copies at 163 MB/s to host and 30 MB/s from host.
