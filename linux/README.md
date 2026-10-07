# linux: what the card's guest runs

Scripts and sources for the guest SoC's software ([SOC-ROADMAP.md](../SOC-ROADMAP.md), S5 onwards). Every
build runs on buzz (riscv64-linux-gnu-gcc, dtc); sources live outside the repository (`~/fpga/src`).

| File | What |
|---|---|
| [card-dts.py](card-dts.py) | the device tree for N harts (S5: 1, S7: 4): RV64IMAFDC, Sv39, QEMU virt's CLINT and PLIC with machine and supervisor contexts per hart, 2 GiB at `0x8000_0000`, the console from [contract-console.dtsi](../soc/gen/contract-console.dtsi) |
| [build-opensbi.sh](build-opensbi.sh) | OpenSBI v1.6, generic platform, the device tree built in: `fw_jump.bin` (to Linux at `0x8020_0000`) and `fw_payload.bin` (its test payload, for simulation) |
| [build-initramfs.sh](build-initramfs.sh), [init](init), [memtest.c](memtest.c) | a BusyBox initramfs (Debian's static riscv64 busybox) with a main-memory test |
| [build-kernel.sh](build-kernel.sh) | stock Linux 6.12, riscv `defconfig`, initramfs built in |

```text
linux/build-opensbi.sh ~/fpga/build/s5              # CARD_HARTS=4 for S7
linux/build-initramfs.sh ~/fpga/build/s5
linux/build-kernel.sh ~/fpga/build/s5 ~/fpga/build/s5/initramfs
```

The cores start at `0x8000_0000` with nothing before them, so OpenSBI carries the device tree and the
kernel carries the initramfs. cardd loads both images through the boot window while the cores are held
in reset, then releases them and serves the console on a socket: [`card up`](../card), then
`card console` ([cardd/src/bin/cardd.rs](../cardd/src/bin/cardd.rs)).

In simulation (`cd soc && sbt "testOnly card.SocCoreSpec"`, after `soc/vexii-cluster.sh tmp/vexii` and
`linux/build-opensbi.sh ../soc/tmp/sw`) OpenSBI reaches its test payload in 12.8 million cycles.
