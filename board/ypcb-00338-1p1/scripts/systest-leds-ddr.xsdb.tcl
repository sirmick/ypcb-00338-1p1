# Needs the systest loaded. Drives the LEDs through axi_gpio_0 (0x40000000) and checks the pins by boundary scan.
# The DDR part of this script uses the wrong address (0x80000000 is DDR only from XDMA); use ddr-test instead.
connect
targets -set -filter {name =~ "MicroBlaze #0"}
stop
proc bit {hex n} { scan [string range $hex [expr {($n/8)*2}] [expr {($n/8)*2+1}]] %x b; expr {($b >> ($n%8)) & 1} }
proc pads {} {
  set zeros [string repeat 0 349]
  jtag targets -set -filter {name =~ "xc7k480t*"}
  set s [jtag sequence]; $s irshift -state IDLE -int 6 1; $s drshift -state IDLE -capture -hex 1395 $zeros
  set r [$s run]; $s delete
  targets -set -filter {name =~ "MicroBlaze #0"}
  return "P30=[bit $r 853] M30=[bit $r 847] N30=[bit $r 850]"
}
for {set v 0} {$v < 8} {incr v} {
  mwr 0x40000000 $v
  puts "gpio=$v read=[string trim [lindex [mrd -value 0x40000000] 0]] pads: [pads]"
}
mwr 0x40000000 5
puts "DDR test:"
if {[catch {
  for {set i 0} {$i < 4} {incr i} { mwr [expr {0x80000000 + $i*0x100000}] [expr {0xA5A50000 + $i}] }
  for {set i 0} {$i < 4} {incr i} { puts [format "  %08x -> %08x" [expr {0x80000000 + $i*0x100000}] [mrd -value [expr {0x80000000 + $i*0x100000}]]] }
} err]} { puts "  DDR access failed: $err" }
