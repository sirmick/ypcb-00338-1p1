# S0 self-test start-up: one hart, M mode. Enable the FPU, set the stack, call main.
    .section .text.start
    .globl _start
_start:
    li    t0, (1 << 13)          # mstatus.FS = Initial: the FPU is on
    csrs  mstatus, t0
    csrwi fcsr, 0
    la    sp, _stack_top
    call  main
1:  j     1b
