# Load a bitstream with xsdb (Digilent driver), then report DONE now and 5 s later.
connect
targets -set -filter {name =~ "xc7k480t*"}
set f [lindex $argv 0]
if {[catch {fpga -file $f} e]} { puts "fpga: $e" } else { puts "fpga: loaded $f" }
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
proc irc {} { set s [jtag sequence]; $s irshift -state IDLE -capture -int 6 0x3f; set r [$s run]; $s delete; scan $r %x v; return [format "INIT=%d DONE=%d" [expr {($v>>4)&1}] [expr {($v>>5)&1}]] }
puts "right after: [irc]"; after 5000; puts "5 s later:   [irc]"
jtag unlock
