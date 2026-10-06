# JTAG: cables, tools and remote checks

Everything we do to the card goes over one JTAG cable, from a box nobody can look at. This page says
which tool to use for what, and how to see the hardware's state without eyes on it. The everyday
commands are wrapped by [`fpga`](../README.md#the-fpga-command).

## The cable

- **Digilent JTAG-HS3** (FT232H, USB `0403:6014`, serial `210299BFE266`) on the card's J1.
  Udev rules are installed on dino (`/etc/udev/rules.d/52-xilinx-*`). The chain is one device:
  XC7K480T, IR length 6.
- Only one program can own the cable at a time. xsdb and Vivado Lab go through `hw_server`, which
  keeps running after they exit; kill it before using openFPGALoader (`pkill -x hw_server; pkill -x cs_server`).
  `fpga` does this.
- A second card wants a second cable rather than a daisy chain: the cards have no TDO pass-through, so
  chaining needs a hand-made Y-adapter.

## Which tool for what

| Job | Use | Why |
|---|---|---|
| Load a bitstream, check DONE | **xsdb** (`fpga load`) | Digilent's own driver; its `fpga -file` reports failure honestly |
| Non-invasive status | **xsdb** IR capture (`fpga status`) | bit 5 = DONE, bit 4 = INIT_COMPLETE; reading it changes nothing |
| Raw JTAG (fuses, USER1, boundary scan, JPROGRAM) | **xsdb** `jtag sequence` | no licence; `jtag lock` / `irshift` / `drshift -capture` |
| Detect the chain | openFPGALoader `--detect` | quick |
| Flash readback | **Vivado Lab** `readback_hw_cfgmem` | 64 MiB in 60 s; openFPGALoader manages 2.6 KB/s |
| Temperature and rails | **Vivado Lab** `get_hw_sysmons` | openFPGALoader `--read-xadc` is Artix-only |
| STAT register | avoid openFPGALoader `--read-register STAT` | it read `0x0` for a configured chip; use xsdb |

Installed: xsdb in `/opt/Xilinx/2026.1/Vivado` (the full Vivado will not start unlicensed, but xsdb
and hw_server do); Vivado Lab 2026.1 in `~/opt/Xilinx` (installed without sudo:
`xsetup -b Install -a XilinxEULA,3rdPartyEULA -c <config>`, product "Lab Edition"); openFPGALoader in
`/opt/oss-cad-suite`.

### xsdb notes

- Captured hex comes back **byte-reversed** (least significant byte first): IDCODE `0x23751093`
  reads as `93107523`. `drshift -hex` needs the TDI string at full length (`string repeat 0 n/4`).
- `jtag claim` does not exist in this version; use `jtag lock` / `jtag unlock`.
- `connect` starts an hw_server if none is running.

## Seeing the hardware remotely

- **Configuration state**: the IR capture (`IR 0x3f`, capture): `0x35` = configured, `0x11` = INIT
  done, not configured.
- **Boundary scan (SAMPLE, IR `000001`)**: captures every pin's output-enable, output value and pad
  value while the design runs. That is how we watch the LEDs blink without a camera (`fpga pins`) and
  read the DONE, INIT_B and AC24 pads (`fpga status`). The cell map is in the BSDL:
  `/opt/Xilinx/2026.1/Vivado/ids_lite/ISE/kintex7/data/xc7k480t_ffg1156.bsd` (1395 cells; the card
  README lists the cells we use). Each I/O has three cells: control (1 = tri-stated), output, input.
  A pattern of every pin tri-stated means the FPGA is not configured.
- **USER registers (BSCANE2)**: a design can expose registers on USER1–4 (IR `0x02`, `0x03`, `0x22`,
  `0x23`). [designs/hello](../designs/hello/) puts a magic word and a counter on USER1; `fpga user1`
  reads it twice and prints the clock rate.
- **XADC**: temperature and supply rails over JTAG, even when the FPGA is unconfigured.
- **JPROGRAM**: clears the FPGA. In master-BPI mode it then boots the factory image from flash in
  about 3.5 s, a quick way to get a known state.

## Instruction codes used (7-series, IR length 6, UG470 table 10-2)

| Instruction | Code | Used for |
|---|---|---|
| SAMPLE | `000001` | boundary scan |
| USER1 / USER2 | `000010` / `000011` | BSCANE2 registers (USER2 holds the MicroBlaze MDM) |
| IDCODE | `001001` | |
| JPROGRAM | `001011` | clear and reconfigure |
| FUSE_KEY / FUSE_DNA / FUSE_USER / FUSE_CNTL | `110001` / `110010` / `110011` / `110100` | **read only**: shift zeros |
| BYPASS | `111111` | cable bit-error test |

**Never** issue fuse-programming sequences or tool modes that burn keys or security bits, on any FPGA.
