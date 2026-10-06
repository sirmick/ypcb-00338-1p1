// Memory test engine for UberDDR3's Wishbone port (part of this repository, not of UberDDR3).
//
// A run writes 2^len words from address 0 with a pseudo-random sequence (nine xorshift64 generators,
// one per 64-bit slice of the 576-bit word, seeded from `seed`), then reads them back in order and
// compares against the same sequence regenerated. The sequence position is the address, so address
// faults show as data errors. Wishbone is pipelined: acks return in order.
//
// mode[0] = write pass, mode[1] = read pass (read alone re-checks earlier contents, e.g. retention).
// err_mask has one bit per DQ line (lane * 8 + bit): set if that line ever read wrong.
module memtest #(
    parameter integer ADDR_BITS = 25,
    parameter integer DATA_BITS = 576
) (
    input wire clk,
    input wire ready,                       // controller calibrated
    input wire start,                       // one-cycle pulse
    input wire [1:0] mode,
    input wire [4:0] len,                   // 2^len words
    input wire [7:0] seed,
    output reg busy = 0,
    output reg [31:0] errors = 0,
    output reg [31:0] first_err_addr = 0,
    output reg [DATA_BITS / 8 - 1:0] err_mask = 0,
    output reg [31:0] write_cycles = 0,
    output reg [31:0] read_cycles = 0,
    // Wishbone master
    output reg wb_stb = 0,
    output reg wb_we = 0,
    output reg [ADDR_BITS - 1:0] wb_addr = 0,
    output wire [DATA_BITS - 1:0] wb_data,
    input wire wb_stall,
    input wire wb_ack,
    input wire [DATA_BITS - 1:0] wb_rdata
);
    localparam integer SLICES = DATA_BITS / 64;
    localparam integer LANE_BITS = DATA_BITS / 8;          // one burst beat: 8 bits per lane
    localparam [2:0] IDLE = 0, WRITE = 1, WRITE_DRAIN = 2, READ = 3, READ_DRAIN = 4, FLUSH = 5;
    reg [2:0] st = IDLE;

    function [63:0] xs64(input [63:0] x);
        reg [63:0] y;
        begin
            y = x ^ (x << 13);
            y = y ^ (y >> 7);
            xs64 = y ^ (y << 17);
        end
    endfunction

    // gen_w: the word being written; gen_r: the word expected at the next ack
    reg [DATA_BITS - 1:0] gen_w, gen_r;
    assign wb_data = gen_w;
    reg [1:0] mode_q = 0;
    reg [4:0] len_q = 0;
    reg [ADDR_BITS:0] issued = 0, acked = 0, total = 0;
    integer i, k;
    // Read compare, pipelined so the 576-bit read data is registered where it arrives and only a 72-bit
    // mask moves on: 1 capture, 2 XOR with the expected word, 3 fold the 8 beats, 4 count.
    reg ack1 = 0, ack2 = 0, ack3 = 0;
    reg [DATA_BITS - 1:0] rdata1, diff2;
    reg [LANE_BITS - 1:0] fold3;
    reg [ADDR_BITS:0] idx1 = 0, idx2 = 0, idx3 = 0;
    reg [2:0] flush = 0;
    reg [LANE_BITS - 1:0] fold;

    task step_gen(inout [DATA_BITS - 1:0] g);
        integer j;
        begin
            for (j = 0; j < SLICES; j = j + 1) g[64 * j +: 64] = xs64(g[64 * j +: 64]);
        end
    endtask

    always @(posedge clk) begin
        case (st)
        IDLE: begin
            wb_stb <= 0;
            if (start && ready) begin
                mode_q <= mode; len_q <= len;
                total <= {{ADDR_BITS{1'b0}}, 1'b1} << len;
                busy <= 1;
                write_cycles <= 0; read_cycles <= 0;
                st <= mode[0] ? WRITE : READ;
                issued <= 0; acked <= 0;
                for (i = 0; i < SLICES; i = i + 1) begin
                    gen_w[64 * i +: 64] <= {8'hA5 ^ seed, 24'h9E3779 + i, 8'h5A + seed, 24'h7F4A7C ^ (i * 24'h1357)};
                end
            end
        end
        WRITE: begin
            write_cycles <= write_cycles + 1;
            if (wb_ack) acked <= acked + 1;
            if (!wb_stb || !wb_stall) begin
                if (wb_stb) begin
                    issued <= issued + 1;
                    step_gen(gen_w);
                end
                if ((wb_stb ? issued + 1 : issued) == total) begin
                    wb_stb <= 0;
                    st <= WRITE_DRAIN;
                end else begin
                    wb_stb <= 1; wb_we <= 1;
                    wb_addr <= wb_stb ? issued[ADDR_BITS - 1:0] + 1'b1 : issued[ADDR_BITS - 1:0];
                end
            end
        end
        WRITE_DRAIN: begin
            write_cycles <= write_cycles + 1;
            if (wb_ack ? acked + 1 == total : acked == total) begin
                st <= mode_q[1] ? READ : IDLE;
                busy <= mode_q[1];
                issued <= 0; acked <= 0;
            end else if (wb_ack) acked <= acked + 1;
        end
        READ, READ_DRAIN: begin
            read_cycles <= read_cycles + 1;
            if (st == READ && (!wb_stb || !wb_stall)) begin
                if (wb_stb) issued <= issued + 1;
                if ((wb_stb ? issued + 1 : issued) == total) begin
                    wb_stb <= 0;
                    st <= READ_DRAIN;
                end else begin
                    wb_stb <= 1; wb_we <= 0;
                    wb_addr <= wb_stb ? issued[ADDR_BITS - 1:0] + 1'b1 : issued[ADDR_BITS - 1:0];
                end
            end
            if (wb_ack) begin
                acked <= acked + 1;
                if (acked + 1 == total) begin
                    st <= FLUSH;
                    wb_stb <= 0;
                end
            end
        end
        FLUSH: begin                        // let the last reads leave the compare pipeline
            read_cycles <= read_cycles + 1;
            if (flush == 7) begin
                st <= IDLE;
                busy <= 0;
            end
        end
        default: st <= IDLE;
        endcase
    end

    always @(posedge clk) begin
        ack1 <= wb_ack && (st == READ || st == READ_DRAIN);
        rdata1 <= wb_rdata;
        idx1 <= acked;
        ack2 <= ack1; idx2 <= idx1;
        if (ack1) begin
            diff2 <= rdata1 ^ gen_r;
            step_gen(gen_r);
        end
        ack3 <= ack2; idx3 <= idx2;
        fold = 0;
        for (k = 0; k < 8; k = k + 1) fold = fold | diff2[LANE_BITS * k +: LANE_BITS];
        fold3 <= ack2 ? fold : 0;
        if (ack3 && fold3 != 0) begin
            if (errors == 0) first_err_addr <= idx3;
            if (errors != 32'hFFFFFFFF) errors <= errors + 1;
            err_mask <= err_mask | fold3;
        end
        flush <= st == FLUSH ? flush + 1 : 0;
        if (st == IDLE && start && ready) begin
            errors <= 0; first_err_addr <= 0; err_mask <= 0;
            for (k = 0; k < SLICES; k = k + 1)
                gen_r[64 * k +: 64] <= {8'hA5 ^ seed, 24'h9E3779 + k, 8'h5A + seed, 24'h7F4A7C ^ (k * 24'h1357)};
        end
    end
endmodule
