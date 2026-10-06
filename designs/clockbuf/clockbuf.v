// Gate 4, regional buffers: MMCM outputs through BUFH and BUFR (bypass and /4), each counting in
// its own domain, measured against the 50 MHz reference. USER1, 256 bits, LSB first:
//   word 0 magic 32'h42554648 ("BUFH"), word 1 status (bit 0 MMCM locked),
//   words 2-6 gray counters: ref 50, BUFG 200, BUFH 200, BUFR bypass 100, BUFR /4 of 200 = 50 (MHz)
module clockbuf(input SYS_CLK, output [2:0] led);
    wire ref_clk;
    BUFG ref_bufg (.I(SYS_CLK), .O(ref_clk));
    wire fb, locked, o0, o1;
    MMCME2_ADV #(
        .BANDWIDTH("OPTIMIZED"), .CLKIN1_PERIOD(20.0), .DIVCLK_DIVIDE(1), .CLKFBOUT_MULT_F(20.0),
        .CLKOUT0_DIVIDE_F(5.0), .CLKOUT1_DIVIDE(10)            // 200 and 100 MHz
    ) mmcm (
        .CLKIN1(SYS_CLK), .CLKIN2(1'b0), .CLKINSEL(1'b1), .CLKFBIN(fb), .CLKFBOUT(fb),
        .CLKOUT0(o0), .CLKOUT1(o1), .LOCKED(locked), .PWRDWN(1'b0), .RST(1'b0),
        .DADDR(7'h0), .DCLK(1'b0), .DEN(1'b0), .DI(16'h0), .DWE(1'b0),
        .PSCLK(1'b0), .PSEN(1'b0), .PSINCDEC(1'b0)
    );
    wire [4:0] clk;
    assign clk[0] = ref_clk;
    BUFG g200 (.I(o0), .O(clk[1]));
    BUFH h200 (.I(o0), .O(clk[2]));
    BUFR #(.BUFR_DIVIDE("BYPASS")) r100 (.I(o1), .O(clk[3]), .CE(1'b1), .CLR(1'b0));
    BUFR #(.BUFR_DIVIDE("4")) r50 (.I(o0), .O(clk[4]), .CE(1'b1), .CLR(1'b0));

    wire [31:0] gray [0:4];
    wire [4:0] slow;
    genvar i;
    generate
        for (i = 0; i < 5; i = i + 1) begin : ctr
            reg [31:0] bin = 0, g = 0;
            always @(posedge clk[i]) begin bin <= bin + 1; g <= bin ^ (bin >> 1); end
            assign gray[i] = g;
            assign slow[i] = bin[26];
        end
    endgenerate

    wire capture, drck, shift, tdi;
    reg [255:0] sr = 0;
    wire [255:0] snap;
    assign snap[31:0] = 32'h42554648;
    assign snap[63:32] = {31'd0, locked};
    generate for (i = 0; i < 5; i = i + 1) begin : pack assign snap[64 + 32 * i +: 32] = gray[i]; end endgenerate
    assign snap[255:224] = 32'd0;
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[255:1]};
    assign led = {slow[3], slow[2], locked};
endmodule
