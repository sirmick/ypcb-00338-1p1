#!/usr/bin/env python3
"""Bring in UberDDR3's YPCB-00338-1P1 example for our openXC7 flow (gate 9).

UberDDR3 is GPL-3.0, so none of it is kept in this repository: this copies it from a local checkout
(UBERDDR3_SRC, default ~/fpga/src/UberDDR3, expected at commit 79d8fd3) into the build dir, and inserts
a JTAG USER1 debug register into the copied top level. The patched file exists only in the build dir.

USER1 (1024 bits, LSB first, 32-bit words): 0 magic 32'h55424433 ("UBD3"),
1 {..., max_state[4:0], state[4:0], calib_complete, clk_locked} (state = state_calibrate; 23 = done),
2 controller-clock cycles since reset, 3 {dual build, channel shown},
4-8 gray counters of clk50, controller_clk, ddr3_clk, ref_clk, ddr3_clk_90,
9 {..., lane 8 resets[3:0], state at last reset[4:0], lane at last reset[3:0], lane being calibrated[3:0],
state at last BIST error[4:0]}, 10 BIST correct reads, 11 BIST wrong reads,
12 calibration {odelay_dq, odelay_dqs, idelay_dq, idelay_dqs (5 bits each), data_start_index[6:0],
added_read_pipe, write_dq_late, read_dq_early, write_level_fail}, 13-14 that lane's 8 read-back bytes,
15 {calibration resets[23:0], dq_target_index[7:0]}, 16 resets per lane 7..0 (4 bits each, saturating).
Words 12-15 describe the lane at the last calibration reset, or lane 0 live if there was none.
17-21: per lane (16 bits each, lane 0 lowest) {odelay_dqs, idelay_dq, idelay_dqs}, live.
22-31, memtest.v: 22 {..., channel 1 busy, seq_done[7:0], busy}, 23 errors, 24 first failing word address, 25-27 DQ lines
that ever failed (bit lane*8+n), 28 write-pass cycles, 29 read-pass cycles, 30 control {seed, channel, mode, len, seq}.
Writing USER1 with bits 31:24 = 8'h4D sets the memtest control (see memtest.v and memtest.py).
"""
import glob, os, re, shutil, subprocess, sys

