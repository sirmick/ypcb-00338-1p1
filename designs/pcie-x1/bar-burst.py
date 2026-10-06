#!/usr/bin/env python3
"""Do exactly N BAR0 reads or writes on the 10ee:0480 endpoint (debug aid). bar-burst.py read|write N"""
import glob, mmap, os, struct, sys
dev = next(d for d in glob.glob("/sys/bus/pci/devices/*") if open(d + "/device").read().strip() == "0x0480")
fd = os.open(dev + "/resource0", os.O_RDWR | os.O_SYNC)
bar = mmap.mmap(fd, 4096, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
op, n = sys.argv[1], int(sys.argv[2])
for i in range(n):
    if op == "read":
        struct.unpack_from("<I", bar, 4 * (i % 256))
    else:
        struct.pack_into("<I", bar, 4 * (i % 256), 0xA5000000 | i)
print(f"{n} {op}s done")
