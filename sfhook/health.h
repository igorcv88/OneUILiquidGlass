#ifndef OULG_HEALTH_H
#define OULG_HEALTH_H
#include <stdio.h>
#include <sys/types.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifndef OULG_HEALTH
#define OULG_HEALTH "/data/misc/surfaceflinger/oulg_health"
#endif
static unsigned long long oulg_start(pid_t pid) {
    char path[64], line[4096];
    snprintf(path, sizeof path, "/proc/%d/stat", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char *ok = fgets(line, sizeof line, f); fclose(f);
    if (!ok) return 0;
    char *p = strrchr(line, ')');
    if (!p) return 0;
    p += 2;
    for (int field = 3; field < 22; field++) {
        p = strchr(p, ' '); if (!p) return 0; p++;
    }
    return strtoull(p, NULL, 10);
}
static inline int oulg_health(pid_t pid, int *got, int *rewrite) {
    int owner = 0; unsigned long long start = 0;
    FILE *f = fopen(OULG_HEALTH, "r");
    if (!f) return 0;
    int n = fscanf(f, "%d %llu %d %d", &owner, &start, got, rewrite); fclose(f);
    return n == 4 && owner == pid && start && start == oulg_start(pid);
}
#endif
