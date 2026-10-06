# Datasheets

Reference documents for the [YPCB-00338-1P1](../board/ypcb-00338-1p1/README.md). The PDFs are
git-ignored; `../fetch-docs.sh` downloads the AMD and TI ones. Each row says where to look, so you
don't have to read 2,600 pages.

| File | Pages | What it is | Go here for |
|---|---|---|---|
| `ug470-7series-config.pdf` | 174 | **Configuration** | modes and pins (tables 2-1–2-9; Master BPI = M 010; CFGBVS 2-5); startup sequence (5-10–5-13); bitstream options (5-15); eFUSE (5-16–5-18, FUSE_CNTL = table 5-17: **read-only for us**); packets (5-20–5-23); FAR (5-24); CMD codes (5-25); CTL0 (5-26/27); **STAT (5-28/29)**; **COR0 (5-30/31)**; COR1, WBSTAR, TIMER, BOOTSTS (5-32–5-39); JTAG instructions (table 10-2) and IR capture (10-3) |
| `ug471-7series-selectio.pdf` | 188 | I/O standards, IOB, IOLOGIC | LVCMOS18 drive/slew, pulls, DCI, IDELAY/ODELAY, ISERDES/OSERDES (DDR3 PHY) |
| `ug472-7series-clocking.pdf` | 114 | Clocking | BUFG/BUFH/BUFR, CMT, **MMCM/PLL** (next thing to prove) |
| `ug473-7series-memory.pdf` | 88 | Block RAM and FIFO | BRAM primitives and INIT |
| `ug474-7series-clb.pdf` | 81 | CLB | LUTs, carry chain (CARRY4: see the -nocarry note in tools/openxc7.md), SRLs |
| `ug475-7series-pkg-pinout.pdf` | 352 | Packages and pinout | FFG1156 bank layout, pin types, dual-purpose config pins |
| `xc7k480tffg1156-pkg.txt` | – | Pin table for our exact package | pin → name, bank, I/O type (all banks HR) |
| `ug476-7series-gtx.pdf` | 506 | GTX transceivers | PCIe lanes, QPLL/CPLL, ref clocks |
| `ug477-7series-pcie.pdf` | 398 | PCIe hard block (PCIE_2_1) | open-toolchain PCIe |
| `ug480-7series-xadc.pdf` | 82 | XADC / system monitor | temperature and supply registers, JTAG DRP access |
| `ug483-7series-pcb.pdf` | 76 | PCB design | power rails and decoupling (no board schematic exists) |
| `ds180-7series-overview.pdf` | 19 | Family overview | XC7K480T resources |
| `ds182-kintex7-dc-ac.pdf` | 73 | Kintex-7 DC/AC data sheet | **VCCINT 0.97–1.03 V** (we read 0.959), I/O levels, temperature grades |
| `xapp1239-7series-encryption.pdf` | 17 | Bitstream encryption | what the AES fuse bits would lock; we never burn them |
| `mt28gu512-bpi-nor-flash.pdf` | 122 | Micron MT28GU512 StrataFlash (U4) | read-array, status, ID commands; blocks; added by hand, source URL not recorded |
| `mt41k256m8-ddr3.pdf` | 221 | Micron 2 Gb DDR3 (×8), 9 per channel | timing, MR registers; added by hand, source URL not recorded |
| `lm73-temp-sensor.pdf` | 31 | TI LM73 (U23, I²C 0x4A) | register map |

Not downloadable by script: **UG586** (7-series MIG/DDR3) at
<https://docs.amd.com/v/u/en-US/ug586_7Series_MIS>; save it here as `ug586-7series-mig.pdf`.

Other references worth knowing: the [prjxray docs](https://f4pga.readthedocs.io/projects/prjxray/en/latest/)
(bitstream format, FASM, databases) and the BSDL file for boundary scan
(`/opt/Xilinx/2026.1/Vivado/ids_lite/ISE/kintex7/data/xc7k480t_ffg1156.bsd` on dino).
