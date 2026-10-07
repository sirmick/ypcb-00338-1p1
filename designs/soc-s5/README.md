# soc-s5: Linux on the card (SoC roadmap S5)

One VexiiRiscv core (RV64IMAFDC, Sv39) at 50 MHz with 2 GiB of DDR3 (channel A) as main memory, QEMU
virt's CLINT and PLIC, and a 16550 whose wire is the PCIe host link. The host (`cardd`) loads OpenSBI and
Linux into main memory through the boot window while the core is held in reset, releases it, and serves
the console on a Unix socket.

- [soc_s5.v](soc_s5.v): the top. The PCIe hard block (as [soc-s4](../soc-s4/)), UberDDR3 for channel A
  with 8 byte lanes (as [uberddr3](../uberddr3/); the SoC's 50 MHz comes from the DDR3 PLL's sixth
  output), `CardS5`, and a JTAG debug register ([check.py](check.py)). Device `10ee:0485`.
- `CardS5` and `SocCore` ([soc/](../../soc/src/main/scala/card/)), generated at build time; the cluster
  from [soc/vexii-cluster.sh](../../soc/vexii-cluster.sh) (`SOC_S5_CORES`, default 1).
- [prebuild.py](prebuild.py) brings it together; it needs sbt, a VexiiRiscv checkout and UberDDR3 at
  79d8fd3 (GPL-3.0, copied at build time, never kept here).

```text
FREQ=50 fpga build designs/soc-s5 soc_s5     # on buzz, ~45 min (place and route ~42)
card up                                      # loads it if needed (warm-rebooting dino), boots the guest
card console                                 # Ctrl-] detaches
```

Result on 2026-10-06: DDR3 calibrated and self-tested on the first load; Linux 6.12 to a bash shell in
14 s; memtest over 1.5 GiB passes; main memory 68 MB/s (line reads).
