// Memories VexiiRiscv's netlist leaves as black boxes (SpinalHDL's blackboxing of RAMs; LiteX ships its
// own). Plain Verilog that Verilator simulates and yosys infers: Ram_1w_1rs (synchronous read, block RAM)
// and Ram_1w_1ra (asynchronous read, distributed RAM). Read and write widths are equal in every instance.
// vexii-cluster.sh appends this file to the netlist, so both get its Vx_ prefix.
module Ram_1w_1rs #(
  parameter wordCount = 0, parameter wordWidth = 0, parameter clockCrossing = 0, parameter technology = "auto",
  parameter readUnderWrite = "dontCare", parameter wrAddressWidth = 1, parameter wrDataWidth = 1,
  parameter wrMaskWidth = 1, parameter wrMaskEnable = 0, parameter rdAddressWidth = 1, parameter rdDataWidth = 1,
  parameter rdLatency = 1
) (
  input wr_clk, input wr_en, input [wrMaskWidth-1:0] wr_mask, input [wrAddressWidth-1:0] wr_addr,
  input [wrDataWidth-1:0] wr_data, input rd_clk, input rd_en, input [rdAddressWidth-1:0] rd_addr,
  input rd_dataEn, output [rdDataWidth-1:0] rd_data
);
  localparam G = wrDataWidth / wrMaskWidth;
  reg [wrDataWidth-1:0] ram [0:wordCount-1];
  reg [rdDataWidth-1:0] q;
  integer i;
  always @(posedge wr_clk)
    if (wr_en)
      for (i = 0; i < wrMaskWidth; i = i + 1)
        if (!wrMaskEnable || wr_mask[i]) ram[wr_addr][i*G +: G] <= wr_data[i*G +: G];
  always @(posedge rd_clk) if (rd_en) q <= ram[rd_addr];
  assign rd_data = q;
endmodule

module Ram_1w_1ra #(
  parameter wordCount = 0, parameter wordWidth = 0, parameter technology = "auto", parameter readUnderWrite = "dontCare",
  parameter wrAddressWidth = 1, parameter wrDataWidth = 1, parameter wrMaskWidth = 1, parameter wrMaskEnable = 0,
  parameter rdAddressWidth = 1, parameter rdDataWidth = 1
) (
  input clk, input wr_en, input [wrMaskWidth-1:0] wr_mask, input [wrAddressWidth-1:0] wr_addr,
  input [wrDataWidth-1:0] wr_data, input [rdAddressWidth-1:0] rd_addr, output [rdDataWidth-1:0] rd_data
);
  localparam G = wrDataWidth / wrMaskWidth;
  reg [wrDataWidth-1:0] ram [0:wordCount-1];
  integer i;
  always @(posedge clk)
    if (wr_en)
      for (i = 0; i < wrMaskWidth; i = i + 1)
        if (!wrMaskEnable || wr_mask[i]) ram[wr_addr][i*G +: G] <= wr_data[i*G +: G];
  assign rd_data = ram[rd_addr];
endmodule
