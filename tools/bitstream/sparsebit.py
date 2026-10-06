#!/usr/bin/env python3
"""Shrink an uncompressed 7-series bitstream to the frames that are not zero.

After JPROGRAM the configuration memory is all zeros, so only non-zero frames
need writing. The result keeps the original header and trailer and writes each
non-zero frame with its own FAR, followed by one zero frame that flushes the
frame pipeline. Small bitstreams load in a fraction of a second, before a
master-BPI board can start booting its own flash image over the top.

usage: sparsebit.py design.bit design.frames out.bin
  design.frames is the fasm2frames output the .bit was made from.
"""
import struct
import sys

FRAME_WORDS = 101
SYNC = bytes.fromhex("aa995566")
NOOP = 0x20000000


def bit_payload(path):
    """Raw configuration bytes of a .bit file (header fields a-d skipped)."""
    d = open(path, "rb").read()
    if SYNC in d[:64] or not d.startswith(b"\x00\x09"):
        return d
    i = 13  # fixed 0x0009 header plus 0x0001 'a'
    for key in b"abcd":
        assert d[i] == key, f"unexpected .bit field {d[i]!r}"
        n = struct.unpack(">H", d[i + 1:i + 3])[0]
        i += 3 + n
    assert d[i] == ord("e")
    n = struct.unpack(">I", d[i + 1:i + 5])[0]
    return d[i + 5:i + 5 + n]


def frame_list(frames):
    """(frame address, words) pairs from a fasm2frames .frames file."""
    for line in open(frames):
        f = line.split()
        if f:
            yield int(f[0], 16), [int(x, 16) for x in f[1].split(",")]


def main(bit, frames, out):
    raw = bit_payload(bit)
    s = raw.find(SYNC)
    preamble = raw[:s]
    words = list(struct.unpack(">%dI" % ((len(raw) - s) // 4), raw[s:s + (len(raw) - s) // 4 * 4]))
    # The type 1 FDRI write (0x30004000, zero words) is followed by the type 2 count.
    i = words.index(0x30004000)
    count = words[i + 1] & 0x7FFFFFF
    head = words[:i]
    tail = words[i + 2 + count:]
    # A CRC check covers every word written, so it cannot survive the rewrite:
    # replace each CRC write with a CRC reset, as openXC7 bitstreams do.
    for seq in (head, tail):
        for j in range(len(seq) - 1):
            if seq[j] == 0x30000001:
                seq[j], seq[j + 1] = 0x30008001, 0x00000007

    body = []
    nonzero = 0
    for far, data in frame_list(frames):
        assert len(data) == FRAME_WORDS
        if not any(data):
            continue
        nonzero += 1
        body += [0x30002001, far]                                # FAR
        body += [0x30004000 | 2 * FRAME_WORDS] + data + [0] * FRAME_WORDS  # FDRI frame + flush
    out_words = head + body + tail + [NOOP] * 64
    with open(out, "wb") as o:
        o.write(preamble)
        o.write(struct.pack(">%dI" % len(out_words), *out_words))
    print(f"{nonzero} non-zero frames; {len(raw)} -> {len(preamble) + 4 * len(out_words)} bytes")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