src = os.path.expanduser(os.environ.get("UBERDDR3_SRC", "~/fpga/src/UberDDR3"))
out = sys.argv[1]
ex = os.path.join(src, "example_demo", "ypcb_00338_1p1")
rev = subprocess.run(["git", "-C", src, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
# Widen o_debug1 to 256 bits through the copies (build dir only) to carry the BIST's first-hand error
# evidence and lane 0's calibration results (see the USER1 layout above).
def sub(text, a, b):
    assert a in text, a
    return text.replace(a, b)
ctl = open(os.path.join(src, "rtl/ddr3_controller.v")).read()
ctl = sub(ctl, "output\twire\t[31:0]\to_debug1,", "output\twire\t[415:0]\to_debug1,")
ctl = sub(ctl, "expected_data <= correct_data;\n",
          "expected_data <= correct_data;\n                        dbg_err_state <= state_calibrate; dbg_err_addr <= check_test_address_counter;\n")
ctl = sub(ctl, "    assign o_debug1 = {27'd0, state_calibrate[4:0]};", """
    reg [4:0] dbg_err_state = 0;
    wire [3:0] dbg_lane = lane;
    reg [31:0] dbg_err_addr = 0;
    // At each calibration reset: the lane being calibrated, the state, that lane's results and its
    // eight read-back bytes, and a per-lane reset count. Once calibrated with no resets: lane 0, live.
    reg dbg_rfc_q = 0;
    reg [23:0] dbg_cal_resets = 0;
    reg [30:0] dbg_cal = 0;
    reg [63:0] dbg_rds = 0;
    reg [7:0] dbg_dqt = 0;
    reg [3:0] dbg_rst_lane = 0;
    reg [4:0] dbg_rst_state = 0;
    reg [3:0] dbg_lane_resets [0:15];
    integer dbg_k;
    initial for (dbg_k = 0; dbg_k < 16; dbg_k = dbg_k + 1) dbg_lane_resets[dbg_k] = 0;
    wire dbg_rst_edge = reset_from_calibrate && !dbg_rfc_q;
    wire [3:0] dbg_sel = dbg_rst_edge ? dbg_lane : 4'd0;
    always @(posedge i_controller_clk) begin
        dbg_rfc_q <= reset_from_calibrate;
        if (dbg_rst_edge || (state_calibrate == DONE_CALIBRATE && dbg_cal_resets == 0)) begin
            dbg_cal <= {odelay_data_cntvaluein[dbg_sel], odelay_dqs_cntvaluein[dbg_sel], idelay_data_cntvaluein[dbg_sel],
                        idelay_dqs_cntvaluein[dbg_sel], data_start_index[dbg_sel][6:0], added_read_pipe[dbg_sel],
                        lane_write_dq_late[dbg_sel], lane_read_dq_early[dbg_sel], write_level_fail[dbg_sel]};
            for (dbg_k = 0; dbg_k < 8; dbg_k = dbg_k + 1)
                dbg_rds[8 * dbg_k +: 8] <= read_data_store[(DQ_BITS * LANES) * dbg_k + 8 * dbg_sel +: 8];
            dbg_dqt <= dq_target_index[dbg_sel];
        end
        if (dbg_rst_edge) begin
            dbg_cal_resets <= dbg_cal_resets + 1;
            dbg_rst_lane <= dbg_lane;
            dbg_rst_state <= state_calibrate;
            if (dbg_lane_resets[dbg_lane] != 4'hF) dbg_lane_resets[dbg_lane] <= dbg_lane_resets[dbg_lane] + 1;
        end
    end
    wire [31:0] dbg_lr_lo = {dbg_lane_resets[7], dbg_lane_resets[6], dbg_lane_resets[5], dbg_lane_resets[4],
                             dbg_lane_resets[3], dbg_lane_resets[2], dbg_lane_resets[1], dbg_lane_resets[0]};
    // Every lane's write-leveling (odelay_dqs) and read delays, live: {odelay_dqs, idelay_dq, idelay_dqs}
    wire [159:0] dbg_lanes;
    genvar dbg_g;
    generate for (dbg_g = 0; dbg_g < 10; dbg_g = dbg_g + 1) begin : dbg_ln
        if (dbg_g < LANES) assign dbg_lanes[16 * dbg_g +: 16] = {1'b0, odelay_dqs_cntvaluein[dbg_g],
                                                                  idelay_data_cntvaluein[dbg_g], idelay_dqs_cntvaluein[dbg_g]};
        else assign dbg_lanes[16 * dbg_g +: 16] = 16'd0;
    end endgenerate
    assign o_debug1 = {dbg_lanes, dbg_lr_lo, dbg_cal_resets, dbg_dqt, dbg_rds, 1'b0, dbg_cal, wrong_read_data, correct_read_data,
                       5'd0, dbg_lane_resets[8], dbg_rst_state, dbg_rst_lane, dbg_lane, dbg_err_state, state_calibrate[4:0]};""")
open(os.path.join(out, "gen_uber0.v"), "w").write(ctl)
# Without ODELAY (HR banks, as on this card) the PHY clocks DQS and the DDR3 CK output from
# !i_ddr3_clk. yosys builds that inversion as a fabric LUT, so CK and DQS arrive 0.6-1.2 ns late,
# by an amount that changes with placement: DQ (on a global clock) then lands one beat off in
# some builds. Invert inside the I/O tile instead: IS_CLK_INVERTED on the DQS OSERDES, and the CK
# output generated by an ODDR (D1=0, D2=1 is !clk) on the plain clock.
phy = open(os.path.join(src, "rtl/ddr3_phy.v")).read()
i = phy.index(".CLK(!i_ddr3_clk)")
j = phy.rindex("#(", 0, i)
phy = phy[:j + 2] + "\n                    .IS_CLK_INVERTED(1'b1)," + phy[j + 2:i] + ".CLK(i_ddr3_clk)" + phy[i + len(".CLK(!i_ddr3_clk)"):]
assert ".CLK(!i_ddr3_clk)" not in phy
assert phy.count(".I(!i_ddr3_clk)") == 3
phy = phy.replace(".I(!i_ddr3_clk)", ".I(ck_oddr)")
k = phy.index("    //synchronous reset\n")
phy = phy[:k] + """    // added by prebuild.py: the DDR3 CK output from an ODDR, not a fabric inverter (single rank only)
    wire ck_oddr;
    ODDR #(.DDR_CLK_EDGE("SAME_EDGE"), .INIT(1'b0), .SRTYPE("SYNC")) ck_oddr_inst (
        .Q(ck_oddr), .C(i_ck_clk), .CE(1'b1), .D1(1'b0), .D2(1'b1), .R(1'b0), .S(1'b0));

""" + phy[k:]
# CK gets its own PLL output, UBER_CK_PHASE degrees (a multiple of 15) after i_ddr3_clk. Without ODELAY
# there is no write leveling, so one CK offset has to suit all nine chips on the fly-by route. Measured
# on the card (9 lanes, 2 loads each): -60 fails, -30 and 0 calibrate only after retries (lanes 4, 7, 8),
# +30, +60, +90 calibrate first time, +120 and +150 fail. +60 is the centre. UBER_CK_PHASE=0: CK on i_ddr3_clk.
ck_phase = os.environ.get("UBER_CK_PHASE", "60")
ck_phase = None if float(ck_phase) == 0 else ck_phase
dec = "        input wire i_ddr3_clk_90, //required only when ODELAY_SUPPORTED is zero\n"
phy = sub(phy, dec, dec + "        input wire i_ck_clk,\n        input wire i_idelayctrl_rdy,\n")
# nextpnr allows one IDELAYCTRL per design (it copies it to every bank with delays): it lives in the top
# level, and each PHY takes its ready signal from a port
i = phy.rindex("    (* IODELAY_GROUP=\"DDR3-GROUP\" *)", 0, phy.index("IDELAYCTRL IDELAYCTRL_inst ("))
j = phy.index("// End of IDELAYCTRL_inst instantiation", i)
phy = phy[:i] + "    assign idelayctrl_rdy = i_idelayctrl_rdy; // added by prebuild.py: IDELAYCTRL is in the top level\n    " + phy[j:]
open(os.path.join(out, "gen_uber1.v"), "w").write(phy)
t = sub(open(os.path.join(src, "rtl/ddr3_top.v")).read(), "output wire[31:0] o_debug1,", "output wire[415:0] o_debug1,")
dec = "        input wire i_ddr3_clk_90, //required only when ODELAY_SUPPORTED is zero\n"
t = sub(t, dec, dec + "        input wire i_ck_clk,\n        input wire i_idelayctrl_rdy,\n")
t, n = re.subn(r"(\n\s*)\.i_ddr3_clk_90\(i_ddr3_clk_90\), *\n",
              r"\1.i_ddr3_clk_90(i_ddr3_clk_90),\1.i_ck_clk(i_ck_clk),\1.i_idelayctrl_rdy(i_idelayctrl_rdy),\n", t)
assert n == 2, n
open(os.path.join(out, "gen_uber2.v"), "w").write(t)
cw = open(os.path.join(ex, "clk_wiz.v")).read()
if ck_phase:
    cw = sub(cw, "    .CLKIN1_PERIOD", f"    .CLKOUT4_DIVIDE       (3),\n    .CLKOUT4_PHASE        ({float(ck_phase):.3f}),\n"
             "    .CLKOUT4_DUTY_CYCLE   (0.500),\n    .CLKIN1_PERIOD")
    cw = sub(cw, "  output wire        clk_out4,", "  output wire        clk_out4,\n  output wire        clk_out5,")
    cw = sub(cw, "    .CLKOUT3             (clk_out4_clk_wiz_0),", "    .CLKOUT3             (clk_out4_clk_wiz_0),\n    .CLKOUT4             (clk_out5_pll),")
    cw = sub(cw, "endmodule", "  wire clk_out5_pll;\n  BUFG clkout5_buf (.O(clk_out5), .I(clk_out5_pll));\nendmodule")
open(os.path.join(out, "gen_uber_clkwiz.v"), "w").write(cw)
top = sub(open(os.path.join(ex, "ypcb_00338_1p1_ddr3.v")).read(), "wire [31:0] debug1;", "wire [415:0] debug1;")
# Byte lanes: the example uses 1; channel 0 has 8 data lanes plus the ECC lane (DQ 64-71, DQS 8)
lanes = int(os.environ.get("UBER_LANES", "9"))
top = sub(top, "        .i_ddr3_clk_90(ddr3_clk_90),", "        .i_ddr3_clk_90(ddr3_clk_90),\n        .i_ck_clk(%s),\n"
          "        .i_idelayctrl_rdy(idelayctrl_rdy)," % ("ck_clk" if ck_phase else "ddr3_clk"))
top = sub(top, "    clk_wiz clk_wiz_inst (", """    // The one IDELAYCTRL, held in reset for 64 reference cycles after the PLL locks
    reg [6:0] idc_rst_cnt = 0;
    always @(posedge ref_clk) if (!(rst_n && clk_locked)) idc_rst_cnt <= 0; else if (!idc_rst_cnt[6]) idc_rst_cnt <= idc_rst_cnt + 1;
    wire idelayctrl_rdy;
    (* IODELAY_GROUP="DDR3-GROUP" *)
    IDELAYCTRL idelayctrl_inst (.RDY(idelayctrl_rdy), .REFCLK(ref_clk), .RST(!idc_rst_cnt[6]));

    clk_wiz clk_wiz_inst (""")
if ck_phase:
    top = sub(top, "        .clk_out4(ddr3_clk_90),", "        .clk_out4(ddr3_clk_90),\n        .clk_out5(ck_clk),")
    top = sub(top, "    wire ddr3_clk_90;", "    wire ddr3_clk_90;\n    wire ck_clk;")
top = sub(top, "localparam integer BYTE_LANES = 1;", f"localparam integer BYTE_LANES = {lanes};")
# The user Wishbone port goes to memtest.v (this directory), driven from JTAG
for a, b in [(".i_wb_stb(1'b0),", ".i_wb_stb(mt_stb),"), (".i_wb_we(1'b0),", ".i_wb_we(mt_we),"),
             (".i_wb_addr({WB_ADDR_BITS{1'b0}}),", ".i_wb_addr(mt_addr),"),
             (".i_wb_data({WB_DATA_BITS{1'b0}}),", ".i_wb_data(mt_wdata),"),
             (".o_wb_stall(),", ".o_wb_stall(mt_stall),"), (".o_wb_ack(),", ".o_wb_ack(mt_ack),"),
             (".o_wb_data(),", ".o_wb_data(mt_rdata),")]:
    top = sub(top, a, b)
top = sub(top, "    wire bist_done =", """    wire mt_stb, mt_we, mt_stall, mt_ack;
    wire [WB_ADDR_BITS-1:0] mt_addr;
    wire [WB_DATA_BITS-1:0] mt_wdata, mt_rdata;
    wire bist_done =""")
top = sub(top, "inout  wire [63:0] ddr3_dq,", f"inout  wire [{8 * lanes - 1}:0] ddr3_dq,")
top = sub(top, "inout  wire [7:0]  ddr3_dqs_p,", f"inout  wire [{lanes - 1}:0]  ddr3_dqs_p,")
top = sub(top, "inout  wire [7:0]  ddr3_dqs_n,", f"inout  wire [{lanes - 1}:0]  ddr3_dqs_n,")
top = sub(top, ".io_ddr3_dq(ddr3_dq[7:0]),", f".io_ddr3_dq(ddr3_dq[{8 * lanes - 1}:0]),")
top = sub(top, ".io_ddr3_dqs(ddr3_dqs_p[0:0]),", f".io_ddr3_dqs(ddr3_dqs_p[{lanes - 1}:0]),")
top = sub(top, ".io_ddr3_dqs_n(ddr3_dqs_n[0:0]),", f".io_ddr3_dqs_n(ddr3_dqs_n[{lanes - 1}:0]),")
# UBER_DUAL=1: both channels, two controllers on the shared clocks. Channel 1 is a copy of the
# controller instance with its ports, wires and memtest renamed (ddr3b_*, *_b, mtb_*).
dual = os.environ.get("UBER_DUAL", "0") == "1"
if dual:
    ports = re.findall(r"\n(    (?:output|inout) +wire[^\n]*\bddr3_\w+,)", top)
    top = sub(top, ports[-1], ports[-1] + "".join("\n" + l.replace("ddr3_", "ddr3b_") for l in ports))
    i0 = top.index("    ddr3_top #("); i1 = top.index(");\n", top.index(") ddr3_top_inst (")) + 3
    inst = top[i0:i1]
    for x, y in [("ddr3_top_inst (", "ddr3_top_inst_b ("), ("(calib_complete)", "(calib_complete_b)"),
                 ("(debug1)", "(debug1_b)"), ("(uart_tx_unused)", "(uart_tx_unused_b)"),
                 ("(ddr3_dm_unused)", "(ddr3_dm_unused_b)"), ("(mt_", "(mtb_")]:
        inst = inst.replace(x, y)
    # only the DDR3 pins move to channel 1's ports: the clocks (ddr3_clk, ddr3_clk_90) stay shared
    port_names = {re.search(r"\bddr3_(\w+),", l).group(1) for l in ports}
    inst = re.sub(r"\(ddr3_(\w+)", lambda m: "(ddr3b_" + m.group(1) if m.group(1) in port_names else m.group(0), inst)
    used = set(re.findall(r"\(ddr3b_(\w+)", inst))
    assert used <= port_names, f"channel 1 connects to undeclared ports: {used - port_names}"
    assert "(ddr3_clk)" in inst and "(ddr3_clk_90)" in inst
    top = top[:i1] + "\n" + inst + top[i1:]
    top = sub(top, "    wire mt_stb, mt_we, mt_stall, mt_ack;", """    wire calib_complete_b;
    wire [415:0] debug1_b;
    wire uart_tx_unused_b;
    wire [BYTE_LANES-1:0] ddr3_dm_unused_b;
    wire mtb_stb, mtb_we, mtb_stall, mtb_ack;
    wire [WB_ADDR_BITS-1:0] mtb_addr;
    wire [WB_DATA_BITS-1:0] mtb_wdata, mtb_rdata;
    wire bist_done_b = calib_complete_b && (debug1_b[4:0] == 5'd23);
    wire mt_stb, mt_we, mt_stall, mt_ack;""")
    top = sub(top, "    assign led[0] = bist_done;\n    assign led[1] = !bist_done;",
              "    assign led[0] = bist_done;\n    assign led[1] = bist_done_b;")
    chan_b = ""
else:
    chan_b = """    wire [415:0] debug1_b = debug1;
    wire calib_complete_b = calib_complete;
    wire bist_done_b = bist_done;
"""
memtest_inst = lambda n, ready, start: f"""    memtest #(.ADDR_BITS(WB_ADDR_BITS), .DATA_BITS(WB_DATA_BITS)) {n} (
        .clk(controller_clk), .ready({ready}), .start({start}), .mode(mt_c2[14:13]), .len(mt_c2[12:8]),
        .seed(mt_c2[23:16]), .busy({n}_busy), .errors({n}_errors), .first_err_addr({n}_first), .err_mask({n}_mask),
        .write_cycles({n}_wcyc), .read_cycles({n}_rcyc), .wb_stb({n}_stb), .wb_we({n}_we), .wb_addr({n}_addr),
        .wb_data({n}_wdata), .wb_stall({n}_stall), .wb_ack({n}_ack), .wb_rdata({n}_rdata));
    wire {n}_busy;
    wire [31:0] {n}_errors, {n}_first, {n}_wcyc, {n}_rcyc;
    wire [WB_DATA_BITS/8-1:0] {n}_mask;
"""
dbg = """
    // ---- added by prebuild.py: JTAG USER1 debug register (not part of UberDDR3) ----
""" + chan_b + """    // Control: a USER1 write whose bits 31:24 are 8'h4D ("M") sets {seed[7:0], channel, mode[1:0], len[4:0],
    // seq[7:0]} from bits 23:0. `channel` picks what the register shows and where a run goes; a new seq starts
    // a run. Reads shift in zeros and are ignored.
    wire dbg_capture, dbg_drck, dbg_shift, dbg_tdi, dbg_update;
    reg [1023:0] dbg_sr = 0;
    reg [23:0] mt_ctrl_tck = 0;
    always @(posedge dbg_update) if (dbg_sr[31:24] == 8'h4D) mt_ctrl_tck <= dbg_sr[23:0];
    reg [23:0] mt_c1 = 0, mt_c2 = 0;
    reg [7:0] mt_seq_done = 0;
    reg mt_start = 0, mtb_start = 0;
    wire dsel = mt_c2[15];
    always @(posedge controller_clk) begin
        mt_c1 <= mt_ctrl_tck; mt_c2 <= mt_c1;
        mt_start <= 0; mtb_start <= 0;
        if (mt_c2[7:0] != mt_seq_done && !mt_busy && !mtb_busy && (dsel ? bist_done_b : bist_done)) begin
            mt_seq_done <= mt_c2[7:0];
            if (dsel) mtb_start <= 1; else mt_start <= 1;
        end
    end
""" + memtest_inst("mt", "bist_done", "mt_start") + (memtest_inst("mtb", "bist_done_b", "mtb_start") if dual else """    wire mtb_busy = 1'b0;
    wire [31:0] mtb_errors = 0, mtb_first = 0, mtb_wcyc = 0, mtb_rcyc = 0;
    wire [WB_DATA_BITS/8-1:0] mtb_mask = 0;
""") + """    wire [95:0] mt_mask96 = dsel ? mtb_mask : mt_mask;
    wire [319:0] mt_snap = {32'd0, {8'd0, mt_c2}, dsel ? mtb_rcyc : mt_rcyc, dsel ? mtb_wcyc : mt_wcyc, mt_mask96,
                            dsel ? mtb_first : mt_first, dsel ? mtb_errors : mt_errors,
                            {22'd0, mtb_busy, mt_seq_done, dsel ? mtb_busy : mt_busy}};
    // The selected channel's controller state
    wire [415:0] d_debug1 = dsel ? debug1_b : debug1;
    wire d_calib = dsel ? calib_complete_b : calib_complete;
    reg [4:0] dbg_max_state = 0, dbg_max_state_b = 0;
    reg [31:0] dbg_cycles = 0;
    always @(posedge controller_clk) begin
        dbg_cycles <= dbg_cycles + 1;
        if (debug1[4:0] > dbg_max_state) dbg_max_state <= debug1[4:0];
        if (debug1_b[4:0] > dbg_max_state_b) dbg_max_state_b <= debug1_b[4:0];
    end
    // gray counters for every clock, measured against the 50 MHz input
    wire [31:0] dbg_g [0:4];
    wire [4:0] dbg_ck = {ddr3_clk_90, ref_clk, ddr3_clk, controller_clk, clk50};
    genvar dbg_i;
    generate for (dbg_i = 0; dbg_i < 5; dbg_i = dbg_i + 1) begin : dbg_cnt
        reg [31:0] b = 0, g = 0;
        always @(posedge dbg_ck[dbg_i]) begin b <= b + 1; g <= b ^ (b >> 1); end
        assign dbg_g[dbg_i] = g;
    end endgenerate
    wire [127:0] dbg_snap_lo = {""" + ("32'd2" if dual else "32'd0") + """ | {31'd0, dsel}, dbg_cycles,
        {20'd0, dsel ? dbg_max_state_b : dbg_max_state, d_debug1[4:0], d_calib, clk_locked}, 32'h55424433};
    wire [1023:0] dbg_snap = {mt_snap, d_debug1, dbg_g[4], dbg_g[3], dbg_g[2], dbg_g[1], dbg_g[0], dbg_snap_lo};
    BSCANE2 #(.JTAG_CHAIN(1)) dbg_bscan (
        .CAPTURE(dbg_capture), .DRCK(dbg_drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(dbg_shift),
        .TCK(), .TDI(dbg_tdi), .TMS(), .UPDATE(dbg_update), .TDO(dbg_sr[0]));
    always @(posedge dbg_drck)
        if (dbg_capture) dbg_sr <= dbg_snap;
        else if (dbg_shift) dbg_sr <= {dbg_tdi, dbg_sr[1023:1]};
"""
i = top.rindex("endmodule")
open(os.path.join(out, "gen_uber_top.v"), "w").write(top[:i] + dbg + top[i:])
keep = re.compile(r"^set_property (LOC|PACKAGE_PIN|IOSTANDARD|SLEW|IN_TERM) \S+ \[get_ports \{?[^ {}]+\}?\]\s*$")
xdc = [l for l in open(os.path.join(ex, "ypcb_00338_1p1_ddr3.xdc")) if keep.match(l)]
def in_use(l):
    m = re.search(r"ddr3_(dq|dqs_[pn])\[(\d+)\]", l)
    return not m or int(m.group(2)) < (8 * lanes if m.group(1) == "dq" else lanes)
xdc = [l for l in xdc if in_use(l)]
# Pin locations come from the board's MIG pin file for UBER_CHANNEL (0 or 1): the example's XDC is
# channel 0 and stops at lane 7. Port names without an index (ddr3_ck_p) are [0] in the pin file.
channel = int(os.environ.get("UBER_CHANNEL", "0"))
ucf = {m.group(1): m.group(2) for l in open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
       f"../../board/ypcb-00338-1p1/MEMORY_CH{channel}.ucf")) for m in [re.search(r'NET\s+"([^"]+)"\s+LOC = "(\w+)"', l)] if m}
