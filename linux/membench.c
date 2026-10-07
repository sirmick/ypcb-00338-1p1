// Main-memory bandwidth for the guest (SOC-ROADMAP S5): streams a buffer far larger than the caches and
// times four access patterns. "line read" touches one word per 64-byte line, so it measures how fast
// lines arrive; the others add the core's own work per word. Static, for the initramfs.
//
//   membench [MiB]        default 64 MiB
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>

static double now(void) {
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec + t.tv_nsec / 1e9;
}
static void report(const char *what, size_t bytes, double secs) {
	printf("membench: %-10s %7.1f MB/s\n", what, bytes / secs / 1e6);
}

int main(int argc, char **argv) {
	size_t mib = argc > 1 ? strtoul(argv[1], 0, 0) : 64, bytes = mib << 20, n = bytes / 8;
	uint64_t *a = mmap(0, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE, -1, 0);
	uint64_t *b = mmap(0, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE, -1, 0);
	if (a == MAP_FAILED || b == MAP_FAILED) { perror("mmap"); return 2; }
	volatile uint64_t sink = 0;
	uint64_t s = 0;
	double t;

	t = now(); memset(a, 0x5a, bytes); report("write", bytes, now() - t);
	t = now(); for (size_t i = 0; i < n; i += 8) s += a[i]; sink = s; report("line read", bytes, now() - t);
	t = now(); s = 0; for (size_t i = 0; i < n; i++) s += a[i]; sink = s; report("read", bytes, now() - t);
	t = now(); memcpy(b, a, bytes); report("copy", 2 * bytes, now() - t);
	(void)sink;
	return 0;
}
