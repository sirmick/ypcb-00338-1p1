#!/bin/bash
# OpenSBI for the guest SoC, with the device tree built in (the cores start at 0x80000000 with nothing
# before them to pass one). Writes into <out>: card.dtb, fw_jump.bin (jumps to Linux at 0x80200000 with
# the tree at 0x82200000) and fw_payload.bin (OpenSBI's own test payload, for simulation).
#
#   [CARD_HARTS=4] build-opensbi.sh <out dir>     the tree from card-dts.py (default 1 hart);
#                                                 OPENSBI_SRC defaults to ~/fpga/src/opensbi (v1.6)
set -eo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "$1" && cd "$1" && pwd)
src=${OPENSBI_SRC:-$HOME/fpga/src/opensbi}
"$here/card-dts.py" "${CARD_HARTS:-1}" > "$out/card.dts"
cpp -nostdinc -undef -x assembler-with-cpp -P -I "$here" "$out/card.dts" | dtc -q -I dts -O dtb -o "$out/card.dtb" -
make -s -C "$src" O="$out/opensbi" PLATFORM=generic CROSS_COMPILE=riscv64-linux-gnu- \
    FW_FDT_PATH="$out/card.dtb" FW_TEXT_START=0x80000000 FW_PAYLOAD=y FW_JUMP=y -j"$(nproc)"
cp "$out"/opensbi/platform/generic/firmware/fw_{jump,payload}.bin "$out/"
echo "OpenSBI $(git -C "$src" describe --tags), ${CARD_HARTS:-1} hart(s) -> $out/fw_jump.bin, fw_payload.bin"
