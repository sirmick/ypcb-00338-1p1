# Our lab, as an example

Two machines: **dino** holds the card and the JTAG cable, **buzz** builds. Nobody sits at dino;
everything is done over ssh, JTAG and its BMC. Any setup works; this one explains the defaults in
[`fpga`](../fpga) (`FPGA_HOST=dino`, the paths below) and in [`bmc`](../bmc).

## dino: the card host

| | |
|---|---|
| Board | ASRock EP2C602 (dual-socket Intel C602), with a BMC (IPMI 2.0) |
| CPU / RAM | Xeon E5-2697 v2, 48 threads / 125 GB |
| OS | Ubuntu 24.04 |
| FPGA card | in a PCIe x8 slot behind root port `00:03.0`; the factory image enumerates as `04:00.0` |
| JTAG cable | Digilent JTAG-HS3 on USB (`lsusb -d 0403:6014`) |

| Path | What |
|---|---|
| `~/hw/` | the copy of this repository that `fpga` syncs and runs (do not edit there) |
| `~/opt/openxc7/` | the openXC7 toolchain (`source ~/opt/openxc7/export.sh`) |
| `~/fpga/chipdb/` | the XC7K480T chipdb |
| `~/fpga/flash-backup/` | the card's factory flash image (read-only) |
| `/opt/Xilinx/2026.1/Vivado` | xsdb, hw_server |
| `~/opt/Xilinx/2026.1/Vivado_Lab` | Vivado Lab Edition (flash readback, system monitor) |
| `/opt/oss-cad-suite` | openFPGALoader, iverilog, verilator |

### Power and the BMC

- **A plain reboot does not remove the card's power**: the FPGA keeps its configuration through a
  warm reboot. A cold start needs the chassis powered off. We do it cleanly: `sudo systemctl poweroff`
  on dino, wait until `bmc status` says off, wait a minute so the DRAM forgets, then `bmc on`.
  dino answers ssh again about two minutes later (done 2026-10-06; the card then boots its factory
  image from flash).
- `bmc` (run from buzz) talks IPMI over the LAN, so it works when dino is hung or off. It reads the
  BMC password from `~/.config/dino-bmc.pass` (mode 600, never in the repo); `BMC_HOST` and
  `BMC_USER` select the BMC. The BMC's own shared NIC is not reachable from dino itself, only from
  the LAN; in-band, dino reaches it with `sudo ipmitool` through `/dev/ipmi0`.
- With the power restore policy at `always-off`, a mains blip leaves dino off until `bmc on`.
- Secure your BMC: change its factory password and keep it off untrusted networks.

### Quirks

- **PCIe hot-add does not work for MMIO on this host.** After loading a PCIe design, a remove and
  rescan enumerates the device and config space works, but memory reads return all-ones. Load the
  design, then warm-reboot dino (the card stays powered and configured): the BIOS enumerates it and
  MMIO works.
- The kernel keeps the factory image's PCIe device registered after other designs are loaded and
  logs AER completion timeouts when something touches it; `echo 1 | sudo tee
  /sys/bus/pci/devices/0000:04:00.0/remove` stops it.
- Never `pkill -f` a pattern that also appears in your own command line: it kills its own shell.

## buzz: the build host

| | |
|---|---|
| CPU / RAM | AMD Ryzen 9 9955HX, 32 threads / 29 GB |
| OS | Ubuntu 24.04 (the same release as dino, so the toolchain binaries are shared) |

`fpga build` runs here when `~/opt/openxc7` exists and copies only the build directory to dino.
yosys is single-threaded and nextpnr's router uses about 1.5 cores, so a fast core beats many slow
ones: designs/blinky takes about 47 s here and 2–3 min on dino. The toolchain is an rsync of dino's
(the same home path, so `export.sh` works unchanged):

```text
rsync -a dino:opt/openxc7/ ~/opt/openxc7/
rsync -a dino:fpga/chipdb/xc7k480tffg1156.bin ~/fpga/chipdb/
```
