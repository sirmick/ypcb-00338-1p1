// DDR PHY primitives in loopback, no DRAM: OSERDESE2 (DDR x8, 500 MHz) drives a pad through an
// IOBUF; the same pad's input goes through IDELAYE2 (VAR_LOAD) into ISERDESE2 (NETWORKING, DDR x8,
// BITSLIP). A checker compares each received word with the last 16 transmitted words. The pin and
// its I/O standard come from the XDC (see prebuild.py): an SSTL15 DDR pin or an LVCMOS18 pin.
//
// USER1 is 352 bits, LSB first. Written (on UPDATE), bits 15:0: {seq[7:0], bitslips[2:0], tap[4:0]}.
// A new seq makes the design load `tap`, pulse BITSLIP `bitslips` times, settle, then count matches
// over 4096 words. Read, 32-bit words: 0 magic 32'h5345524C ("SERL"), 1 status {..., cntvalue[4:0],
// seq_done[7:0], done, idelayctrl_rdy, locked}, 2 {tx word, rx word} (latest, 8 bits each),
// 3-10 match counts for offsets 0..15 (16 bits each, offset k in bits 16(k%2)+:16 of word 3+k/2).
module serdes_loop(input SYS_CLK, inout pad, output [1:0] led);
    // ---- clocks: 125 MHz (CLKDIV), 500 MHz (CLK), 200 MHz (IDELAYCTRL reference)
    wire fb, locked, o_sys, o_4x, o_ref, o_4xb, sys, sys4x, ref, sys4x_b;
    MMCME2_ADV #(
        .BANDWIDTH("OPTIMIZED"), .CLKIN1_PERIOD(20.0), .DIVCLK_DIVIDE(1), .CLKFBOUT_MULT_F(20.0),
        `ifdef SL_HALF
        .CLKOUT0_DIVIDE_F(16.0), .CLKOUT1_DIVIDE(4), .CLKOUT2_DIVIDE(5),   // 62.5 / 250 MHz: 500 Mbit/s
`else
        .CLKOUT0_DIVIDE_F(8.0), .CLKOUT1_DIVIDE(2), .CLKOUT2_DIVIDE(5),    // 125 / 500 MHz: 1 Gbit/s
`endif
        `ifdef SL_HALF
        .CLKOUT3_DIVIDE(4), .CLKOUT3_PHASE(180.0)
