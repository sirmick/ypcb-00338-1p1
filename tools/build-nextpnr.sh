#!/bin/bash
# build-nextpnr.sh: build the nextpnr-himbaechel (xilinx) that this repo's builds use by default:
# openXC7/nextpnr at the commit below plus our patches in upstream/, installed to ~/fpga/nextpnr-dev-bin.
#
#   tools/build-nextpnr.sh            patches 0001-0003 (proven on silicon: BRAM widths, OSERDES T1)
#   NEXTPNR_IOL=1 tools/build-nextpnr.sh   also 0004 (time IOLOGIC fabric ports; a draft)
#
# Needs a C++ toolchain, cmake, Boost and Eigen (the same as openXC7's toolchain installer), and the
# prjxray database from the openXC7 install (PRJXRAY_DB, default ~/opt/openxc7/share/nextpnr/prjxray-db).
# No chipdb is embedded (HIMBAECHEL_XILINX_DEVICES empty): xc7-build.sh builds one and passes --chipdb.
# xc7-build.sh picks the result up automatically; TOOL_PATH= (empty) builds with the stock nextpnr.
set -eo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
REV=c68c1358
SRC=${NEXTPNR_SRC:-$HOME/fpga/nextpnr-dev}
OUT=${NEXTPNR_OUT:-$HOME/fpga/nextpnr-dev-bin}
DB=${PRJXRAY_DB:-$HOME/opt/openxc7/share/nextpnr/prjxray-db}
[[ -d $DB/kintex7 ]] || { echo "no prjxray database at $DB (set PRJXRAY_DB)" >&2; exit 2; }

if [[ ! -d $SRC/.git ]]; then
    git clone https://github.com/openXC7/nextpnr.git "$SRC"
fi
cd "$SRC"
git fetch -q origin
if [[ -n $(git status --porcelain --untracked-files=no) ]]; then
    echo "$SRC has local changes; commit or discard them first (git -C $SRC diff)" >&2; exit 2
fi
git checkout -q "$REV"
git submodule update -q --init --recursive
patches=("$here"/upstream/nextpnr-000[123]-*.patch)
[[ ${NEXTPNR_IOL:-0} == 1 ]] && patches+=("$here"/upstream/nextpnr-0004-*.patch)
for p in "${patches[@]}"; do
    echo "== applying $(basename "$p")"
    git apply "$p"
done
cmake -S . -B build -DARCH=himbaechel -DHIMBAECHEL_UARCH=xilinx -DHIMBAECHEL_PRJXRAY_DB="$DB" \
      -DHIMBAECHEL_XILINX_DEVICES= \
      -DBUILD_PYTHON=OFF -DBUILD_GUI=OFF -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build --target nextpnr-himbaechel -j"$(nproc)"
mkdir -p "$OUT"
cp build/nextpnr-himbaechel "$OUT/"
git checkout -q -- .                 # leave the checkout clean for the next run
echo "== $OUT/nextpnr-himbaechel: openXC7/nextpnr $REV + ${#patches[@]} patches"
