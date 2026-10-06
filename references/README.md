# References: other people's work on this card

Everything public we found about the YPCB-00338-1P1 (searched 2026-10-05, English and Chinese). Local
copies are in `third-party/` (git-ignored: they are other people's content; this index is the tracked
part). Re-fetch from the URLs if the copies are missing.

**There is no public schematic.** The pin map was reverse-engineered by TiferKing (JTAG-driven GPIO
probing) and ne5532 (desoldering the DDR3). Card identity: Baidu product family `SCH-001-003` (also
"DU-PCB-001-003", the "Baidu 2015 board"), built by Inspur (ours, YPCB) and Celestica (YZCA sibling,
"fully identical in connections" per the reseller).

## Most useful

| Source | What it gives | Local copy |
|---|---|---|
| [TiferKing/ypcb_00338_1p1_hack](https://github.com/TiferKing/ypcb_00338_1p1_hack) (MIT) + [blog](https://www.tiferking.cn/index.php/2024/12/19/650/) | board files, XDC, pinout spreadsheet, MIG settings, MicroBlaze systest; SW1→INIT_B, SW2→GPIO | `../board/ypcb-00338-1p1/` (board files), `tiferking_blog.html`, `mig_*.prj`, `YPCB-00338-1P1-*.jpg/png`, `jtag_hs3_pinout.png`; `YPCB_00338_1P1_software.zip` (examples/ in the repo), unpacked in `ypcb_software/`: a Vitis MicroBlaze project with a dual-channel MIG at DDR3-1066 (4:1, 133.33 MHz UI clock, ECC on, both 2 GiB channels), XDMA, BPI flash, I²C; its `top_wrapper.bit` decoded to `ypcb_software/mig2ch.fasm` by bit2fasm |
| [TiferKing#3](https://github.com/TiferKing/ypcb_00338_1p1_hack/issues/3) | AC24 identified as the board reset (Maccraft123), confirmed by zollij; flash dumps of both variants | – |
| [zollij's gist](https://gist.github.com/zollij/bf49d29b0ab79d38346dda2a0e32b5b9) | full AC24 investigation, openXC7 status on this part, CARRY4 / SRL bugs, Celestica variant facts | `zollij_ypcb-ac24-writeup.md` |
| [litex-boards platform](https://github.com/litex-hub/litex-boards/blob/master/litex_boards/platforms/ypcb_00338_1p1.py) / [target](https://github.com/litex-hub/litex-boards/blob/master/litex_boards/targets/ypcb_00338_1p1.py) | the most complete pin list (DDR3 both channels, PCIe x8, flash) | `litex-hub_*` |
| [FeSens/openTPU docs/board.md](https://github.com/FeSens/openTPU/blob/main/docs/board.md), [flash.md](https://github.com/FeSens/openTPU/blob/main/docs/flash.md), [observability.md](https://github.com/FeSens/openTPU/blob/main/docs/observability.md) | a large design running on the card: both DDR3 channels, PCIe Gen2 x8, flash programming, I²C scan (LM73 at 0x4A) | `FeSens_openTPU_*` |
| [FeSens/bonetto-soc](https://github.com/FeSens/bonetto-soc) | openXC7 DDR3 validation log | `FeSens_bonetto-soc_*` |
| [FeSens/inspur-adventures](https://github.com/FeSens/inspur-adventures) | openXC7 bring-up notes ("skill" file) | `FeSens_inspur-adventures_*` |
| [alh-Imago bring-up findings](https://github.com/alh-Imago/Imago-Unicell/blob/main/hardware/YPCB_00338_bringup_findings.md) | YZCA-00338-104 board: mode pins, JTAG | `alh-Imago_*` |
| [omasanori/sch-001-003-hack](https://github.com/omasanori/sch-001-003-hack) | the Baidu SCH-001-003 identity | `omasanori_*` |

## Designs and constraints

| Source | Local copy |
|---|---|
| [openXC7 demo-projects blinky-ypcb003381p1](https://github.com/openXC7/demo-projects/tree/main/blinky-ypcb003381p1) (no AC24 handling: see [UPSTREAM](../UPSTREAM.md)) | `openXC7_demo-projects_*` |
| [regymm/pcie_7x](https://github.com/regymm/pcie_7x) `pcie_7x_ypcb_k480t.xdc` (open-toolchain PCIe) | `regymm_*` |
| [UberDDR3 ypcb_00338_1p1 example](https://github.com/AngeloJacobo/UberDDR3/tree/main/example_demo/ypcb_00338_1p1) (calibration under openXC7: issue #44) | `AngeloJacobo_*` |
| [openFPGALoader bpiOverJtag constraints](https://github.com/trabucayre/openFPGALoader/blob/master/spiOverJtag/constr_xc7k_ffg1156.xdc), [boards.yml](https://github.com/trabucayre/openFPGALoader/blob/master/doc/boards.yml) | `trabucayre_*` |
| [ruidongwu's XDMA x8 project](https://www.cnblogs.com/ruidongwu/p/18564807) (PERST inverter note) | `ruidongwu_*` |

## Teardowns and listings

| Source | Notes |
|---|---|
| [ne5532 on cnblogs](https://www.cnblogs.com/ne5532/articles/18534334) | reference designators U1–U26, Y1–Y3 (local: `ne5532_cnblogs.html`) |
| [fpga.com.ua listing](https://fpga.com.ua/index.php?product_id=329&route=product%2Fproduct) | "there is no schematic"; recommends `UNUSEDPIN PULLNONE` (the AC24 workaround) |
| [controlpaths article](https://www.controlpaths.com/2025/05/18/kintex7-accelerator/) | Vivado block design walkthrough; no new pin data |
| [openXC7/nextpnr#20](https://github.com/openXC7/nextpnr/issues/20) | DDR3 flow gaps on the XC7K480T |

## Not checked (dead ends or skipped)

- ne5532's reverse-engineered pin pack on Baidu Pan (`pan.baidu.com/s/1xmLexNwvflCn5LhYS9_vhQ`, code 8as6).
- Original flash dumps on [LukeVassallo's share](https://cloud.lukevassallo.com/index.php/s/N5LsR6RKdfmsPy3) (binaries, not downloaded).
- X/Twitter posts (HTTP 402), CSDN (HTTP 521), cnblogs comment threads (empty).
