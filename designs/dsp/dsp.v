// Gate 6: DSP48E1, inferred by yosys, at 200 MHz. After the MMCM locks, each of four datapaths
// consumes exactly 2^20 operands from its own xorshift64 generator, folds every result into a 64-bit
// checksum (cs = rotl(cs, 1) ^ result) and then freezes. check.py recomputes all four in Python.
//   0 mul:   25x18 signed multiply, registered in, out and in between (AREG/BREG/MREG/PREG)
//   1 mac:   48-bit accumulate of 25x18 signed products (P feedback)
//   2 preadd: (A + D) x B, A and D 25-bit signed, B 18-bit signed (pre-adder)
//   3 wide:  32x32 unsigned -> 64 bits (several DSPs, cascade)
// USER1, 384 bits, LSB first, 32-bit words: 0 magic 32'h44535036 ("DSP6"), 1 status: bit 0 locked,
// bits 4:1 done per datapath; then checksums as 64-bit pairs (low word first): 2-3 mul, 4-5 mac,
// 6-7 preadd, 8-9 wide; 10-11 zero.
module dsp(input SYS_CLK, output [2:0] led);
    wire fb, locked, o0, clk;
    MMCME2_ADV #(
        .BANDWIDTH("OPTIMIZED"), .CLKIN1_PERIOD(20.0), .DIVCLK_DIVIDE(1), .CLKFBOUT_MULT_F(20.0),
        .CLKOUT0_DIVIDE_F(5.0)
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
    dsp_mul    #(.SEED(64'h0123456789ABCDEF)) t0 (clk, go, cs0, done[0]);
    dsp_mac    #(.SEED(64'h0F1E2D3C4B5A6978)) t1 (clk, go, cs1, done[1]);
    dsp_preadd #(.SEED(64'h1122334455667788)) t2 (clk, go, cs2, done[2]);
    dsp_wide   #(.SEED(64'hCAFEBABEDEADBEEF)) t3 (clk, go, cs3, done[3]);

    wire capture, drck, shift, tdi;
    reg [383:0] sr = 0;
    wire [383:0] snap = {64'd0, cs3, cs2, cs1, cs0, {27'd0, done, locked}, 32'h44535036};
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[383:1]};
    assign led = {&done, done[0], locked};
endmodule

// Shared operand source and result folding: a xorshift64 generator, an operand counter that stops
// after 2^20 issues, and a checksum that folds each result arriving `LAT` cycles after its issue.
module src_fold #(parameter SEED = 64'h1, parameter LAT = 4) (
    input clk, input go, output reg [63:0] x = SEED, output issue, input [63:0] result,
    output reg [63:0] cs = 0, output reg done = 0);
    reg [20:0] n = 0;
    assign issue = go && !n[20];
    reg [LAT-1:0] v = 0;
    always @(posedge clk) begin
        if (issue) begin
            x <= x ^ (x << 13) ^ ((x ^ (x << 13)) >> 7) ^
                 ((x ^ (x << 13) ^ ((x ^ (x << 13)) >> 7)) << 17);
            n <= n + 1;
        end
        v <= {v[LAT-2:0], issue};
        if (v[LAT-1]) cs <= {cs[62:0], cs[63]} ^ result;
        if (n[20] && v == 0) done <= 1;
    end
endmodule

module dsp_mul #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] x; wire issue;
    reg signed [24:0] a = 0; reg signed [17:0] b = 0;
    reg signed [42:0] m = 0, p = 0;
    always @(posedge clk) begin a <= x[24:0]; b <= x[47:30]; m <= a * b; p <= m; end
    // issue at cycle 0 samples x; a/b at 1, m at 2, p at 3: result valid 3 cycles after issue
    src_fold #(.SEED(SEED), .LAT(3)) f (clk, go, x, issue, {{21{p[42]}}, p}, cs, done);
endmodule

module dsp_mac #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] x; wire issue;
    reg signed [24:0] a = 0; reg signed [17:0] b = 0;
    reg signed [42:0] m = 0;
    reg signed [47:0] acc = 0;
    reg v1 = 0, v2 = 0;
    always @(posedge clk) begin
        a <= x[24:0]; b <= x[47:30]; v1 <= issue;
        m <= a * b; v2 <= v1;
        if (v2) acc <= acc + m;              // accumulate only real products
    end
    // product of issue t is added at the end of t+2, so acc holds exactly k products during t+3
    src_fold #(.SEED(SEED), .LAT(3)) f (clk, go, x, issue, {{16{acc[47]}}, acc}, cs, done);
endmodule

module dsp_preadd #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] x; wire issue;
    reg signed [24:0] a = 0, d = 0; reg signed [17:0] b = 0;
    reg signed [24:0] ad = 0; reg signed [17:0] b2 = 0;
    reg signed [42:0] m = 0, p = 0;
    always @(posedge clk) begin
        a <= x[24:0]; d <= {x[63:56], x[16:0]}; b <= x[47:30];
        ad <= a + d; b2 <= b;                // 25-bit pre-adder result (wraps, as in the DSP)
        m <= ad * b2; p <= m;
    end
    src_fold #(.SEED(SEED), .LAT(4)) f (clk, go, x, issue, {{21{p[42]}}, p}, cs, done);
endmodule

module dsp_wide #(parameter SEED = 64'h1) (input clk, input go, output [63:0] cs, output done);
    wire [63:0] x; wire issue;
    reg [31:0] a = 0, b = 0;
    reg [63:0] m = 0, p = 0, q = 0;
    always @(posedge clk) begin a <= x[31:0]; b <= x[63:32]; m <= a * b; p <= m; q <= p; end
    src_fold #(.SEED(SEED), .LAT(4)) f (clk, go, x, issue, q, cs, done);
endmodule
