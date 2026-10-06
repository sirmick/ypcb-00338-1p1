// Gate 4, BUFR from a clock-capable pin (the path nextpnr supports: IOB -> HCLK_IOI -> BUFR.I).
// SYS_CLK (AA28, MRCC) drives a BUFG (reference) and two BUFRs: bypass and /4. USER1, 160 bits:
//   word 0 magic 32'h42554652 ("BUFR"), words 1-3 gray counters: ref 50, BUFR bypass 50, BUFR /4 12.5 (MHz)
//   word 4 zero.
module bufr_pin(input SYS_CLK, output [2:0] led);
    wire [2:0] clk;
    BUFG ref_bufg (.I(SYS_CLK), .O(clk[0]));
    BUFR #(.BUFR_DIVIDE("BYPASS")) r1 (.I(SYS_CLK), .O(clk[1]), .CE(1'b1), .CLR(1'b0));
    BUFR #(.BUFR_DIVIDE("4")) r4 (.I(SYS_CLK), .O(clk[2]), .CE(1'b1), .CLR(1'b0));
    wire [31:0] gray [0:2];
    wire [2:0] slow;
    genvar i;
    generate
        for (i = 0; i < 3; i = i + 1) begin : ctr
            reg [31:0] bin = 0, g = 0;
            always @(posedge clk[i]) begin bin <= bin + 1; g <= bin ^ (bin >> 1); end
            assign gray[i] = g;
            assign slow[i] = bin[24];
        end
    endgenerate
    wire capture, drck, shift, tdi;
    reg [159:0] sr = 0;
    wire [159:0] snap = {32'd0, gray[2], gray[1], gray[0], 32'h42554652};
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[159:1]};
    assign led = slow;
endmodule
