# Needs TiferKing's systest loaded (MicroBlaze). Address-line, data-bus and block test on both DDR3 channels.
connect
targets -set -filter {name =~ "MicroBlaze #0"}
stop
puts "MSR=[string trim [lindex [split [rrd msr] :] 1]] (DCE bit 0x80 must be 0)"
proc rd {a} { return [mrd -value $a] }
foreach {ch base} {0 0x100000000 1 0x180000000} {
  set fail 0
  # 1. address lines: base + 2^k for k=2..30 (2 GB per channel)
  set addrs [list $base]
  for {set k 2} {$k <= 30} {incr k} { lappend addrs [expr {$base + (1<<$k)}] }
  set i 0; foreach a $addrs { mwr $a [expr {0x5A000000 | ($ch<<20) | $i}]; incr i }
  set i 0; foreach a $addrs { set v [rd $a]; set e [expr {0x5A000000 | ($ch<<20) | $i}]
    if {$v != $e} { puts [format "  ch%d ADDR FAIL @%x got %08x want %08x" $ch $a $v $e]; incr fail }; incr i }
  # 2. data bus: walking 1s and 0s at offset 0x40 (avoid the address-test words)
  set dfail 0
  for {set b 0} {$b < 32} {incr b} { foreach p [list [expr {1<<$b}] [expr {~(1<<$b) & 0xffffffff}]] {
    mwr [expr {$base+0x40}] $p; set v [rd [expr {$base+0x40}]]
    if {$v != $p} { incr dfail; if {$dfail < 5} { puts [format "  ch%d DATA FAIL pattern %08x got %08x" $ch $p $v] } } } }
  incr fail $dfail
  # 3. blocks of 16384 words (64 KiB) at several offsets, LCG data
  set t0 [clock milliseconds]; set words 0
  foreach off {0x00100000 0x10000000 0x40000000 0x7FF00000} {
    set a [expr {$base + $off}]; set x [expr {($off >> 12) ^ 0x1234567 ^ $ch}]; set data {}
    for {set j 0} {$j < 16384} {incr j} { set x [expr {(1103515245*$x + 12345) & 0xffffffff}]; lappend data $x }
    mwr $a $data 16384
    set back [mrd -value $a 16384]
    set bad 0; for {set j 0} {$j < 16384} {incr j} { if {[lindex $back $j] != [lindex $data $j]} { incr bad } }
    if {$bad} { puts [format "  ch%d BLOCK FAIL @%x: %d/16384 words wrong" $ch $a $bad]; incr fail $bad }
    incr words 16384
  }
  set ms [expr {[clock milliseconds]-$t0}]
  puts [format "ch%d @0x%x: address lines 2..30 + data bus + %d KiB blocks -> %s  (%.0f KiB/s)" $ch $base [expr {$words*4/1024}] [expr {$fail ? "FAIL ($fail)" : "PASS"}] [expr {$words*8.0/1024/($ms/1000.0)}]]
}
