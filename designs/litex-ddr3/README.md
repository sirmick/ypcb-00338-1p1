# litex-ddr3: LiteX SoC with LiteDRAM on DDR3 channel 0 (gate 9)

The litex-boards `ypcb_00338_1p1` target (VexRiscv, LiteDRAM with the A7 DDR PHY, JTAG UART), generated
on dino, built with our openXC7 flow. There is no Verilog of ours here: `prebuild.py` copies LiteX's
output into the build dir.

1. On dino: `cd ~/fpga/litex-ypcb && source ~/venv/litex/bin/activate &&
   python -m litex_boards.targets.ypcb_00338_1p1 --build --no-compile-gateware` (the openxc7 LiteX
   backend fails with `convert() got an unexpected keyword argument 'nowidelut'`; Verilog from the
   default backend is fine).
2. `rsync -a dino:fpga/litex-ypcb/ ~/fpga/litex-ypcb/`, then `fpga build designs/litex-ddr3 ypcb_00338_1p1`.
3. Load it and talk to the BIOS over the JTAG UART.
