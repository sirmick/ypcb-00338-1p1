# Boundary-scan snapshot of the config pins and AC24: DONE pad, INIT_B pad, AC24 (ctl, out, pad), IR DONE.
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
proc bit {hex n} { scan [string range $hex [expr {($n/8)*2}] [expr {($n/8)*2+1}]] %x b; expr {($b >> ($n%8)) & 1} }
set s [jtag sequence]; $s irshift -state IDLE -capture -int 6 1; $s drshift -state IDLE -capture -hex 1395 [string repeat 0 349]
set r [$s run]; $s delete
set ir [lindex $r 0]; set bsr [lindex $r 1]
scan $ir %x v
puts [format "IR_DONE=%d  DONE_pad=%d INIT_B_pad=%d  AC24 ctl=%d out=%d pad=%d" [expr {($v>>5)&1}] [bit $bsr 13] [bit $bsr 10] [bit $bsr 635] [bit $bsr 636] [bit $bsr 637]]
jtag unlock
