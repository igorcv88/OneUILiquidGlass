#ifndef OULG_RESTART_POLICY_H
#define OULG_RESTART_POLICY_H
#define OULG_MONITOR_MS 120000
#define OULG_RETRY_WINDOW_MS 30000
// At-entry injection cannot wait for backoff: reject a generation caught during cooldown.
// One retry total, only after a clean first injection, never after a failed attach.
static inline int oulg_retry_allowed(int failed, int retries, double now,
                                     double retry_until, double last_injection) {
    return !failed && retries == 0 && now < retry_until && now - last_injection >= 1000;
}
#endif
