# Read the hello design's JTAG USER1 register twice, 1 s apart: magic word + counter.
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
proc user1 {} {
  set s [jtag sequence]; $s irshift -state IDLE -int 6 0x02; $s drshift -state IDLE -capture -hex 64 [string repeat 0 16]
  set r [$s run]; $s delete
  # captured hex is byte-reversed (LSB byte first)
  set v 0; for {set i 7} {$i >= 0} {incr i -1} { scan [string range $r [expr {$i*2}] [expr {$i*2+1}]] %x b; set v [expr {($v << 8) | $b}] }
  return $v
}
set a [user1]; set t0 [clock milliseconds]; after 1000; set b [user1]; set dt [expr {[clock milliseconds]-$t0}]
puts [format "magic=%08x count=%u  then magic=%08x count=%u  (%d ms apart)" [expr {$a & 0xffffffff}] [expr {$a >> 32}] [expr {$b & 0xffffffff}] [expr {$b >> 32}] $dt]
if {($a & 0xffffffff) != 0xc0ffee42} { puts "no hello USER1 register (magic != c0ffee42): is designs/hello loaded?"; jtag unlock; exit 1 }
puts [format "counter rate: %.2f MHz" [expr {((($b >> 32) - ($a >> 32)) & 0xffffffff) / ($dt * 1000.0)}]]
jtag unlock
