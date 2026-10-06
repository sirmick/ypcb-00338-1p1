#!/usr/bin/env python3
"""Gate 9 (UberDDR3) check: where is calibration? state 23 = done (and BIST passed in this example)."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import read_dr, xfer_dr, words  # noqa: E402
NAMES = {0: "IDLE", 1: "BITSLIP_DQS_TRAIN_1", 2: "MPR_READ", 3: "COLLECT_DQS", 4: "ANALYZE_DQS",
         5: "CALIBRATE_DQS", 6: "BITSLIP_DQS_TRAIN_2", 7: "START_WRITE_LEVEL", 8: "WAIT_FOR_FEEDBACK",
         9: "ISSUE_WRITE_1", 10: "ISSUE_WRITE_2", 11: "ISSUE_READ", 12: "READ_DATA", 13: "ANALYZE_DATA",
         14: "CHECK_STARTING_DATA", 15: "BITSLIP_DQS_TRAIN_3", 17: "BURST_WRITE", 18: "BURST_READ",
         19: "RANDOM_WRITE", 20: "RANDOM_READ", 21: "ALTERNATE_WRITE_READ", 22: "FINISH_READ",
         23: "DONE_CALIBRATE", 24: "ANALYZE_DATA_LOW_FREQ"}
channel = int(sys.argv[1]) if len(sys.argv) > 1 else 0
if True:      # select the channel to show (dual-channel builds); a write with the current seq starts no run
    seq = (words(read_dr(bits=1024)[0][1], 32)[22] >> 1) & 0xFF
    xfer_dr([(0x4D << 24) | (channel << 15) | seq], 1024)
(t0, a), (t1, b) = read_dr(bits=1024, count=2, gap_ms=1000)
w, wa = words(b, 32), words(a, 32)
if w[0] != 0x55424433:
    sys.exit("magic %08x != 55424433: is designs/uberddr3 loaded?" % w[0])
st = w[1]
if w[3] & 2:
    print(f"dual-channel build, showing channel {w[3] & 1}")
st = w[1]
state, mx = (st >> 2) & 0x1F, (st >> 7) & 0x1F
print(f"clk_locked={st & 1} calib_complete={(st >> 1) & 1}  controller clock "
      f"{((w[2] - wa[2]) & 0xFFFFFFFF) / ((t1 - t0) * 1000):.2f} MHz")
from jtagdr import gray_to_bin  # noqa: E402
d = [(gray_to_bin(y) - gray_to_bin(x)) & 0xFFFFFFFF for x, y in zip(wa[4:9], w[4:9])]
if d[0]:
    print("clocks vs 50 MHz input: " + ", ".join(f"{n} {v / d[0] * 50:.2f} MHz" for n, v in
          zip(("controller", "ddr3", "ref (IDELAYCTRL)", "ddr3_90"), d[1:])))
print(f"state {state} ({NAMES.get(state, '?')}), furthest reached {mx} ({NAMES.get(mx, '?')})")
c = w[12]
c, nres = w[12], w[15] >> 8
print(f"BIST reads: {w[10]} correct, {w[11]} wrong")
if nres:
    per = [(w[16] >> 4 * k) & 15 for k in range(8)] + [(w[9] >> 23) & 15]
    print(f"calibration resets {nres}; per lane 0-8 (saturating at 15): {per}; last in lane {(w[9] >> 14) & 15}, "
          f"state {(w[9] >> 18) & 31} ({NAMES.get((w[9] >> 18) & 31, '?')})")
who = f"lane {(w[9] >> 14) & 15} at its last reset" if nres else "lane 0"
print(f"{who}: odelay dq {c >> 26 & 31} dqs {c >> 21 & 31}, idelay dq {c >> 16 & 31} dqs {c >> 11 & 31}, "
      f"data_start_index {c >> 4 & 127}, added_read_pipe {c >> 3 & 1}, write_dq_late {c >> 2 & 1}, "
      f"read_dq_early {c >> 1 & 1}, write_level_fail {c & 1}, dq_target_index {w[15] & 255}")
rb = w[13] | w[14] << 32
print("  read back " + " ".join(f"{(rb >> 8 * k) & 255:02x}" for k in reversed(range(8))) + "   (pattern 91 77 29 8c d0 ad 51 c1)")
ln = [(w[17 + k // 2] >> 16 * (k % 2)) & 0xFFFF for k in range(9)]
print("per lane (odelay_dqs/idelay_dq/idelay_dqs): " + "  ".join(f"{k}:{v >> 10 & 31}/{v >> 5 & 31}/{v & 31}" for k, v in enumerate(ln)))
ok = state == 23 and (st >> 1) & 1
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
