// Gate 9 prerequisite: are the two 200 MHz LVDS oscillators (DDR3 reference clocks) running?
// AH27/AH28 (bank 12, channel 0) and G25/G26 (bank 17, channel 1), each through IBUFDS and a BUFG,
// counted against the 50 MHz reference. USER1, 128 bits: word 0 magic 32'h43323030 ("C200"),
// words 1-3 gray counters: ref 50, clk200 #0, clk200 #1.
module clk200(input SYS_CLK, input c0_p, input c0_n, input c1_p, input c1_n, output [2:0] led);
    wire [2:0] clk;
    wire c0, c1;
    BUFG ref_bufg (.I(SYS_CLK), .O(clk[0]));
    IBUFDS #(.DIFF_TERM("FALSE")) ib0 (.I(c0_p), .IB(c0_n), .O(c0));
    IBUFDS #(.DIFF_TERM("FALSE")) ib1 (.I(c1_p), .IB(c1_n), .O(c1));
    BUFG g0 (.I(c0), .O(clk[1]));
    BUFG g1 (.I(c1), .O(clk[2]));
    wire [31:0] gray [0:2];
    wire [2:0] slow;
    genvar i;
    generate
        for (i = 0; i < 3; i = i + 1) begin : ctr
            reg [31:0] bin = 0, g = 0;
            always @(posedge clk[i]) begin bin <= bin + 1; g <= bin ^ (bin >> 1); end
            assign gray[i] = g;
            assign slow[i] = bin[26];
        end
    endgenerate
    wire capture, drck, shift, tdi;
    reg [127:0] sr = 0;
    wire [127:0] snap = {gray[2], gray[1], gray[0], 32'h43323030};
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[127:1]};
    assign led = slow;
endmodule
