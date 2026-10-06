# XADC die temperature and rails. vivado_lab -mode batch -source this -tclargs [samples] [seconds apart]
set n [expr {[llength $argv] > 0 ? [lindex $argv 0] : 1}]
set gap [expr {[llength $argv] > 1 ? [lindex $argv 1] : 5}]
open_hw_manager
connect_hw_server -url localhost:3121
open_hw_target
set dev [lindex [get_hw_devices xc7k480t*] 0]
current_hw_device $dev
refresh_hw_device -update_hw_probes false $dev
set s [get_hw_sysmons -of_objects $dev]
for {set i 0} {$i < $n} {incr i} {
  refresh_hw_sysmon $s
  puts [format "T=%s C  maxT=%s C  VCCINT=%s VCCAUX=%s VCCBRAM=%s" [get_property TEMPERATURE $s] [get_property MAX_TEMPERATURE $s] [get_property VCCINT $s] [get_property VCCAUX $s] [get_property VCCBRAM $s]]
  if {$i < $n - 1} { after [expr {$gap * 1000}] }
}
close_hw_target
disconnect_hw_server
