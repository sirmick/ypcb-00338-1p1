#!/bin/bash
# The S5 initramfs as a directory the kernel builds in (CONFIG_INITRAMFS_SOURCE): Debian's static busybox
# (every applet, linked at boot) and static bash (riscv64), init, /etc, memtest and membench. Downloads
# the two packages once into <out>.
#
#   build-initramfs.sh <out dir>        -> <out>/initramfs/
set -eo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "$1" && cd "$1" && pwd)
fetch() { # <package path under pool/main> <deb>: download once, unpack into <out>/pkg
    [[ -s $out/$2 ]] || curl -sfo "$out/$2" "https://deb.debian.org/debian/pool/main/$1/$2"
    dpkg-deb -x "$out/$2" "$out/pkg"
}
rm -rf "$out/initramfs" "$out/pkg"
fetch b/busybox busybox-static_1.37.0-6+b9_riscv64.deb
fetch b/bash bash-static_5.2.37-2+b10_riscv64.deb
r=$out/initramfs
mkdir -p "$r"/{bin,sbin,usr/bin,usr/sbin,dev,proc,sys,tmp,root,etc,var/log,run}
cp "$out/pkg/usr/bin/busybox" "$r/bin/busybox"
cp "$out/pkg/usr/bin/bash-static" "$r/bin/bash"
ln -s busybox "$r/bin/sh"
install -m 755 "$here/init" "$r/init"
cp -r "$here/etc/." "$r/etc/"
riscv64-linux-gnu-gcc -static -O2 -o "$r/bin/memtest" "$here/memtest.c"
riscv64-linux-gnu-gcc -static -O2 -o "$r/bin/membench" "$here/membench.c"
echo "initramfs -> $r ($(du -sh "$r" | cut -f1))"
