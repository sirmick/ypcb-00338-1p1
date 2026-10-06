// Gate 7: timing on silicon. The MMCM runs at TIMING_MHZ (gen_params.v from prebuild.py); four
// structures each consume 2^20 xorshift64 operands, fold every result into a 64-bit checksum and
// freeze. check.py replays them in Python: any wrong checksum is a failure at that clock.
//   0 add32:  32-bit accumulator (one CARRY4 chain)
//   1 add64:  64-bit accumulator (a long chain)
//   2 carry:  33-bit sum and a < b, carry out and O both used in fabric (openXC7/nextpnr-xilinx#134 shape)
//   3 srl_ce: clock-enabled shift registers 32, 16 and 3 deep (YosysHQ/yosys#6058 shape)
// USER1, 384 bits, LSB first: 0 magic 32'h54494D37 ("TIM7"), 1 status (bit 0 locked, 4:1 done),
// 2 the build's MHz, 3-10 checksums as 64-bit pairs, low word first.
module timing(input SYS_CLK, output [2:0] led);
    wire fb, locked, o0, clk;
    MMCME2_ADV #(
        .BANDWIDTH("OPTIMIZED"), .CLKIN1_PERIOD(20.0), .DIVCLK_DIVIDE(1),
        .CLKFBOUT_MULT_F(`TIMING_MULT), .CLKOUT0_DIVIDE_F(`TIMING_DIV)
    ) mmcm (
        .CLKIN1(SYS_CLK), .CLKIN2(1'b0), .CLKINSEL(1'b1), .CLKFBIN(fb), .CLKFBOUT(fb),
        .CLKOUT0(o0), .LOCKED(locked), .PWRDWN(1'b0), .RST(1'b0),
        .DADDR(7'h0), .DCLK(1'b0), .DEN(1'b0), .DI(16'h0), .DWE(1'b0),
        .PSCLK(1'b0), .PSEN(1'b0), .PSINCDEC(1'b0)
    );
    BUFG g (.I(o0), .O(clk));
    reg [1:0] lock_sync = 0;
    always @(posedge clk) lock_sync <= {lock_sync[0], locked};
    wire go = lock_sync[1];

    wire [63:0] cs0, cs1, cs2, cs3;
    wire [3:0] done;
    t_add32  #(.SEED(64'h0123456789ABCDEF)) t0 (clk, go, cs0, done[0]);
    t_add64  #(.SEED(64'h0F1E2D3C4B5A6978)) t1 (clk, go, cs1, done[1]);
    t_carry  #(.SEED(64'h1122334455667788)) t2 (clk, go, cs2, done[2]);
    t_srl_ce #(.SEED(64'hCAFEBABEDEADBEEF)) t3 (clk, go, cs3, done[3]);

    wire capture, drck, shift, tdi;
    reg [383:0] sr = 0;
    wire [383:0] snap = {32'd0, cs3, cs2, cs1, cs0, 32'd`TIMING_MHZ, {27'd0, done, locked}, 32'h54494D37};
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[383:1]};
    assign led = {&done, done[0], locked};
endmodule

// Operand source and checksum: xorshift64, 2^20 issues, results folded 2 cycles after issue (the
// operand register stage, then the structure's own register).
module gen2 #(parameter SEED = 64'h1) (
    input clk, input go, output reg [63:0] xr = 0, output reg vr = 0, input [63:0] result,
    output reg [63:0] cs = 0, output reg done = 0);
    reg [63:0] x = SEED;
    reg [20:0] n = 0;
    reg v2 = 0;
    wire issue = go && !n[20];
    wire [63:0] t1 = x ^ (x << 13), t2 = t1 ^ (t1 >> 7), t3 = t2 ^ (t2 << 17);
    always @(posedge clk) begin
        if (issue) begin x <= t3; n <= n + 1; end
        xr <= x; vr <= issue;                // operands of issue t are in xr during t+1
        v2 <= vr;                            // the structure registers its result at the end of t+1
        if (v2) cs <= {cs[62:0], cs[63]} ^ result;
        if (n[20] && !vr && !v2) done <= 1;
    end
endmodule

module t_add32 #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] xr; wire vr;
    reg [31:0] acc = 0;
    always @(posedge clk) if (vr) acc <= acc + xr[31:0];
    gen2 #(.SEED(SEED)) g (clk, go, xr, vr, {32'd0, acc}, cs, done);
endmodule

module t_add64 #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] xr; wire vr;
    reg [63:0] acc = 0;
    always @(posedge clk) if (vr) acc <= acc + xr;
    gen2 #(.SEED(SEED)) g (clk, go, xr, vr, acc, cs, done);
endmodule

module t_carry #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] xr; wire vr;
    reg [33:0] r = 0;
    wire [31:0] a = xr[31:0], b = xr[63:32];
    always @(posedge clk) r <= {a < b, {1'b0, a} + {1'b0, b}};
    gen2 #(.SEED(SEED)) g (clk, go, xr, vr, {30'd0, r}, cs, done);
endmodule

module t_srl_ce #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] xr; wire vr;
    reg [31:0] s32 = 0; reg [15:0] s16 = 0; reg [2:0] s3 = 0;
    wire ce = vr & xr[40];
    always @(posedge clk) if (ce) begin
        s32 <= {s32[30:0], xr[0]}; s16 <= {s16[14:0], xr[1]}; s3 <= {s3[1:0], xr[2]};
    end
    gen2 #(.SEED(SEED)) g (clk, go, xr, vr, {61'd0, s3[2], s16[15], s32[31]}, cs, done);
endmodule
