# JPROGRAM only: clears the FPGA; in master-BPI mode it then boots from flash.
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
proc ir {} { set s [jtag sequence]; $s irshift -state IDLE -capture -int 6 0x3f; set r [$s run]; $s delete; return $r }
puts "IR capture before: [ir]"
set s [jtag sequence]; $s irshift -state IDLE -int 6 0x0b; $s irshift -state IDLE -int 6 0x3f; $s run; $s delete
for {set i 0} {$i < 10} {incr i} { after 500; puts "t=[expr {($i+1)*0.5}]s IR capture: [ir]" }
jtag unlock
