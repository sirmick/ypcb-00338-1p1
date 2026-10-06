#!/bin/bash
# Build a bitstream for the YPCB-00338-1P1 (XC7K480T-FFG1156) with openXC7. Runs on dino.
#
#   xc7-build.sh <design dir> [top]       e.g. xc7-build.sh designs/blinky
#
# The design dir holds *.v and one *.xdc; top defaults to the dir name. An optional prebuild.py is
# run first with the build dir as its argument (for generated files such as $readmemh contents,
# which yosys reads relative to the build dir). Outputs go to
# <design dir>/build/ (or $BUILD): <top>.json, .fasm, .board.fasm, .frames, .bit, a log per step.
# board/ypcb-00338-1p1/board.fasm (AC24 pull-up: without it nothing reaches DONE) is appended
# to every build's FASM before fasm2frames.
set -eo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)            # repository root
dir=$(cd "$1" && pwd); top=${2:-$(basename "$dir")}
[[ -d $dir ]] || { echo "usage: $0 <design dir> [top]" >&2; exit 2; }
source ~/opt/openxc7/export.sh
# Our patched nextpnr (upstream/nextpnr-*.patch, built by tools/build-nextpnr.sh) is used when present,
# until upstream has the fixes. TOOL_PATH= (empty) builds with the stock toolchain.
if [[ -z ${TOOL_PATH+x} ]]; then
    for d in ~/fpga/nextpnr-dev-bin ~/fpga/nextpnr-dev/build; do [[ -x $d/nextpnr-himbaechel ]] && { TOOL_PATH=$d; break; }; done
fi
[[ -n ${TOOL_PATH:-} ]] && { PATH=$TOOL_PATH:$PATH; echo "== using $(command -v nextpnr-himbaechel)"; }
DB=$PRJXRAY_DB_DIR/kintex7
PART=xc7k480tffg1156-2
CHIPDB=${CHIPDB:-$HOME/fpga/chipdb/xc7k480tffg1156.bin}
FREQ=${FREQ:-50}                                   # SYS_CLK is 50 MHz
THREADS=${THREADS:-$(nproc)}                       # dino: 48 weak cores; nextpnr's placer/router use them
NEXTPNR_ARGS=${NEXTPNR_ARGS:-}                     # extra nextpnr flags, e.g. --router2-tmg-ripup
SYNTH_ARGS=${SYNTH_ARGS:-}                         # extra synth_xilinx flags, e.g. -nosrl
out=$dir/${BUILD:-build}; mkdir -p "$out"; cd "$out"     # BUILD= names the output dir (parallel variants)

if [[ ! -s $CHIPDB ]]; then                        # about 2 minutes, once
    mkdir -p "$(dirname "$CHIPDB")"
    python3 "$NEXTPNR_XILINX_DIR/share/nextpnr/himbaechel/uarch/xilinx/gen/xilinx_gen.py" \
        --xray "$DB" --device xc7k480t --bba "${CHIPDB%.bin}.bba"   # fabric name, not package
    bbasm -l "${CHIPDB%.bin}.bba" "$CHIPDB"
fi
if [[ -f $dir/prebuild.py ]]; then                # generated inputs (e.g. $readmemh files) go into the build dir
    python3 "$dir/prebuild.py" "$out" || { echo "== prebuild FAILED"; exit 1; }
fi
xdc=$(ls "$out"/gen_*.xdc 2>/dev/null | head -1 || true)   # a prebuild-generated XDC wins over the design's own
[[ -n $xdc ]] || xdc=$(ls "$dir"/*.xdc | head -1)
step() {
    local name=$1 t0=$SECONDS; shift
    "$@" > "$out/$top.$STEP.log" 2>&1 || { echo "== $name FAILED"; grep -E "ERROR|error:" "$out/$top.$STEP.log" | head -20; exit 1; }
    echo "== $name ($((SECONDS - t0)) s)"
}
# generated sources from prebuild.py (gen_*.v in the build dir) come first, so their `defines apply
STEP=yosys    step synth   yosys -Q -p "synth_xilinx -flatten -abc9 -arch xc7 $SYNTH_ARGS -top $top; write_json $top.json" $(ls "$out"/gen_*.v "$dir"/*.v 2>/dev/null)
STEP=nextpnr  step pnr     nextpnr-xilinx --chipdb "$CHIPDB" --xdc "$xdc" --json $top.json --fasm $top.fasm --freq "$FREQ" --threads "$THREADS" $NEXTPNR_ARGS
grep -E "Max frequency" "$out/$top.nextpnr.log" | sort -u | sed 's/^Info: */   /' || true
BOARD_FASM=${BOARD_FASM-$here/board/ypcb-00338-1p1/board.fasm}   # BOARD_FASM= to build without it
if [[ -n $BOARD_FASM ]]; then grep -hv '^#' "$BOARD_FASM" | cat $top.fasm - > $top.board.fasm
else cp $top.fasm $top.board.fasm; fi
STEP=fasm2frames step frames sh -c "fasm2frames --part $PART --db-root $DB $top.board.fasm > $top.frames"
STEP=bit      step bit     xc7frames2bit --part_file "$DB/$PART/part.yaml" --part_name $PART \
                                --frm_file $top.frames --output_file $top.bit
ls -l "$out/$top.bit"