def relocate(l):
    m = re.match(r"^set_property (LOC|PACKAGE_PIN) \S+ \[get_ports \{?(ddr3_[^ {}]+?)\}?\]\s*$", l)
    if not m:
        return l
    n = m.group(2)
    pin = ucf.get(n) or ucf[n + "[0]"]
    return f"set_property LOC {pin} [get_ports {{{n}}}]\n"
xdc = [relocate(l) for l in xdc]
extra = [f"ddr3_dq[{i}]" for i in range(64, 8 * lanes)] + [f"ddr3_dqs_{pn}[{i}]" for i in range(8, lanes) for pn in "pn"]
for n in extra:
    std = "DIFF_SSTL15" if "dqs" in n else "SSTL15"
    xdc += [f"set_property LOC {ucf[n]} [get_ports {{{n}}}]\n", f"set_property IOSTANDARD {std} [get_ports {{{n}}}]\n",
            f"set_property SLEW FAST [get_ports {{{n}}}]\n", f"set_property IN_TERM UNTUNED_SPLIT_40 [get_ports {{{n}}}]\n"]
if dual:
    ucf = {m.group(1): m.group(2) for l in open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
           "../../board/ypcb-00338-1p1/MEMORY_CH1.ucf")) for m in [re.search(r'NET\s+"([^"]+)"\s+LOC = "(\w+)"', l)] if m}
    xdc += [relocate(l).replace("{ddr3_", "{ddr3b_") for l in xdc if "{ddr3_" in l]
open(os.path.join(out, "gen_uber.xdc"), "w").writelines(xdc)
print(f"uberddr3 {rev}: " + ("both channels" if dual else f"channel {channel}") + f", {lanes} byte lanes, CK phase {ck_phase or 0}, {len(xdc)} XDC lines" + ("" if rev.startswith("79d8fd3") else "  (WARNING: expected 79d8fd3)"))
