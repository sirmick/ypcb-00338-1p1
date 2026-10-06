// SoC roadmap S0: VexiiRiscv's MicroSoc on the card. 50 MHz SYS_CLK straight in; reset held for 2^16
// cycles after configuration; the self-test in RAM (sw/) drives the three LEDs (see sw/main.c).
module vexii_s0 (
    input  wire SYS_CLK,
    output wire [2:0] led
);
    reg [16:0] por = 0;
    always @(posedge SYS_CLK) if (!por[16]) por <= por + 1;
    wire txd;
    MicroSoc soc (
        .socCtrl_systemClk(SYS_CLK),
        .socCtrl_asyncReset(!por[16]),
        .system_peripheral_uart_logic_uart_txd(txd),
        .system_peripheral_uart_logic_uart_rxd(1'b1),
        .system_peripheral_demo_logic_leds(led),
        .system_peripheral_demo_logic_buttons(1'b0)
    );
endmodule
