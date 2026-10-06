#!/usr/bin/env python3
"""Pick the MMCM settings for TIMING_MHZ (env, default 200) and write gen_params.v into the build dir."""
import os
import sys

MHZ = int(os.environ.get("TIMING_MHZ", "200"))
# (VCO multiplier from 50 MHz, output divider): VCO must stay within 600-1440 MHz
SETTINGS = {200: (20, 5), 250: (20, 4), 300: (24, 4), 333: (20, 3), 350: (21, 3), 400: (24, 3),
            450: (18, 2), 500: (20, 2), 550: (22, 2), 600: (24, 2)}
if MHZ not in SETTINGS:
    sys.exit(f"TIMING_MHZ={MHZ}: pick one of {sorted(SETTINGS)}")
mult, div = SETTINGS[MHZ]
with open(os.path.join(sys.argv[1], "gen_params.v"), "w") as f:
    f.write(f"`define TIMING_MULT {mult}.0\n`define TIMING_DIV {div}.0\n`define TIMING_MHZ {MHZ}\n")
