# Read the whole BPI flash (64 MiB, ~60 s), read-only. vivado_lab -mode batch -source this -tclargs <out.bin>
# Output is raw flash order; see the card README for converting to bitstream order.
set out [lindex $argv 0]
open_hw_manager
connect_hw_server -url localhost:3121
open_hw_target
set dev [lindex [get_hw_devices xc7k480t*] 0]
current_hw_device $dev
refresh_hw_device -update_hw_probes false $dev
puts "PARTS: [get_cfgmem_parts *28gu512*]"
set part [lindex [get_cfgmem_parts {mt28gu512aax1e-bpi-x16}] 0]
create_hw_cfgmem -hw_device $dev $part
set mem [get_property PROGRAM.HW_CFGMEM $dev]
# Read-only: never program, erase, blank-check or verify.
set_property PROGRAM.ERASE 0 $mem
set_property PROGRAM.BLANK_CHECK 0 $mem
set_property PROGRAM.CFG_PROGRAM 0 $mem
set_property PROGRAM.VERIFY 0 $mem
set_property PROGRAM.CHECKSUM 0 $mem
create_hw_bitstream -hw_device $dev [get_property PROGRAM.HW_CFGMEM_BITFILE $dev]
program_hw_devices $dev
refresh_hw_device $dev
set t [clock seconds]
readback_hw_cfgmem -force -format bin -all -file $out $mem
puts "READBACK took [expr {[clock seconds]-$t}] s"
close_hw_target
disconnect_hw_server