`else
        .CLKOUT3_DIVIDE(2), .CLKOUT3_PHASE(180.0)                // sys4x inverted, for CLKB
`endif
    ) mmcm (
        .CLKIN1(SYS_CLK), .CLKIN2(1'b0), .CLKINSEL(1'b1), .CLKFBIN(fb), .CLKFBOUT(fb),
        .CLKOUT0(o_sys), .CLKOUT1(o_4x), .CLKOUT2(o_ref), .CLKOUT3(o_4xb), .LOCKED(locked), .PWRDWN(1'b0), .RST(1'b0),
        .DADDR(7'h0), .DCLK(1'b0), .DEN(1'b0), .DI(16'h0), .DWE(1'b0),
        .PSCLK(1'b0), .PSEN(1'b0), .PSINCDEC(1'b0)
    );
    BUFG b0 (.I(o_sys), .O(sys));
    BUFG b1 (.I(o_4x), .O(sys4x));
    BUFG b2 (.I(o_ref), .O(ref));
    BUFG b3 (.I(o_4xb), .O(sys4x_b));
    reg [3:0] rst_sr = 4'hF;
    always @(posedge sys) rst_sr <= {rst_sr[2:0], ~locked};
    wire rst = rst_sr[3];

    wire rdy;
    IDELAYCTRL idc (.REFCLK(ref), .RST(rst), .RDY(rdy));

    // ---- transmit: PRBS7, 8 bits per cycle
    reg [6:0] lfsr = 7'h7F;
    reg [7:0] tx = 0;
    integer j;
    reg [6:0] l;
    reg [7:0] w;
    always @(posedge sys) begin
        l = lfsr;
        for (j = 0; j < 8; j = j + 1) begin w[j] = l[6]; l = {l[5:0], l[6] ^ l[5]}; end
        lfsr <= l;
        tx <= w;
    end
    wire oq, tq;
    OSERDESE2 #(
        .DATA_RATE_OQ("DDR"), .DATA_RATE_TQ("BUF"), .DATA_WIDTH(8), .SERDES_MODE("MASTER"),
        .TRISTATE_WIDTH(1)
    ) oser (
        .CLK(sys4x), .CLKDIV(sys), .RST(rst), .OCE(1'b1),
        .D1(tx[0]), .D2(tx[1]), .D3(tx[2]), .D4(tx[3]), .D5(tx[4]), .D6(tx[5]), .D7(tx[6]), .D8(tx[7]),
        .T1(1'b0), .TCE(1'b1), .OQ(oq), .TQ(tq)      // T through TQ: the IOB's T has no fabric constant route
    );
    wire pad_in;
    IOBUF iob (.I(oq), .T(tq), .IO(pad), .O(pad_in));

    // ---- receive
    wire dly;
    wire [4:0] cntvalue;
    reg ld = 0, bitslip = 0;
    reg [4:0] tap = 0;
    IDELAYE2 #(
        .IDELAY_TYPE("VAR_LOAD"), .DELAY_SRC("IDATAIN"), .IDELAY_VALUE(0), .REFCLK_FREQUENCY(200.0),
        .HIGH_PERFORMANCE_MODE("TRUE"), .SIGNAL_PATTERN("DATA"), .CINVCTRL_SEL("FALSE"),
        .PIPE_SEL("FALSE")
    ) idel (
        .C(sys), .LD(ld), .CE(1'b0), .INC(1'b0), .CNTVALUEIN(tap), .CNTVALUEOUT(cntvalue),
        .IDATAIN(pad_in), .DATAOUT(dly)
    );
    wire [7:0] q;
    ISERDESE2 #(
        .DATA_RATE("DDR"), .DATA_WIDTH(8), .INTERFACE_TYPE("NETWORKING"), .IOBDELAY("IFD"),
        .NUM_CE(1), .SERDES_MODE("MASTER"), .OFB_USED("FALSE"), .DYN_CLKDIV_INV_EN("FALSE"),
        .DYN_CLK_INV_EN("FALSE")
    ) iser (
`ifdef CLKB_MMCM
        .CLK(sys4x), .CLKB(sys4x_b), .CLKDIV(sys),      // CLKB from a 180-degree MMCM output
`else
        .CLK(sys4x), .CLKB(~sys4x), .CLKDIV(sys),       // CLKB as LiteDRAM wires it
`endif .RST(rst), .CE1(1'b1), .BITSLIP(bitslip), .DDLY(dly),
        .Q1(q[7]), .Q2(q[6]), .Q3(q[5]), .Q4(q[4]), .Q5(q[3]), .Q6(q[2]), .Q7(q[1]), .Q8(q[0])
    );
    reg [7:0] rx = 0;
    always @(posedge sys) rx <= q;

    // ---- control from JTAG (synchronised), sequencing and match counting
    reg [15:0] ctrl_tck = 0;
    reg [15:0] c1 = 0, c2 = 0;
    always @(posedge sys) begin c1 <= ctrl_tck; c2 <= c1; end
    reg [7:0] seq_done = 0;
    reg [2:0] slips_left = 0;
    reg [7:0] wait_ctr = 0;
    reg [12:0] win = 0;
    reg done = 0, counting = 0;
    reg [7:0] hist [0:15];
    reg [15:0] match [0:15];
    integer k;
    always @(posedge sys) begin
        ld <= 0; bitslip <= 0;
        hist[0] <= tx;
        for (k = 1; k < 16; k = k + 1) hist[k] <= hist[k - 1];
        if (c2[15:8] != seq_done && !rst && rdy && wait_ctr == 0 && !counting && slips_left == 0) begin
            tap <= c2[4:0]; ld <= 1; slips_left <= c2[7:5]; seq_done <= c2[15:8];
            done <= 0; wait_ctr <= 200;
        end else if (slips_left != 0 && wait_ctr[2:0] == 0) begin
            bitslip <= 1; slips_left <= slips_left - 1; wait_ctr <= 8;
        end else if (wait_ctr != 0) begin
            wait_ctr <= wait_ctr - 1;
            if (wait_ctr == 1 && slips_left == 0) begin
                counting <= 1; win <= 0;
                for (k = 0; k < 16; k = k + 1) match[k] <= 0;
            end
        end else if (counting) begin
            for (k = 0; k < 16; k = k + 1) if (rx == hist[k]) match[k] <= match[k] + 1;
            win <= win + 1;
            if (win == 4095) begin counting <= 0; done <= 1; end
        end
    end

    wire capture, drck, shift, tdi, update;
    reg [351:0] sr = 0;
    wire [351:0] snap;
    assign snap[31:0] = 32'h5345524C;
    assign snap[63:32] = {14'd0, cntvalue, seq_done, done, rdy, locked};
    assign snap[95:64] = {16'd0, tx, rx};
    genvar g;
    generate for (g = 0; g < 16; g = g + 1) begin : pk assign snap[96 + 16 * g +: 16] = match[g]; end endgenerate
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(update), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[351:1]};
    always @(posedge update) ctrl_tck <= sr[15:0];
    assign led = {done, rdy};
endmodule
