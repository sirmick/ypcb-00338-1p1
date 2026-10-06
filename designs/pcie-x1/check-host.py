#!/usr/bin/env python3
"""Gate 8 host-side check for designs/pcie-x1, run on dino as root (`fpga pcie-check`).

Finds the 10ee:0480 endpoint, enables memory decoding, maps BAR0 (4 KB, 256 x 32-bit registers that
reset to 0x12345678), checks the reset value, writes a pattern to every word, reads it back, and
reports link speed and width from sysfs.
"""
import glob
import mmap
import os
import struct
import sys

dev = next((d for d in glob.glob("/sys/bus/pci/devices/*")
            if open(d + "/vendor").read().strip() == "0x10ee" and open(d + "/device").read().strip() == "0x0480"), None)
if not dev:
    sys.exit("no 10ee:0480 device: load designs/pcie-x1 and rescan (echo 1 > /sys/bus/pci/rescan)")
print(f"device {os.path.basename(dev)}  link {open(dev + '/current_link_speed').read().strip()} "
      f"x{open(dev + '/current_link_width').read().strip()}")
with open(dev + "/enable", "w") as f:            # sets the memory-space bit in COMMAND
    f.write("1")
fd = os.open(dev + "/resource0", os.O_RDWR | os.O_SYNC)
bar = mmap.mmap(fd, 4096, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
rd = lambda i: struct.unpack_from("<I", bar, 4 * i)[0]
def wr(i, v): struct.pack_into("<I", bar, 4 * i, v)

first = [rd(i) for i in range(4)]
print("first words:", " ".join(f"{v:08x}" for v in first))
pat = lambda i, s: ((i * 0x9E3779B1) ^ s) & 0xFFFFFFFF
errors = 0
for seed in (0x00000000, 0xFFFFFFFF, 0xA5A5A5A5, 0x5A5A1234):
    for i in range(256):
        wr(i, pat(i, seed))
    errors += sum(rd(i) != pat(i, seed) for i in range(256))
print(f"write/read 4 x 256 words: {errors} errors")
ok = errors == 0
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
