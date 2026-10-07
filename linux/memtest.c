// A memory test for the guest (SOC-ROADMAP S5): fills a large buffer with an address-dependent
// pseudo-random pattern, reads it back, then again with the pattern inverted, reporting the first
// mismatches and the throughput. Static, so the initramfs needs nothing else.
//
//   memtest [MiB] [passes]        default 256 MiB, 2 passes
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <time.h>

static uint64_t mix(uint64_t x) {
	x ^= x >> 33; x *= 0xff51afd7ed558ccdULL; x ^= x >> 33; x *= 0xc4ceb9fe1a85ec53ULL; x ^= x >> 33;
	return x;
}
static double now(void) {
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
	size_t mib = argc > 1 ? strtoul(argv[1], 0, 0) : 256;
	int passes = argc > 2 ? atoi(argv[2]) : 2;
	size_t n = mib << 17; // 64-bit words
	uint64_t *buf = mmap(0, n * 8, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE, -1, 0);
	if (buf == MAP_FAILED) { perror("mmap"); return 2; }
	unsigned long errors = 0;
	for (int p = 0; p < passes; p++) {
		uint64_t inv = (p & 1) ? ~0ULL : 0, seed = 0x9e3779b97f4a7c15ULL * (p + 1);
		double t0 = now();
		for (size_t i = 0; i < n; i++) buf[i] = mix(seed + i) ^ inv;
		double t1 = now();
		for (size_t i = 0; i < n; i++) {
			uint64_t want = mix(seed + i) ^ inv;
			if (buf[i] != want) {
				if (errors < 10)
					printf("memtest: pass %d word %zu (virt %p): read %016llx want %016llx\n", p, i, (void *)&buf[i],
					       (unsigned long long)buf[i], (unsigned long long)want);
				errors++;
			}
		}
		double t2 = now();
		printf("memtest: pass %d, %zu MiB: write %.1f MB/s, read+check %.1f MB/s, %lu errors so far\n", p, mib,
		       n * 8 / (t1 - t0) / 1e6, n * 8 / (t2 - t1) / 1e6, errors);
	}
	printf("memtest: %s (%lu errors)\n", errors ? "FAIL" : "PASS", errors);
	return errors != 0;
}
