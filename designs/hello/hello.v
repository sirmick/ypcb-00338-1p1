// Hello world for the YPCB-00338-1P1: blink the LEDs off the 50 MHz SYS_CLK
// and expose a free-running counter on JTAG USER1 so it can be checked remotely.
//
// led[0] ~0.37 Hz, led[1] its inverse, led[2] ~1.5 Hz. All static => no clock.
// USER1 (IR 0x02) shifts out 64 bits, LSB first: 32'hC0FFEE42, then the counter.
module hello(input SYS_CLK, output [2:0] led);
    reg [31:0] count = 0;
    always @(posedge SYS_CLK) count <= count + 1;
    assign led = {count[24], ~count[26], count[26]};

    wire capture, drck, sel, shift, tdi;
    reg [63:0] sr = 0;
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(sel),
        .SHIFT(shift), .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    // DRCK only toggles while USER1 is selected in Capture-DR or Shift-DR.
    always @(posedge drck)
        if (capture) sr <= {count, 32'hC0FFEE42};
        else if (shift) sr <= {tdi, sr[63:1]};
endmodule
