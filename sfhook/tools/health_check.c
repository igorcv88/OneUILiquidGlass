#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
static char health_path[] = "/tmp/oulg-health-XXXXXX";
#define OULG_HEALTH health_path
#include "../health.h"
#include "../restart_policy.h"
int main(void) {
    int fd = mkstemp(health_path); assert(fd >= 0); close(fd);
    // Some host sandboxes expose a parent PID namespace in /proc.
    int pid = 0;
    FILE *self = fopen("/proc/self/stat", "r"); assert(self);
    assert(fscanf(self, "%d", &pid) == 1); fclose(self);
    unsigned long long start = oulg_start(pid); assert(start > 0);
    int got = 0, rewrite = 0;
    assert(!oulg_health(pid, &got, &rewrite));
    FILE *f = fopen(health_path, "w"); assert(f);
    fprintf(f, "%d %llu 1 1\n", pid, start); fclose(f);
    assert(oulg_health(pid, &got, &rewrite) && got && rewrite);
    assert(!oulg_health(pid + 1, &got, &rewrite));
    f = fopen(health_path, "w"); assert(f);
    fprintf(f, "%d %llu 1 1\n", pid, start + 1); fclose(f);
    assert(!oulg_health(pid, &got, &rewrite));
    unlink(health_path);
    assert(!oulg_health(pid, &got, &rewrite));
    assert(oulg_retry_allowed(0, 0, 3000, 30000, 200));
    assert(!oulg_retry_allowed(0, 0, 500, 30000, 200));
    assert(!oulg_retry_allowed(1, 0, 3000, 30000, 200));
    assert(!oulg_retry_allowed(0, 1, 3000, 30000, 200));
    assert(!oulg_retry_allowed(0, 0, 30000, 30000, 200));
    assert(OULG_MONITOR_MS > OULG_RETRY_WINDOW_MS);
    puts("health generation and bounded retry policy: all checks passed");
}
