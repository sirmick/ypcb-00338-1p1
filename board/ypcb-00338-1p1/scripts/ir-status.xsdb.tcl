# Non-invasive config state: capture the IR (bit 4 INIT_COMPLETE, bit 5 DONE).
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
set s [jtag sequence]; $s irshift -state IDLE -capture -int 6 0x3f; set r [$s run]; $s delete
scan $r %x v; puts [format "IR capture 0x%02x: INIT=%d DONE=%d" $v [expr {($v>>4)&1}] [expr {($v>>5)&1}]]
jtag unlock
