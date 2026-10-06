# Needs the systest loaded. Clears the MIG ECC counters, re-reads blocks written by ddr-test, reports CE/UE.
connect
targets -set -filter {name =~ "MicroBlaze #0"}
stop
proc ecc {} { set o {}; foreach {ch b} {0 0x76100000 1 0x76200000} { append o [format "ch%d status=%x ce_cnt=%d  " $ch [mrd -value $b] [mrd -value [expr {$b+0xC}]]] }; return $o }
foreach b {0x76100000 0x76200000} { mwr $b 3; mwr [expr {$b+0xC}] 0 }
puts "after clear:  [ecc]"
foreach {ch base} {0 0x100000000 1 0x180000000} {
  set bad 0
  foreach off {0x00100000 0x10000000 0x40000000 0x7FF00000} {
    set a [expr {$base + $off}]; set x [expr {($off >> 12) ^ 0x1234567 ^ $ch}]
    set back [mrd -value $a 16384]
    for {set j 0} {$j < 16384} {incr j} { set x [expr {(1103515245*$x + 12345) & 0xffffffff}]; if {[lindex $back $j] != $x} { incr bad } }
  }
  puts "ch$ch re-read 256 KiB written earlier: $bad bad words"
}
puts "after reads:  [ecc]"
set v [mrd -value 0x120000000 16]
puts "after reading never-written DDR: [ecc]"
