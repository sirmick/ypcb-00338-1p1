# Read IDCODE, FUSE_CNTL, FUSE_USER and FUSE_DNA by shifting zeros through each DR. READ-ONLY: never program fuses.
# Captured hex is byte-reversed (LSB byte first): IDCODE 0x23751093 prints as 93107523.
connect
puts [jtag targets]
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
proc rd {name ir bits} {
  set s [jtag sequence]
  $s irshift -state IDLE -int 6 $ir
  $s drshift -state IDLE -capture -hex $bits [string repeat 0 [expr {$bits/4}]]
  set r [$s run]
  $s delete
  puts [format "%-10s = 0x%s" $name $r]
}
rd IDCODE    0x09 32
rd FUSE_CNTL 0x34 32
rd FUSE_USER 0x33 32
rd FUSE_DNA  0x32 64
rd FUSE_DNA2 0x32 64
jtag unlock
disconnect
