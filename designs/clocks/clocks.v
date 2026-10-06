// Gate 4: clock management. One MMCME2_ADV and one PLLE2_ADV run from the 50 MHz SYS_CLK (VCO
// 1000 MHz each); every output drives a BUFG and a gray-coded 32-bit counter. USER1 shifts out
// 512 bits, LSB first:
//   word 0      magic 32'h434C4B34 ("CLK4")
//   word 1      status: bit 0 MMCM locked, bit 1 PLL locked, bit 2 MMCM lost lock, bit 3 PLL lost lock
//   words 2-15  gray counters: ref 50, MMCM 133.33 (fractional /7.5), 200, 250, 166.67, 100, 333.33,
//               50, then PLL 250, 200, 100, 125, 333.33, 166.67 (MHz)
// check.py turns two reads into rates, each measured against the ref counter.
module clocks(input SYS_CLK, output [2:0] led);
    // The CMTs take SYS_CLK straight from the pin (AA28 is a clock-capable MRCC input); the BUFG
    // copy is for the fabric. Feeding the CMTs from the BUFG output made nextpnr report hold
    // violations from a 0.85 ns skew estimate on that net.
    wire ref_clk;
    BUFG ref_bufg (.I(SYS_CLK), .O(ref_clk));

    // ---- MMCM: VCO = 50 * 20 = 1000 MHz
    wire mmcm_fb, mmcm_locked;
    wire [6:0] mo;                                  // raw MMCM outputs
    MMCME2_ADV #(
        .BANDWIDTH("OPTIMIZED"), .CLKIN1_PERIOD(20.0), .DIVCLK_DIVIDE(1), .CLKFBOUT_MULT_F(20.0),
        .CLKOUT0_DIVIDE_F(7.5),                     // 133.333 (fractional)
        .CLKOUT1_DIVIDE(5), .CLKOUT2_DIVIDE(4), .CLKOUT3_DIVIDE(6),
        .CLKOUT4_DIVIDE(10), .CLKOUT5_DIVIDE(3), .CLKOUT6_DIVIDE(20)
    ) mmcm (
        .CLKIN1(SYS_CLK), .CLKIN2(1'b0), .CLKINSEL(1'b1), .CLKFBIN(mmcm_fb), .CLKFBOUT(mmcm_fb),
        .CLKOUT0(mo[0]), .CLKOUT1(mo[1]), .CLKOUT2(mo[2]), .CLKOUT3(mo[3]),
        .CLKOUT4(mo[4]), .CLKOUT5(mo[5]), .CLKOUT6(mo[6]),
        .LOCKED(mmcm_locked), .PWRDWN(1'b0), .RST(1'b0),
        .DADDR(7'h0), .DCLK(1'b0), .DEN(1'b0), .DI(16'h0), .DWE(1'b0),
        .PSCLK(1'b0), .PSEN(1'b0), .PSINCDEC(1'b0)
    );

    // ---- PLL: VCO = 50 * 20 = 1000 MHz
    wire pll_fb, pll_locked;
    wire [5:0] po;
    PLLE2_ADV #(
        .BANDWIDTH("OPTIMIZED"), .CLKIN1_PERIOD(20.0), .DIVCLK_DIVIDE(1), .CLKFBOUT_MULT(20),
        .CLKOUT0_DIVIDE(4), .CLKOUT1_DIVIDE(5), .CLKOUT2_DIVIDE(10),
        .CLKOUT3_DIVIDE(8), .CLKOUT4_DIVIDE(3), .CLKOUT5_DIVIDE(6)
    ) pll (
        .CLKIN1(SYS_CLK), .CLKIN2(1'b0), .CLKINSEL(1'b1), .CLKFBIN(pll_fb), .CLKFBOUT(pll_fb),
        .CLKOUT0(po[0]), .CLKOUT1(po[1]), .CLKOUT2(po[2]), .CLKOUT3(po[3]),
        .CLKOUT4(po[4]), .CLKOUT5(po[5]),
        .LOCKED(pll_locked), .PWRDWN(1'b0), .RST(1'b0),
        .DADDR(7'h0), .DCLK(1'b0), .DEN(1'b0), .DI(16'h0), .DWE(1'b0)
    );

    // ---- one BUFG and one gray counter per clock; clk[0] is the 50 MHz reference
    wire [13:0] clk;
    assign clk[0] = ref_clk;
    genvar i;
    generate
        for (i = 0; i < 7; i = i + 1) begin : mb
            BUFG b (.I(mo[i]), .O(clk[1 + i]));
        end
        for (i = 0; i < 6; i = i + 1) begin : pb
            BUFG b (.I(po[i]), .O(clk[8 + i]));
        end
    endgenerate

    wire [31:0] gray [0:13];
    wire [13:0] slow;                               // bit 26 of each counter, for the LEDs
    generate
        for (i = 0; i < 14; i = i + 1) begin : ctr
            reg [31:0] bin = 0, g = 0;
            always @(posedge clk[i]) begin
                bin <= bin + 1;
                g <= bin ^ (bin >> 1);              // registered: one bit changes per count
            end
            assign gray[i] = g;
            assign slow[i] = bin[26];
        end
    endgenerate

    // ---- sticky lost-lock flags (in the reference domain)
    reg mmcm_was = 0, pll_was = 0, mmcm_lost = 0, pll_lost = 0;
    always @(posedge ref_clk) begin
        mmcm_was <= mmcm_was | mmcm_locked;
        pll_was <= pll_was | pll_locked;
        if (mmcm_was && !mmcm_locked) mmcm_lost <= 1;
        if (pll_was && !pll_locked) pll_lost <= 1;
    end

    // ---- USER1: 512-bit capture/shift register
    wire capture, drck, shift, tdi;
    reg [511:0] sr = 0;
    wire [511:0] snap;
    assign snap[31:0] = 32'h434C4B34;
    assign snap[63:32] = {28'd0, pll_lost, mmcm_lost, pll_locked, mmcm_locked};
    generate
        for (i = 0; i < 14; i = i + 1) begin : pack
            assign snap[64 + 32 * i +: 32] = gray[i];
        end
    endgenerate
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[511:1]};

    // LEDs: MMCM locked, PLL locked, and a blink from the MMCM's 100 MHz domain (~0.75 Hz)
    assign led = {slow[5], pll_locked, mmcm_locked};
endmodule
