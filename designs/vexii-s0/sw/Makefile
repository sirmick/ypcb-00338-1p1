# Bare-metal self-test for the S0 core (RV64IMAFDC, M mode, 64 KiB RAM at 0x8000_0000)
CFLAGS = -march=rv64imafdc_zicsr_zicntr -mabi=lp64d -mcmodel=medany -O2 -fno-math-errno \
         -nostdlib -nostartfiles -ffreestanding -Wall
selftest.elf: start.S main.c link.ld
	riscv64-unknown-elf-gcc $(CFLAGS) -T link.ld -Wl,--no-warn-rwx-segments start.S main.c -lgcc -o $@
clean:
	rm -f selftest.elf
