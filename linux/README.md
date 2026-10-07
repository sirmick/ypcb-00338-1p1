# linux: what the card's guest runs

Scripts and sources for the guest SoC's software ([SOC-ROADMAP.md](../SOC-ROADMAP.md), S5 onwards). Every
build runs on buzz (riscv64-linux-gnu-gcc, dtc); sources live outside the repository (`~/fpga/src`).

| File | What |
|---|---|
| [card-s5.dts](card-s5.dts) | the S5 device tree: one RV64IMAFDC hart, QEMU virt's CLINT and PLIC, 2 GiB at `0x8000_0000`, the console from [contract-console.dtsi](../soc/gen/contract-console.dtsi) |
| [build-opensbi.sh](build-opensbi.sh) | OpenSBI v1.6, generic platform, the device tree built in: `fw_jump.bin` (to Linux at `0x8020_0000`) and `fw_payload.bin` (its test payload, for simulation) |
| [build-initramfs.sh](build-initramfs.sh), [init](init), [memtest.c](memtest.c) | a BusyBox initramfs (Debian's static riscv64 busybox) with a main-memory test |
| [build-kernel.sh](build-kernel.sh) | stock Linux 6.12, riscv `defconfig`, initramfs built in |

```text
linux/build-opensbi.sh ~/fpga/build/s5
linux/build-initramfs.sh ~/fpga/build/s5
linux/build-kernel.sh ~/fpga/build/s5 ~/fpga/build/s5/initramfs
```

The core starts at `0x8000_0000` with nothing before it, so OpenSBI carries the device tree and the
kernel carries the initramfs. cardd loads both images through the boot window while the cores are held
in reset, then releases them: `sudo ./boot 0000:04:00.0 fw_jump.bin Image` on dino
([cardd/examples/boot.rs](../cardd/examples/boot.rs)), with the console on its stdin and stdout.

In simulation (`cd soc && sbt "testOnly card.SocCoreSpec"`, after `soc/vexii-cluster.sh tmp/vexii` and
`linux/build-opensbi.sh ../soc/tmp/sw`) OpenSBI reaches its test payload in 12.8 million cycles.
