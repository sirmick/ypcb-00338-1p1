// Gate 5: block RAM. Two memories inferred by yosys, run at 200 MHz from an MMCM:
//   m36: 4096 x 36 (RAMB36 territory), m18: 1024 x 18 (a RAMB18), both with initial contents set
//   in the bitstream (f36 / f18 below).
// After lock the design reads every initial word once (checking it and summing it), then loops
// forever: write pattern p(addr, pass) over the whole depth, read it all back and compare.
// USER1, 512 bits, LSB first, 32-bit words:
//   0 magic 32'h4252414D ("BRAM")      1 status: bit 0 MMCM locked, bits 2:1 state
//   2 initial-content errors, m36      3 initial-content errors, m18
//   4 sum of m36's initial words (low 32 bits)   5 sum of m18's initial words
//   6 passes completed (gray)          7 pattern errors, m36      8 pattern errors, m18
//   9 first failing address            10 first failing data (low 32 bits)   11 expected data
module bram(input SYS_CLK, output [2:0] led);
    function [35:0] f36(input [11:0] i);
        f36 = {i ^ 12'h5A5, ~i, i ^ {i[5:0], i[11:6]}};
    endfunction
    function [17:0] f18(input [9:0] i);
        f18 = {i ^ 10'h2B7, ~i[7:0]};
    endfunction
    function [35:0] p36(input [11:0] a, input [11:0] s);
        p36 = f36(a) ^ {s, s, s};
    endfunction
    function [17:0] p18(input [9:0] a, input [11:0] s);
        p18 = f18(a) ^ {s[9:0], s[7:0]};
    endfunction

    // ---- 200 MHz from an MMCM (gate 4)
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

    // ---- the memories (simple dual port: one write port, one registered read port each)
    reg [35:0] m36 [0:4095];
    reg [17:0] m18 [0:1023];
    integer k;
    initial begin
        for (k = 0; k < 4096; k = k + 1) m36[k] = f36(k);
        for (k = 0; k < 1024; k = k + 1) m18[k] = f18(k);
    end
    reg [11:0] waddr = 0, raddr = 0;
    reg we = 0;
    reg [35:0] wd36 = 0, q36 = 0;
    reg [17:0] wd18 = 0, q18 = 0;
    always @(posedge clk) begin
        if (we) m36[waddr] <= wd36;
        if (we && waddr < 1024) m18[waddr[9:0]] <= wd18;
        q36 <= m36[raddr];
        q18 <= m18[raddr[9:0]];
    end

    // ---- the test sequencer
    // Read pipeline: cycle n issues raddr (registered); cycle n+1 the RAM registers q; cycle n+2
    // compares q. The iss_* and rd_* registers carry address, state and seed alongside.
    localparam INIT = 2'd0, WRITE = 2'd1, READ = 2'd2;
    reg [1:0] state = INIT;
    reg [1:0] lock_sync = 0;
    reg [11:0] pass = 1;                            // pattern seed; never 0, so patterns differ from INIT
    reg [31:0] passes = 0, passes_g = 0;
    reg [31:0] init_err36 = 0, init_err18 = 0, err36 = 0, err18 = 0;
    reg [39:0] sum36 = 0;
    reg [31:0] sum18 = 0;
    reg [31:0] bad_addr = 0, bad_data = 0, bad_want = 0;
    reg iss_v = 0, rd_v = 0;
    reg [1:0] iss_state = INIT, rd_state = INIT;
    reg [11:0] iss_addr = 0, rd_addr = 0, iss_pass = 0, rd_pass = 0;
    reg [11:0] ctr = 0;

    always @(posedge clk) begin
        lock_sync <= {lock_sync[0], locked};
        passes_g <= passes ^ (passes >> 1);
        we <= 0;
        iss_v <= 0;
        {rd_v, rd_state, rd_addr, rd_pass} <= {iss_v, iss_state, iss_addr, iss_pass};
        if (rd_v) begin
            if (rd_state == INIT) begin
                sum36 <= sum36 + q36;
                if (q36 != f36(rd_addr)) init_err36 <= init_err36 + 1;
                if (rd_addr < 1024) begin
                    sum18 <= sum18 + q18;
                    if (q18 != f18(rd_addr[9:0])) init_err18 <= init_err18 + 1;
                end
            end else begin
                if (q36 != p36(rd_addr, rd_pass)) begin
                    err36 <= err36 + 1;
                    if (err36 == 0 && err18 == 0) begin
                        bad_addr <= rd_addr; bad_data <= q36[31:0]; bad_want <= p36(rd_addr, rd_pass);
                    end
                end
                if (rd_addr < 1024 && q18 != p18(rd_addr[9:0], rd_pass)) err18 <= err18 + 1;
            end
        end
        if (lock_sync[1]) begin
            case (state)
                INIT, READ: begin
                    raddr <= ctr;
                    {iss_v, iss_state, iss_addr, iss_pass} <= {1'b1, state, ctr, pass};
                    ctr <= ctr + 1;
                    if (ctr == 4095) begin
                        state <= WRITE;
                        if (state == READ) begin
                            pass <= (pass == 12'hFFF) ? 12'd1 : pass + 1;
                            passes <= passes + 1;
                        end
                    end
                end
                WRITE: begin
                    waddr <= ctr; we <= 1;
                    wd36 <= p36(ctr, pass);
                    wd18 <= p18(ctr[9:0], pass);
                    ctr <= ctr + 1;
                    if (ctr == 4095) state <= READ;
                end
            endcase
        end
    end

    // ---- USER1
    wire capture, drck, shift, tdi;
    reg [511:0] sr = 0;
    wire [511:0] snap = {128'd0,
                         bad_want, bad_data, bad_addr, err18, err36, passes_g,
                         sum18, sum36[31:0], init_err18, init_err36,
                         {29'd0, state, locked}, 32'h4252414D};
    BSCANE2 #(.JTAG_CHAIN(1)) bscan (
        .CAPTURE(capture), .DRCK(drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(shift),
        .TCK(), .TDI(tdi), .TMS(), .UPDATE(), .TDO(sr[0]));
    always @(posedge drck)
        if (capture) sr <= snap;
        else if (shift) sr <= {tdi, sr[511:1]};

    assign led = {passes[12], err36 == 0 && err18 == 0, locked};
endmodule
