#!/usr/bin/env python3
"""Find what a working 7-series bitstream has that a failing one lacks, on real hardware.

Each test builds a bitstream from the GOOD one's header and trailer (CRC checks replaced by CRC
resets) plus a subset of its frame-write packets or bits, loads it with xsdb and checks DONE.
This is how the AC24 bit was found (2026-10-05): ~191k packets -> 1 packet -> 1 bit, ~40 loads.

  start-bisect.py empty   good.bit            # header + trailer only: does it reach DONE?
  start-bisect.py prefix  good.bit            # last packet needed (keeps packets [0, N))
  start-bisect.py window  good.bit END        # first packet needed, keeping packets [lo, END)
  start-bisect.py bits    good.bit FAR [n]    # minimal set of bits in n frames from FAR (default 2)

Runs on dino. LOADER defaults to the board's load.xsdb.tcl; xsdb must be on PATH
(source /opt/Xilinx/2026.1/Vivado/settings64.sh). Assumes the target already fails without the
needed content, and that "needed" is monotonic for prefix/window (true for one missing frame).
"""
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LOADER = os.environ.get("LOADER", os.path.join(HERE, "../../board/ypcb-00338-1p1/scripts/load.xsdb.tcl"))
WORK = os.environ.get("WORK", "/tmp/start-bisect.bin")
SYNC = bytes.fromhex("aa995566")
FRAME_WORDS = 101
CMD_WRITE, FAR_WRITE, CRC_WRITE, NOOP = 0x30008001, 0x30002001, 0x30000001, 0x20000000
RCRC, GRESTORE = 7, 0x0A


def payload(path):
    """(preamble bytes, config words from the sync word on) of a .bit or raw .bin."""
    d = open(path, "rb").read()
    if d[:2] == b"\x00\x09":                       # .bit: skip header fields a-d and 'e' + length
        i = 13
        for _ in b"abcd":
            i += 3 + struct.unpack(">H", d[i + 1:i + 3])[0]
        d = d[i + 5:i + 5 + struct.unpack(">I", d[i + 1:i + 5])[0]]
    s = d.find(SYNC)
    return d[:s], list(struct.unpack(">%dI" % ((len(d) - s) // 4), d[s:s + (len(d) - s) // 4 * 4]))


def no_crc(ws):
    """Replace CRC checks with CRC resets: a CRC cannot survive dropping packets."""
    ws = list(ws)
    for k in range(len(ws) - 1):
        if ws[k] == CRC_WRITE:
            ws[k], ws[k + 1] = CMD_WRITE, RCRC
    return ws


def split(path):
    """preamble, head (to FAR 0 + WCFG), frame-write packets, tail (from the last GRESTORE)."""
    pre, w = payload(path)
    i = next(k for k in range(len(w) - 3) if w[k] == FAR_WRITE and w[k + 1] == 0)
    j = max(k for k in range(len(w) - 1) if w[k] == CMD_WRITE and w[k + 1] == GRESTORE)
    head, mid = no_crc(w[:i + 4]), w[i + 4:j]
    tail = no_crc([CMD_WRITE, RCRC] + w[j:]) + [NOOP] * 64
    pk, k = [], 0
    while k < len(mid):
        x = mid[k]
        if x >> 29 == 1:                           # type 1, maybe followed by a type 2 count
            n = x & 0x7FF
            if k + 1 + n < len(mid) and mid[k + 1 + n] >> 29 == 2:
                n += 1 + (mid[k + 1 + n] & 0x7FFFFFF)
            pk.append(mid[k:k + 1 + n]); k += 1 + n
        else:
            pk.append([x]); k += 1
    return pre, head, pk, tail


def frames_of(path):
    """{frame address: words} for an uncompressed bitstream, via prjxray bitread."""
    part = os.path.expanduser("~/opt/openxc7/share/nextpnr/prjxray-db/kintex7/xc7k480tffg1156-2/part.yaml")
    out = WORK + ".frm"
    subprocess.run(["bitread", "-part_file", part, "-z", "-o", out, path], capture_output=True, check=True)
    fr, cur = {}, None
    for line in open(out):
        if line.startswith(".frame"):
            cur = int(line.split()[1], 16); fr[cur] = []
        elif cur is not None:
            fr[cur] += [int(x, 16) for x in line.split()]
    return fr


def load(pre, words, label):
    open(WORK, "wb").write(pre + struct.pack(">%dI" % len(words), *words))
    r = subprocess.run(["xsdb", LOADER, WORK], capture_output=True, text=True, timeout=300)
    ok = "right after: INIT=1 DONE=1" in r.stdout
    print(f"  {label:40s} {4 * len(words):9d} bytes -> {'DONE' if ok else 'fail'}", flush=True)
    return ok


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    mode, good = sys.argv[1], sys.argv[2]
    pre, head, pk, tail = split(good)
    body = lambda lo, hi: [x for p in pk[lo:hi] for x in p]
    if mode == "empty":
        load(pre, head + tail, "header + trailer only")
    elif mode == "prefix":
        print(len(pk), "frame-write packets")
        assert load(pre, head + no_crc(body(0, len(pk))) + tail, "all packets (CRC reset)")
        lo, hi = 0, len(pk)
        while hi - lo > 1:
            m = (lo + hi) // 2
            if load(pre, head + no_crc(body(0, m)) + tail, f"packets [0,{m})"): hi = m
            else: lo = m
        print("last packet needed:", hi - 1, " ".join("%08x" % x for x in pk[hi - 1][:3]))
    elif mode == "window":
        end = int(sys.argv[3])
        lo, hi = 0, end - 1
        while hi - lo > 1:
            m = (lo + hi) // 2
            if load(pre, head + no_crc(body(m, end)) + tail, f"packets [{m},{end})"): lo = m
            else: hi = m
        print(f"smallest window: packets [{lo},{end})")
        for p in pk[lo:end]:
            print("   ", " ".join("%08x" % x for x in p[:3]), "... len", len(p))
    elif mode == "bits":
        far = int(sys.argv[3], 0); n = int(sys.argv[4]) if len(sys.argv) > 4 else 2
        fr = frames_of(good)
        bits = [(f, w, b) for f in range(n) for w in range(FRAME_WORDS) for b in range(32)
                if (fr.get(far + f, [0] * FRAME_WORDS)[w] >> b) & 1]
        print(len(bits), "set bits")

        def test(sub):
            data = [[0] * FRAME_WORDS for _ in range(n)]
            for f, w, b in sub:
                data[f][w] |= 1 << b
            frames = [FAR_WRITE, far, 0x30004000 | (FRAME_WORDS * (n + 1))]
            frames += [x for d in data for x in d] + [0] * FRAME_WORDS   # one flush frame
            return load(pre, head + frames + tail, f"{len(sub)} bits")

        assert test(bits) and not test([]), "need: all bits pass, no bits fail"
        cur, k = list(bits), 2                       # ddmin: remove chunks while it still passes
        while len(cur) >= 2:
            size = max(1, len(cur) // k)
            for c in [cur[i:i + size] for i in range(0, len(cur), size)]:
                rest = [x for x in cur if x not in c]
                if test(rest):
                    cur, k = rest, max(k - 1, 2); break
            else:
                if k >= len(cur): break
                k = min(len(cur), k * 2)
        print("minimal bits (frame offset, word, bit) from FAR %#x:" % far, cur)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
