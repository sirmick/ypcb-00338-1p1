// Blinky that drives AC24 high itself instead of relying on board.fasm: the portable way to keep
// the YPCB-00338-1P1 from resetting at startup (works in Vivado and openXC7). Build with BOARD_FASM=.
module blinky_ac24drive(input SYS_CLK, output [2:0] led, output ac24);
    reg [26:0] count = 0;
    always @(posedge SYS_CLK) count <= count + 1;
    assign led = {count[24], ~count[26], count[26]};
    assign ac24 = 1'b1;   // board reset: must be high
endmodule
