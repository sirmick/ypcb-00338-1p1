# pcie-x1: PCIe Gen2 x1 endpoint (gate 8)

Vendored from [regymm/pcie_7x](https://github.com/regymm/pcie_7x) at `cd1c33f` (CERN-OHL-P,
[LICENSE.pcie_7x](LICENSE.pcie_7x)): the PCIE_2_1 hard block and one GTX lane, no Vivado IP. Only the
XDC is ours (I/O standards corrected to the 1.8 V banks); AC24 comes from board.fasm as for every build.
BAR0 is 4 KB onto a 256-word register file initialised to `0x12345678`; device ID `0x0480`.

Build: `fpga build designs/pcie-x1 pcie_7x_top_aximm_ypcb_480t`. Test: see `check.sh` (run on dino).
