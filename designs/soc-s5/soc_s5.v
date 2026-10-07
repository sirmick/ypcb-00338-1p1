// SoC roadmap S5c: the guest SoC on the card. CardS5 (soc/: one VexiiRiscv core, the devices, the host
// link) between the PCIe hard block (as designs/soc-s4) and UberDDR3 on channel A (as designs/uberddr3,
// 8 byte lanes, DDR3-667). Three clocks: the hard block's 62.5 MHz user clock, the SoC's 50 MHz and the
// DDR3 controller's 83.3 MHz, the last two from the DDR3 PLL. Device 10ee:0485, BAR0 64 KiB.
`default_nettype none
`timescale 1ns / 1ps

module soc_s5 (
  input  wire        clk50,
  input  wire        rst_n,
  output wire [2:0]  led,
  output wire        pci_exp_txp,
  output wire        pci_exp_txn,
  input  wire        pci_exp_rxp,
  input  wire        pci_exp_rxn,
  input  wire        sys_clk_p,
  input  wire        sys_clk_n,
  input  wire        sys_rst_n,
  output wire [0:0]  ddr3_ck_p,
  output wire [0:0]  ddr3_ck_n,
  output wire        ddr3_reset_n,
  output wire [0:0]  ddr3_cke,
  output wire [0:0]  ddr3_cs_n,
  output wire        ddr3_ras_n,
  output wire        ddr3_cas_n,
  output wire        ddr3_we_n,
  output wire [14:0] ddr3_addr,
  output wire [2:0]  ddr3_ba,
  inout  wire [63:0] ddr3_dq,
  inout  wire [7:0]  ddr3_dqs_p,
  inout  wire [7:0]  ddr3_dqs_n,
  output wire [0:0]  ddr3_odt
);
  // ---- clocks: the DDR3 PLL (designs/uberddr3's, plus a 50 MHz output for the SoC) ----
  wire controller_clk, ddr3_clk, ref_clk, ddr3_clk_90, ck_clk, soc_clk, clk_locked;
  clk_wiz clk_wiz_inst (
    .clk_in1(clk50), .clk_out1(controller_clk), .clk_out2(ddr3_clk), .clk_out3(ref_clk),
    .clk_out4(ddr3_clk_90), .clk_out5(ck_clk), .clk_out6(soc_clk), .reset(!rst_n), .locked(clk_locked));
  reg [7:0] soc_rst_cnt = 0;
  always @(posedge soc_clk) if (!clk_locked) soc_rst_cnt <= 0; else if (!soc_rst_cnt[7]) soc_rst_cnt <= soc_rst_cnt + 1;
  wire soc_reset = !soc_rst_cnt[7];

  // ---- DDR3 channel A ----
  localparam integer BYTE_LANES = 8;
  localparam integer WB_ADDR_BITS = 15 + 10 + 3 - 3;
  localparam integer WB_DATA_BITS = 8 * BYTE_LANES * 8;
  reg [6:0] idc_rst_cnt = 0;
  always @(posedge ref_clk) if (!(rst_n && clk_locked)) idc_rst_cnt <= 0; else if (!idc_rst_cnt[6]) idc_rst_cnt <= idc_rst_cnt + 1;
  wire idelayctrl_rdy;
  (* IODELAY_GROUP="DDR3-GROUP" *)
  IDELAYCTRL idelayctrl_inst (.RDY(idelayctrl_rdy), .REFCLK(ref_clk), .RST(!idc_rst_cnt[6]));
  wire calib_complete;
  wire [415:0] debug1;
  wire [BYTE_LANES-1:0] ddr3_dm_unused;
  wire wb_cyc, wb_stb, wb_we, wb_stall, wb_ack;
  wire [WB_ADDR_BITS-1:0] wb_addr;
  wire [WB_DATA_BITS-1:0] wb_wdata, wb_rdata;
  wire [WB_DATA_BITS/8-1:0] wb_sel;
  ddr3_top #(
    .CONTROLLER_CLK_PERIOD(12_000), .DDR3_CLK_PERIOD(3_000), .ROW_BITS(15), .COL_BITS(10), .BA_BITS(3),
    .BYTE_LANES(BYTE_LANES), .AUX_WIDTH(4), .WB2_ADDR_BITS(32), .WB2_DATA_BITS(32), .DUAL_RANK_DIMM(0),
    .MICRON_SIM(0), .ODELAY_SUPPORTED(0), .SECOND_WISHBONE(0), .DLL_OFF(0), .WB_ERROR(0), .BIST_MODE(1),
    .BIST_TEST_DATAMASK(0), .ECC_ENABLE(0), .SPEED_BIN(1), .SDRAM_CAPACITY(4)
  ) ddr3_top_inst (
    .i_controller_clk(controller_clk), .i_ddr3_clk(ddr3_clk), .i_ref_clk(ref_clk), .i_ddr3_clk_90(ddr3_clk_90),
    .i_ck_clk(ck_clk), .i_idelayctrl_rdy(idelayctrl_rdy), .i_rst_n(rst_n && clk_locked),
    .i_wb_cyc(wb_cyc), .i_wb_stb(wb_stb), .i_wb_we(wb_we), .i_wb_addr(wb_addr), .i_wb_data(wb_wdata),
    .i_wb_sel(wb_sel), .i_aux(4'b0), .o_wb_stall(wb_stall), .o_wb_ack(wb_ack), .o_wb_err(), .o_wb_data(wb_rdata),
    .o_aux(),
    .i_wb2_cyc(1'b0), .i_wb2_stb(1'b0), .i_wb2_we(1'b0), .i_wb2_addr(32'b0), .i_wb2_data(32'b0), .i_wb2_sel(4'b0),
    .o_wb2_stall(), .o_wb2_ack(), .o_wb2_data(),
    .o_ddr3_clk_p(ddr3_ck_p), .o_ddr3_clk_n(ddr3_ck_n), .o_ddr3_reset_n(ddr3_reset_n), .o_ddr3_cke(ddr3_cke),
    .o_ddr3_cs_n(ddr3_cs_n), .o_ddr3_ras_n(ddr3_ras_n), .o_ddr3_cas_n(ddr3_cas_n), .o_ddr3_we_n(ddr3_we_n),
    .o_ddr3_addr(ddr3_addr), .o_ddr3_ba_addr(ddr3_ba), .io_ddr3_dq(ddr3_dq), .io_ddr3_dqs(ddr3_dqs_p),
    .io_ddr3_dqs_n(ddr3_dqs_n), .o_ddr3_dm(ddr3_dm_unused), .o_ddr3_odt(ddr3_odt),
    .o_calib_complete(calib_complete), .o_debug1(debug1), .i_user_self_refresh(1'b0), .uart_tx()
  );
  // calibrated, and the controller's built-in self-test finished (state 23)
  wire ram_ready = calib_complete && (debug1[4:0] == 5'd23);
  reg ddr_reset_q = 1;
  always @(posedge controller_clk) ddr_reset_q <= !(rst_n && clk_locked);

  // ---- PCIe ----
  wire        user_clk, user_reset, user_lnk_up;
  wire        s_axis_tx_tready;
  wire [63:0] s_axis_tx_tdata;
  wire [7:0]  s_axis_tx_tkeep;
  wire        s_axis_tx_tlast, s_axis_tx_tvalid;
  wire [63:0] m_axis_rx_tdata;
  wire [7:0]  m_axis_rx_tkeep;
  wire        m_axis_rx_tlast, m_axis_rx_tvalid, m_axis_rx_tready;
  wire [21:0] m_axis_rx_tuser;
  wire        cfg_to_turnoff;
  wire [15:0] cfg_command;
  wire [7:0]  cfg_bus_number;
  wire [4:0]  cfg_device_number;
  wire [2:0]  cfg_function_number;
  wire [4:0]  gt_reset_fsm;
  wire [5:0]  pl_ltssm_state;
  wire        pipe_mmcm_lock;
  wire [255:0] dbg_block;
  wire        sys_clk, sys_rst_n_c;
  IBUF        sys_reset_n_ibuf (.O(sys_rst_n_c), .I(sys_rst_n));
  IBUFDS_GTE2 refclk_ibuf (.O(sys_clk), .ODIV2(), .I(sys_clk_p), .CEB(1'b0), .IB(sys_clk_n));
  pcie_7x #(
    .PCIE_GT_DEVICE("GTX"), .PCIE_USE_MODE("3.0"), .LINK_CAP_MAX_LINK_SPEED(4'h1), .LINK_CTRL2_TARGET_LINK_SPEED(4'h1),
    .LINK_STATUS_SLOT_CLOCK_CONFIG("TRUE"), .USER_CLK_FREQ(1), .CFG_DEV_ID(16'h0485), .BAR0(32'hFFFF0000),
    .DEV_CAP_MAX_PAYLOAD_SUPPORTED(1), .VC0_RX_RAM_LIMIT(13'h3FF), .VC0_TOTAL_CREDITS_CD(370),
    .VC0_TOTAL_CREDITS_CH(72), .VC0_TOTAL_CREDITS_NPH(4), .VC0_TOTAL_CREDITS_NPD(8), .VC0_TOTAL_CREDITS_PD(32),
    .VC0_TOTAL_CREDITS_PH(4), .VC0_TX_LASTPACKET(28)
  ) pcie_7x_i (
    .dbg_block(dbg_block),
    .pci_exp_txn(pci_exp_txn), .pci_exp_txp(pci_exp_txp), .pci_exp_rxn(pci_exp_rxn), .pci_exp_rxp(pci_exp_rxp),
    .pipe_mmcm_lock(pipe_mmcm_lock), .pipe_mmcm_rst_n(1'b1),
    .user_clk_out(user_clk), .user_reset_out(user_reset), .user_lnk_up(user_lnk_up),
    .s_axis_tx_tready(s_axis_tx_tready), .s_axis_tx_tdata(s_axis_tx_tdata), .s_axis_tx_tkeep(s_axis_tx_tkeep),
    .s_axis_tx_tuser(4'b0), .s_axis_tx_tlast(s_axis_tx_tlast), .s_axis_tx_tvalid(s_axis_tx_tvalid),
    .m_axis_rx_tdata(m_axis_rx_tdata), .m_axis_rx_tkeep(m_axis_rx_tkeep), .m_axis_rx_tlast(m_axis_rx_tlast),
    .m_axis_rx_tvalid(m_axis_rx_tvalid), .m_axis_rx_tready(m_axis_rx_tready), .m_axis_rx_tuser(m_axis_rx_tuser),
    .tx_cfg_gnt(1'b1), .rx_np_ok(1'b1), .rx_np_req(1'b1),
    .cfg_trn_pending(1'b0), .cfg_pm_halt_aspm_l0s(1'b0), .cfg_pm_halt_aspm_l1(1'b0),
    .cfg_pm_force_state_en(1'b0), .cfg_pm_force_state(2'b00), .cfg_dsn(64'h0000000101000a35),
    .cfg_turnoff_ok(cfg_to_turnoff), .cfg_pm_wake(1'b0),
    .cfg_interrupt(1'b0), .cfg_interrupt_rdy(), .cfg_interrupt_assert(1'b0), .cfg_interrupt_di(8'd0),
    .cfg_interrupt_do(), .cfg_interrupt_mmenable(), .cfg_interrupt_msienable(), .cfg_interrupt_msixenable(),
    .cfg_interrupt_msixfm(), .cfg_interrupt_stat(1'b0), .cfg_pciecap_interrupt_msgnum(5'd0),
    .cfg_status(), .cfg_command(cfg_command), .cfg_dstatus(), .cfg_lstatus(), .cfg_pcie_link_state(),
    .cfg_dcommand(), .cfg_lcommand(), .cfg_dcommand2(),
    .cfg_pmcsr_pme_en(), .cfg_pmcsr_powerstate(), .cfg_pmcsr_pme_status(), .cfg_received_func_lvl_rst(),
    .tx_buf_av(), .tx_err_drop(), .tx_cfg_req(),
    .cfg_to_turnoff(cfg_to_turnoff), .cfg_bus_number(cfg_bus_number), .cfg_device_number(cfg_device_number),
    .cfg_function_number(cfg_function_number),
    .sys_clk(sys_clk), .sys_rst_n(sys_rst_n_c),
    .gt_reset_fsm(gt_reset_fsm), .pl_ltssm_state(pl_ltssm_state), .pipe_txoutclk_out()
  );
  reg user_reset_q = 1;
  always @(posedge user_clk) user_reset_q <= user_reset;

  // ---- the card ----
  wire guest_reset;
  CardS5 card (
    .clk(soc_clk), .reset(soc_reset),
    .io_pcieClk(user_clk), .io_pcieReset(user_reset_q),
    .io_rx_valid(m_axis_rx_tvalid), .io_rx_ready(m_axis_rx_tready), .io_rx_payload_last(m_axis_rx_tlast),
    .io_rx_payload_fragment_data(m_axis_rx_tdata), .io_rx_payload_fragment_keep(m_axis_rx_tkeep),
    .io_tx_valid(s_axis_tx_tvalid), .io_tx_ready(s_axis_tx_tready), .io_tx_payload_last(s_axis_tx_tlast),
    .io_tx_payload_fragment_data(s_axis_tx_tdata), .io_tx_payload_fragment_keep(s_axis_tx_tkeep),
    .io_completerId({cfg_bus_number, cfg_device_number, cfg_function_number}),
    .io_busMaster(cfg_command[2]),
    .io_ddrClk(controller_clk), .io_ddrReset(ddr_reset_q),
    .io_wb_cyc(wb_cyc), .io_wb_stb(wb_stb), .io_wb_we(wb_we), .io_wb_addr(wb_addr), .io_wb_wdata(wb_wdata),
    .io_wb_sel(wb_sel), .io_wb_stall(wb_stall), .io_wb_ack(wb_ack), .io_wb_rdata(wb_rdata),
    .io_ramReady(ram_ready), .io_guestReset(guest_reset)
  );

  reg [31:0] soc_cycles = 0;
  always @(posedge soc_clk) soc_cycles <= soc_cycles + 1;
  assign led = {soc_cycles[25], ram_ready, user_lnk_up};

  // ---- JTAG USER1 debug register: 768 bits, LSB first, 32-bit words ----
  // 0 magic 32'h53354344 ("S5CD"), 1 status {calibration state[4:0], guest_reset, ram_ready, calib_complete,
  // cfg_command[2:1], pl_ltssm_state[5:0], clk_locked, 0, user_reset, user_lnk_up}, 2 SoC cycles,
  // (the PCIe MMCM's LOCKED is left out: nextpnr cannot route it out of its CMT in this placement)
  // 3 RX TLPs, 4 TX TLPs, 5 wishbone requests, 6 wishbone acks.
  reg [31:0] dbg_rx = 0, dbg_tx = 0, dbg_wb_req = 0, dbg_wb_ack = 0;
  always @(posedge user_clk) begin
    if (m_axis_rx_tvalid && m_axis_rx_tready && m_axis_rx_tlast) dbg_rx <= dbg_rx + 1;
    if (s_axis_tx_tvalid && s_axis_tx_tready && s_axis_tx_tlast) dbg_tx <= dbg_tx + 1;
  end
  always @(posedge controller_clk) begin
    if (wb_stb && !wb_stall) dbg_wb_req <= dbg_wb_req + 1;
    if (wb_ack) dbg_wb_ack <= dbg_wb_ack + 1;
  end
  wire dbg_capture, dbg_drck, dbg_shift, dbg_tdi;
  reg [767:0] dbg_sr = 0;
  wire [767:0] dbg_snap = {544'd0, dbg_wb_ack, dbg_wb_req, dbg_tx, dbg_rx, soc_cycles,
                           {12'd0, debug1[4:0], guest_reset, ram_ready, calib_complete, cfg_command[2:1],
                            pl_ltssm_state, clk_locked, 1'b0, user_reset, user_lnk_up},
                           32'h53354344};
  BSCANE2 #(.JTAG_CHAIN(1)) dbg_bscan (
    .CAPTURE(dbg_capture), .DRCK(dbg_drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(dbg_shift),
    .TCK(), .TDI(dbg_tdi), .TMS(), .UPDATE(), .TDO(dbg_sr[0]));
  always @(posedge dbg_drck)
    if (dbg_capture) dbg_sr <= dbg_snap;
    else if (dbg_shift) dbg_sr <= {dbg_tdi, dbg_sr[767:1]};
endmodule
