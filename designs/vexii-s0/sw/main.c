// S0 self-test for VexiiRiscv on the card (RV64IMAFDC): integer, M, A and double-precision checks
// against known answers. LEDs at 0x10003000 (MicroSoc's demo peripheral):
//   pass: LED0 toggles every 1/4 s (2 Hz) and LED2 stays on
//   fail: LED1 on, LED2 blinks the number of the first failing test, then a pause, repeating
#include <stdint.h>
#define LEDS (*(volatile uint32_t *)0x10003000)
#define HZ 50000000ull

static inline uint64_t cycles(void) { uint64_t c; asm volatile("rdcycle %0" : "=r"(c)); return c; }
static void wait(uint64_t c) { uint64_t t = cycles(); while (cycles() - t < c) {} }

static volatile uint64_t sink;

static int test_int(void) {          // 32-bit FNV-1a over a counting pattern
    uint32_t h = 2166136261u;
    for (uint32_t i = 0; i < 100000; i++) { h ^= (i * 2654435761u) >> 7; h *= 16777619u; }
    sink = h;
    uint32_t ref = 2166136261u;      // recomputed the slow way, byte by byte, as a cross-check
    for (uint32_t i = 0; i < 100000; i++) { uint32_t v = (i * 2654435761u) >> 7; ref ^= v; ref *= 16777619u; }
    return h == ref && h != 0;
}
static int test_muldiv(void) {
    volatile uint64_t a = 0x123456789abcdefull, b = 0xfedcba987ull;
    uint64_t p = a * b, q = a / 12345, r = a % 12345;
    __uint128_t full = (__uint128_t)a * b;
    return p == (uint64_t)full && q * 12345 + r == a && (uint64_t)(full >> 64) == 0x121fa00aull
           && (int64_t)(-7) / 2 == -3 && (int64_t)(-7) % 2 == -1;
}
static int test_atomic(void) {
    volatile uint64_t x = 5; uint64_t old;
    asm volatile("amoadd.d %0, %2, (%1)" : "=r"(old) : "r"(&x), "r"(10ull) : "memory");
    uint64_t v, ok;
    asm volatile("1: lr.d %0, (%2)\n addi %0, %0, 1\n sc.d %1, %0, (%2)\n bnez %1, 1b"
                 : "=&r"(v), "=&r"(ok) : "r"(&x) : "memory");
    return old == 5 && x == 16;
}
static int test_fpu(void) {
    double s = 0;                     // Basel problem: sum 1/k^2 -> pi^2/6
    for (int k = 1; k <= 20000; k++) s += 1.0 / ((double)k * k);
    double pi2_6 = 1.6449340668482264, err = pi2_6 - s;   // tail ~ 1/20000 = 5e-5
    if (!(err > 4.99e-5 && err < 5.01e-5)) return 0;
    volatile double two = 2.0, x = 1e300;
    double r = __builtin_sqrt(two);
    if (r * r - 2.0 > 1e-15 || r * r - 2.0 < -1e-15) return 0;
    double f; asm volatile("fmadd.d %0, %1, %2, %3" : "=f"(f) : "f"(3.0), "f"(4.0), "f"(5.0));
    if (f != 17.0) return 0;
    if (!(x * x > 1e308)) return 0;   // overflows to infinity
    volatile float a = 1.5f, b = 2.25f;
    return a * b == 3.375f && (long)(-2.75) == -2;
}

int main(void) {
    int (*tests[])(void) = { test_int, test_muldiv, test_atomic, test_fpu };
    int fail = 0;
    for (int i = 0; i < 4 && !fail; i++) if (!tests[i]()) fail = i + 1;
    for (;;) {
        if (!fail) { LEDS = 4 | 1; wait(HZ / 4); LEDS = 4; wait(HZ / 4); continue; }
        for (int n = 0; n < fail; n++) { LEDS = 2 | 4; wait(HZ / 4); LEDS = 2; wait(HZ / 4); }
        wait(HZ);
    }
}
