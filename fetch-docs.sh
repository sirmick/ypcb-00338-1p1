#!/usr/bin/env bash
# Downloads the reference documents for the Kintex-7 card into datasheets/ (git-ignored).
# AMD's old xilinx.com links redirect to a docs.amd.com viewer page whose last path element is
# a document id; /api/khub/documents/<id>/content serves the PDF itself.
set -euo pipefail
cd "$(dirname "$0")/datasheets"
UA="Mozilla/5.0"

amd() { # amd <file> <legacy xilinx.com url>
  local out=$1 url=$2 viewer id
  [[ -s $out ]] && { echo "have $out"; return; }
  viewer=$(curl -sSL -A "$UA" -o /dev/null -w '%{url_effective}' "$url")
  id=${viewer##*/}
  if [[ $id == root ]]; then   # newer "map" viewer: /r/<map id>/root; the PDF is the map's attachment
    local map att; map=${viewer%/root}; map=${map##*/}
    att=$(curl -sSL -A "$UA" "https://docs.amd.com/api/khub/maps/$map/attachments" |
          python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
    curl -sSL -A "$UA" -o "$out" "https://docs.amd.com/api/khub/maps/$map/attachments/$att/content"
  else
    curl -sSL -A "$UA" -o "$out" "https://docs.amd.com/api/khub/documents/$id/content"
  fi
  file -b "$out" | grep -q PDF || { echo "FAILED $out ($viewer)"; rm -f "$out"; return; }
  echo "got  $out"
}

plain() { # plain <file> <url>
  [[ -s $1 ]] && { echo "have $1"; return; }
  curl -sSL -A "$UA" -o "$1" "$2" && echo "got  $1" || { echo "FAILED $1"; rm -f "$1"; }
}

X=https://www.xilinx.com/support/documentation
amd ds180-7series-overview.pdf        $X/data_sheets/ds180_7Series_Overview.pdf
amd ds182-kintex7-dc-ac.pdf           $X/data_sheets/ds182_Kintex_7_Data_Sheet.pdf
amd ug470-7series-config.pdf          $X/user_guides/ug470_7Series_Config.pdf
amd ug471-7series-selectio.pdf        $X/user_guides/ug471_7Series_SelectIO.pdf
amd ug472-7series-clocking.pdf        $X/user_guides/ug472_7Series_Clocking.pdf
amd ug473-7series-memory.pdf          $X/user_guides/ug473_7Series_Memory_Resources.pdf
amd ug474-7series-clb.pdf             $X/user_guides/ug474_7Series_CLB.pdf
amd ug475-7series-pkg-pinout.pdf      $X/user_guides/ug475_7Series_Pkg_Pinout.pdf
amd ug476-7series-gtx.pdf             $X/user_guides/ug476_7Series_Transceivers.pdf
amd ug477-7series-pcie.pdf            $X/user_guides/ug477_7Series_IntBlock_PCIe.pdf
amd ug480-7series-xadc.pdf            $X/user_guides/ug480_7Series_XADC.pdf
amd ug483-7series-pcb.pdf             $X/user_guides/ug483_7Series_PCB.pdf
# ug586 (MIG, 7 series) has no scriptable URL any more: download it by hand from
# https://docs.amd.com/v/u/en-US/ug586_7Series_MIS into datasheets/ug586-7series-mig.pdf
amd xapp1239-7series-encryption.pdf   $X/application_notes/xapp1239-fpga-bitstream-encryption.pdf
plain xc7k480tffg1156-pkg.txt         https://www.xilinx.com/support/packagefiles/k7packages/xc7k480tffg1156pkg.txt
plain lm73-temp-sensor.pdf            https://www.ti.com/lit/ds/symlink/lm73.pdf
