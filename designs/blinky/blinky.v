// Minimal blinky for the YPCB-00338-1P1: LEDs off the 50 MHz SYS_CLK, nothing else.
// led[0] ~0.37 Hz, led[1] its inverse, led[2] ~1.5 Hz. Check remotely with `fpga pins`.
module blinky(input SYS_CLK, output [2:0] led);
    reg [26:0] count = 0;
    always @(posedge SYS_CLK) count <= count + 1;
    assign led = {count[24], ~count[26], count[26]};
endmodule
