// SoC roadmap S4b: the host link on real PCIe. The PCIE_2_1 hard block (regymm/pcie_7x, vendored in
// designs/pcie-x1 and copied in by prebuild.py) feeds CardS4 (soc/: PcieLink and 64 KiB of block RAM
// standing in for guest memory). BAR0 is 64 KiB onto the mailbox; the card's DMA waits for Bus Master
// Enable. Device 10ee:0484.
//
// GEN2=0 (the default) trains a Gen1 link with a 62.5 MHz user clock; GEN2=1 trains Gen2 at 125 MHz.
`timescale 1ns / 1ps

module soc_s4 #(
  parameter GEN2 = `ifdef SOC_S4_GEN2 1 `else 0 `endif
) (
  output       pci_exp_txp,
  output       pci_exp_txn,
  input        pci_exp_rxp,
  input        pci_exp_rxn,
  input        sys_clk_p,
  input        sys_clk_n,
  input        sys_rst_n,
  input        clk_50,
  output [2:0] led
);
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
    .PCIE_GT_DEVICE                ("GTX"),
    .PCIE_USE_MODE                 ("3.0"),
    .LINK_CAP_MAX_LINK_SPEED       (GEN2 ? 4'h2 : 4'h1),
    .LINK_CTRL2_TARGET_LINK_SPEED  (GEN2 ? 4'h2 : 4'h1),
    .LINK_STATUS_SLOT_CLOCK_CONFIG ("TRUE"),
    .USER_CLK_FREQ                 (GEN2 ? 2 : 1),
    .CFG_DEV_ID                    (16'h0484),
    .BAR0                          (32'hFFFF0000),
    .DEV_CAP_MAX_PAYLOAD_SUPPORTED (1),
    .VC0_RX_RAM_LIMIT              (13'h3FF),
    .VC0_TOTAL_CREDITS_CD          (370),
    .VC0_TOTAL_CREDITS_CH          (72),
    .VC0_TOTAL_CREDITS_NPH         (4),
    .VC0_TOTAL_CREDITS_NPD         (8),
    .VC0_TOTAL_CREDITS_PD          (32),
    .VC0_TOTAL_CREDITS_PH          (4),
    .VC0_TX_LASTPACKET             (28)
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

  wire [7:0] irq;
  CardS4 card (
    .clk(user_clk), .reset(user_reset_q),
    .io_rx_valid(m_axis_rx_tvalid), .io_rx_ready(m_axis_rx_tready), .io_rx_payload_last(m_axis_rx_tlast),
    .io_rx_payload_fragment_data(m_axis_rx_tdata), .io_rx_payload_fragment_keep(m_axis_rx_tkeep),
    .io_tx_valid(s_axis_tx_tvalid), .io_tx_ready(s_axis_tx_tready), .io_tx_payload_last(s_axis_tx_tlast),
    .io_tx_payload_fragment_data(s_axis_tx_tdata), .io_tx_payload_fragment_keep(s_axis_tx_tkeep),
    .io_completerId({cfg_bus_number, cfg_device_number, cfg_function_number}),
    .io_busMaster(cfg_command[2]),
    .io_irq(irq)
  );

  reg [31:0] cnt = 0;
  always @(posedge user_clk) cnt <= cnt + 1;
  assign led = {cnt[25], user_lnk_up, pipe_mmcm_lock};

  // ---- JTAG USER1 debug register: 768 bits, LSB first, 32-bit words ----
  // 0 magic 32'h53344344 ("S4CD"), 1 status {irq[7:0], cfg_command[2:1], pl_ltssm_state[5:0],
  // gt_reset_fsm[4:0], pipe_mmcm_lock, user_reset, user_lnk_up}, 2 user_clk cycles, 3 RX TLPs, 4 TX TLPs,
  // 5 RX valid-but-not-ready cycles, 6-7 first beat of the last RX TLP, 8-9 first beat of the last TX TLP,
  // 10 TX valid-but-not-ready cycles, 16-23 pcie_block's dbg_block.
  reg [31:0] dbg_rx = 0, dbg_tx = 0, dbg_rx_stall = 0, dbg_tx_stall = 0;
  reg [63:0] dbg_rx_hdr = 0, dbg_tx_hdr = 0;
  reg dbg_rx_sop = 1, dbg_tx_sop = 1;
  always @(posedge user_clk) begin
    if (m_axis_rx_tvalid && m_axis_rx_tready) begin
      if (dbg_rx_sop) dbg_rx_hdr <= m_axis_rx_tdata;
      dbg_rx_sop <= m_axis_rx_tlast;
      if (m_axis_rx_tlast) dbg_rx <= dbg_rx + 1;
    end
    if (m_axis_rx_tvalid && !m_axis_rx_tready) dbg_rx_stall <= dbg_rx_stall + 1;
    if (s_axis_tx_tvalid && s_axis_tx_tready) begin
      if (dbg_tx_sop) dbg_tx_hdr <= s_axis_tx_tdata;
      dbg_tx_sop <= s_axis_tx_tlast;
      if (s_axis_tx_tlast) dbg_tx <= dbg_tx + 1;
    end
    if (s_axis_tx_tvalid && !s_axis_tx_tready) dbg_tx_stall <= dbg_tx_stall + 1;
  end
  wire dbg_capture, dbg_drck, dbg_shift, dbg_tdi;
  reg [767:0] dbg_sr = 0;
  wire [767:0] dbg_snap = {dbg_block, 160'd0, dbg_tx_stall, dbg_tx_hdr, dbg_rx_hdr, dbg_rx_stall, dbg_tx, dbg_rx, cnt,
                           {8'd0, irq, cfg_command[2:1], pl_ltssm_state, gt_reset_fsm, pipe_mmcm_lock, user_reset,
                            user_lnk_up},
                           32'h53344344};
  BSCANE2 #(.JTAG_CHAIN(1)) dbg_bscan (
    .CAPTURE(dbg_capture), .DRCK(dbg_drck), .RESET(), .RUNTEST(), .SEL(), .SHIFT(dbg_shift),
    .TCK(), .TDI(dbg_tdi), .TMS(), .UPDATE(), .TDO(dbg_sr[0]));
  always @(posedge dbg_drck)
    if (dbg_capture) dbg_sr <= dbg_snap;
    else if (dbg_shift) dbg_sr <= {dbg_tdi, dbg_sr[767:1]};
endmodule
