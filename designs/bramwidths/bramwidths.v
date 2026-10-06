// Gate 5, every width: thirteen memories, each sized to fill one block RAM in one mode, all
// inferred by yosys and run at 200 MHz from an MMCM. Each memtest checks its initial contents
// (set in the bitstream), then loops writing and reading back a pattern over its whole depth.
//   RAMB36-sized (36 Kb): 32768x1, 16384x2, 8192x4, 4096x9, 2048x18, 1024x36, 512x72
//   RAMB18-sized (18 Kb): 2048x9, 1024x18, 512x36, 4096x4, 8192x2, 16384x1
//                         (the parity-free modes use 16 of the 18 Kb)
// USER1, 1312 bits, LSB first, 32-bit words: 0 magic 32'h42524D57 ("BRMW"), 1 status (bit 0 locked),
// then per memory i (order above): 2+3i initial-content errors, 3+3i pattern errors, 4+3i passes (gray).
module bramwidths(input SYS_CLK, output [2:0] led);
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

    localparam N = 13;
    wire [32 * 3 * N - 1:0] st;
    memtest #(.W(1), .AW(15), .INIT_FILE("init_w1_a15.hex")) t0  (clk, go, st[  0 +: 96]);
    memtest #(.W(2), .AW(14), .INIT_FILE("init_w2_a14.hex")) t1  (clk, go, st[ 96 +: 96]);
    memtest #(.W(4), .AW(13), .INIT_FILE("init_w4_a13.hex")) t2  (clk, go, st[192 +: 96]);
    memtest #(.W(9), .AW(12), .INIT_FILE("init_w9_a12.hex")) t3  (clk, go, st[288 +: 96]);
    memtest #(.W(18), .AW(11), .INIT_FILE("init_w18_a11.hex")) t4  (clk, go, st[384 +: 96]);
    memtest #(.W(36), .AW(10), .INIT_FILE("init_w36_a10.hex")) t5  (clk, go, st[480 +: 96]);
    memtest #(.W(72), .AW(9), .INIT_FILE("init_w72_a9.hex"))  t6  (clk, go, st[576 +: 96]);
    memtest #(.W(9), .AW(11), .INIT_FILE("init_w9_a11.hex")) t7  (clk, go, st[672 +: 96]);
    memtest #(.W(18), .AW(10), .INIT_FILE("init_w18_a10.hex")) t8  (clk, go, st[768 +: 96]);
    memtest #(.W(36), .AW(9), .INIT_FILE("init_w36_a9.hex"))  t9  (clk, go, st[864 +: 96]);
    memtest #(.W(4), .AW(12), .INIT_FILE("init_w4_a12.hex")) t10 (clk, go, st[960 +: 96]);
    memtest #(.W(2), .AW(13), .INIT_FILE("init_w2_a13.hex")) t11 (clk, go, st[1056 +: 96]);
    memtest #(.W(1), .AW(14), .INIT_FILE("init_w1_a14.hex")) t12 (clk, go, st[1152 +: 96]);

    wire capture, drck, shift, tdi;
    reg [1311:0] sr = 0;
    wire [1311:0] snap = {st, 31'd0, locked, 32'h42524D57};
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[1311:1]};
    assign led = {1'b0, 1'b0, locked};
endmodule

// One memory of W bits x 2^AW words, with its own initial-content check and pattern loop.
// status = {passes (gray), pattern errors, initial-content errors}.
module memtest #(parameter W = 9, parameter AW = 12, parameter INIT_FILE = "init_w9_a12.hex") (input clk, input go, output [95:0] status);
    localparam D = 1 << AW;
    // 72-bit value per address: f(a) truncated to W bits is the initial content; the pattern for
    // pass s is f(a) ^ g(s).
    function [71:0] f(input [15:0] a);
        f = {a[7:0], a ^ {a[7:0], a[15:8]}, a ^ 16'hA5C3, ~a, a ^ {15'd0, ^a}};
    endfunction
    function [71:0] g(input [11:0] s);
        g = {s[7:0], s, s, s, s, s};
    endfunction

    reg [W-1:0] mem [0:D-1];
    // Initial contents f(a) come from prebuild.py: an initial loop over 32768 words takes yosys
    // tens of minutes to evaluate, $readmemh a fraction of a second.
    initial $readmemh(INIT_FILE, mem);

    reg [AW-1:0] waddr = 0, raddr = 0;
    reg we = 0;
    reg [W-1:0] wd = 0, q = 0;
    always @(posedge clk) begin
        if (we) mem[waddr] <= wd;
        q <= mem[raddr];
    end

    localparam INIT = 2'd0, WRITE = 2'd1, READ = 2'd2;
    reg [1:0] state = INIT;
    reg [11:0] pass = 1;
    reg [31:0] passes = 0, passes_g = 0, init_err = 0, err = 0;
    reg iss_v = 0, rd_v = 0;
    reg [1:0] iss_state = INIT, rd_state = INIT;
    reg [AW-1:0] iss_addr = 0, rd_addr = 0, ctr = 0;
    reg [11:0] iss_pass = 0, rd_pass = 0;
    wire [W-1:0] want_init = f(rd_addr);
    wire [W-1:0] want_pat = f(rd_addr) ^ g(rd_pass);
    always @(posedge clk) begin
        passes_g <= passes ^ (passes >> 1);
        we <= 0;
        iss_v <= 0;
        {rd_v, rd_state, rd_addr, rd_pass} <= {iss_v, iss_state, iss_addr, iss_pass};
        if (rd_v) begin
            if (rd_state == INIT) begin
                if (q != want_init) init_err <= init_err + 1;
            end else if (q != want_pat) err <= err + 1;
        end
        if (go) begin
            case (state)
                INIT, READ: begin
                    raddr <= ctr;
                    {iss_v, iss_state, iss_addr, iss_pass} <= {1'b1, state, ctr, pass};
                    ctr <= ctr + 1;
                    if (ctr == D - 1) begin
                        state <= WRITE;
                        if (state == READ) begin
                            pass <= (pass == 12'hFFF) ? 12'd1 : pass + 1;
                            passes <= passes + 1;
                        end
                    end
                end
                WRITE: begin
                    waddr <= ctr; we <= 1;
                    wd <= f(ctr) ^ g(pass);
                    ctr <= ctr + 1;
                    if (ctr == D - 1) state <= READ;
                end
            endcase
        end
    end
    assign status = {passes_g, err, init_err};
endmodule
