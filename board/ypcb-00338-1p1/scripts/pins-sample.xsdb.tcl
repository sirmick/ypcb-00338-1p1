# Boundary-scan SAMPLE of the three LED pins, 100 times ~45 ms apart.
# Prints: ms, then (ctl out pad) for P30, M30, N30. ctl 1 = tri-stated. `fpga pins` summarises it.
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
set zeros [string repeat 0 [expr {(1395+3)/4}]]
proc bit {hex n} { scan [string range $hex [expr {($n/8)*2}] [expr {($n/8)*2+1}]] %x b; expr {($b >> ($n%8)) & 1} }
set t0 [clock milliseconds]
for {set i 0} {$i < 100} {incr i} {
  set s [jtag sequence]
  $s irshift -state IDLE -int 6 1
  $s drshift -state IDLE -capture -hex 1395 $zeros
  set r [$s run]; $s delete
  set o {}; foreach c {851 852 853 845 846 847 848 849 850} { append o [bit $r $c] }; puts "[expr {[clock milliseconds]-$t0}] $o"
  after 40
}
jtag unlock
