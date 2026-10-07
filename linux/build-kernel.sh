#!/bin/bash
# A stock Linux kernel for the guest SoC: riscv defconfig, with the initramfs built in (OpenSBI hands
# Linux only the device tree, so the initramfs travels inside the Image).
#
#   build-kernel.sh <out dir> <initramfs dir>      LINUX_SRC defaults to ~/fpga/src/linux-6.12
set -eo pipefail
out=$(mkdir -p "$1" && cd "$1" && pwd)
initramfs=$(cd "$2" && pwd)
src=${LINUX_SRC:-$HOME/fpga/src/linux-6.12}
k=(make -s -C "$src" O="$out/linux" ARCH=riscv CROSS_COMPILE=riscv64-linux-gnu-)
"${k[@]}" defconfig
"$src/scripts/config" --file "$out/linux/.config" --set-str INITRAMFS_SOURCE "$initramfs" \
    --set-val INITRAMFS_ROOT_UID 0 --set-val INITRAMFS_ROOT_GID 0
"${k[@]}" olddefconfig
"${k[@]}" -j"$(nproc)" Image
cp "$out/linux/arch/riscv/boot/Image" "$out/Image"
echo "Linux $(make -s -C "$src" kernelversion) -> $out/Image ($(du -h "$out/Image" | cut -f1))"
