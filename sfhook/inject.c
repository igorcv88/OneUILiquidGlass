// Route A (docs/sf-sem-overlay.md): loads liboulg_sf.so into a freshly restarted surfaceflinger with
// ptrace, without a mount, a patched binary or a change to /system. Nothing persists: the next
// surfaceflinger start (a reboot, a crash) runs the stock compositor.
//
// The library must be in before RenderEngine builds its GL interface, and the call must run where no
// lock is held. Both hold at the executable's entry point: linking is done, main() has not run, there
// is one thread. So the injector waits for the new process, stops it while the dynamic linker is
// still working, puts a BRK on AT_ENTRY, and calls the linker's __loader_dlopen when it traps there.
// If the process is already past its entry when it is caught, the injector detaches without touching
// it (exit 3) unless --late is given.
//
// Usage: oulg_inject --lib PATH [--probe] [--restart] [--clear-cache] [--late] [--daemon LOG] [--timeout S]
//   --selftest     the whole injection into a throwaway child (/system/bin/sleep) instead of
//                  surfaceflinger: nothing is stopped or restarted. Run it before --restart.
//   --version      the commit it was built from
//   --probe        read-only check on the running surfaceflinger: PTRACE_SEIZE and an immediate
//                  detach (the process is never stopped), its linker and entry, the library's label
//   --restart      stop surfaceflinger, start it, inject into the new one, and restart the zygote
//                  3 s after the start (restarts the whole userspace)
//   --clear-cache  delete skia_shaders/egl_shaders first, so programs are compiled from source
//   --daemon LOG   detach from the calling shell; with --restart it must leave the app cgroup too
//                  (verified in /proc/self/cgroup) or nothing is restarted
#define _GNU_SOURCE
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/uio.h>
#include <sys/xattr.h>
#include <sys/wait.h>
#include <asm/ptrace.h>

#define SF_EXE "/system/bin/surfaceflinger"
#define CACHE_DIR "/data/misc/surfaceflinger/"
#define BRK0 0xd4200000u
#ifndef OULG_VERSION
#define OULG_VERSION "dev"
#endif

static double t0;
static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}
static void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    printf("[%8.1f ms] ", now_ms() - t0);
    vprintf(fmt, ap);
    printf("\n");
    fflush(stdout);
    va_end(ap);
}

static int is_sf(pid_t pid) {
    char path[64], exe[256];
    snprintf(path, sizeof path, "/proc/%d/exe", pid);
    ssize_t n = readlink(path, exe, sizeof exe - 1);
    if (n <= 0) return 0;
    exe[n] = 0;
    return strcmp(exe, SF_EXE) == 0;
}
/** The surfaceflinger process other than {@code not}, or 0. */
static pid_t find_sf(pid_t not) {
    DIR *d = opendir("/proc");
    if (!d) return 0;
    pid_t found = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '1' || e->d_name[0] > '9') continue;
        pid_t pid = (pid_t) atoi(e->d_name);
        if (pid != not && is_sf(pid)) { found = pid; break; }
    }
    closedir(d);
    return found;
}

struct module { uintptr_t start, end; };
/** Load range of the first mapping whose path ends with {@code suffix}. */
static int find_module(pid_t pid, const char *suffix, struct module *m) {
    char path[64], line[512];
    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    int found = 0;
    size_t sl = strlen(suffix);
    while (fgets(line, sizeof line, f)) {
        uintptr_t a, b;
        char *p = strchr(line, '/');
        if (!p) continue;
        p[strcspn(p, "\n")] = 0;
        size_t pl = strlen(p);
        if (pl < sl || strcmp(p + pl - sl, suffix) != 0) continue;
        if (sscanf(line, "%lx-%lx", &a, &b) != 2) continue;
        if (!found) { m->start = a; m->end = b; found = 1; }
        else if (b > m->end) m->end = b;
    }
    fclose(f);
    return found;
}

static uintptr_t auxv_entry(pid_t pid) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/auxv", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    Elf64_auxv_t a;
    uintptr_t entry = 0;
    while (read(fd, &a, sizeof a) == sizeof a && a.a_type != AT_NULL) if (a.a_type == AT_ENTRY) entry = a.a_un.a_val;
    close(fd);
    return entry;
}

/** st_value of an exported symbol of an ELF file on disk, or 0. */
static uintptr_t elf_symbol(const char *file, const char *name) {
    int fd = open(file, O_RDONLY);
    if (fd < 0) return 0;
    struct stat st;
    uintptr_t value = 0;
    if (fstat(fd, &st) == 0) {
        unsigned char *buf = malloc((size_t) st.st_size);
        if (buf && read(fd, buf, (size_t) st.st_size) == st.st_size) {
            Elf64_Ehdr *eh = (Elf64_Ehdr *) buf;
            Elf64_Shdr *sh = (Elf64_Shdr *) (buf + eh->e_shoff);
            for (int i = 0; i < eh->e_shnum && !value; i++) {
                if (sh[i].sh_type != SHT_DYNSYM) continue;
                Elf64_Sym *sym = (Elf64_Sym *) (buf + sh[i].sh_offset);
                const char *str = (const char *) (buf + sh[sh[i].sh_link].sh_offset);
                for (size_t k = 0; k < sh[i].sh_size / sizeof *sym; k++)
                    if (sym[k].st_value && strcmp(str + sym[k].st_name, name) == 0) { value = sym[k].st_value; break; }
            }
        }
        free(buf);
    }
    close(fd);
    return value;
}
/** Path of the dynamic linker mapped into pid. */
static int linker_path(pid_t pid, char *out, size_t size) {
    char path[64], line[512];
    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    int ok = 0;
    while (fgets(line, sizeof line, f)) {
        char *p = strchr(line, '/');
        if (!p) continue;
        p[strcspn(p, "\n")] = 0;
        size_t n = strlen(p);
        if (n >= 9 && strcmp(p + n - 9, "/linker64") == 0) { snprintf(out, size, "%s", p); ok = 1; break; }
    }
    fclose(f);
    return ok;
}

static int thread_count(pid_t pid) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/task", pid);
    DIR *d = opendir(path);
    if (!d) return -1;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d))) if (e->d_name[0] >= '1' && e->d_name[0] <= '9') n++;
    closedir(d);
    return n;
}

static int get_regs(pid_t pid, struct user_pt_regs *r) {
    struct iovec io = { r, sizeof *r };
    return ptrace(PTRACE_GETREGSET, pid, (void *) NT_PRSTATUS, &io) == 0;
}
static int set_regs(pid_t pid, struct user_pt_regs *r) {
    struct iovec io = { r, sizeof *r };
    return ptrace(PTRACE_SETREGSET, pid, (void *) NT_PRSTATUS, &io) == 0;
}
static int poke(pid_t pid, uintptr_t addr, const void *data, size_t n) {
    for (size_t i = 0; i < n; i += 8) {
        long word = 0;
        size_t k = n - i < 8 ? n - i : 8;
        if (k < 8) {
            errno = 0;
            word = ptrace(PTRACE_PEEKDATA, pid, (void *) (addr + i), NULL);
            if (errno) return 0;
        }
        memcpy(&word, (const char *) data + i, k);
        if (ptrace(PTRACE_POKEDATA, pid, (void *) (addr + i), (void *) word) != 0) return 0;
    }
    return 1;
}
static void peek_string(pid_t pid, uintptr_t addr, char *out, size_t size) {
    size_t at = 0;
    while (at + 1 < size) {
        errno = 0;
        long word = ptrace(PTRACE_PEEKDATA, pid, (void *) (addr + at), NULL);
        if (errno) break;
        for (int i = 0; i < 8 && at + 1 < size; i++) {
            char c = (char) (word >> (8 * i));
            out[at++] = c;
            if (!c) return;
        }
    }
    out[at] = 0;
}

/** Logs the signal and the registers of a stopped pid: what a fault looked like, before anything else. */
static void log_stop(pid_t pid) {
    siginfo_t si;
    if (ptrace(PTRACE_GETSIGINFO, pid, NULL, &si) == 0)
        say("  siginfo signo=%d code=%d addr=%p", si.si_signo, si.si_code, si.si_addr);
    struct user_pt_regs r;
    if (get_regs(pid, &r))
        say("  regs pc=0x%llx sp=0x%llx lr=0x%llx pstate=0x%llx (btype=%llu) x0=0x%llx x1=0x%llx x2=0x%llx",
            (unsigned long long) r.pc, (unsigned long long) r.sp, (unsigned long long) r.regs[30], (unsigned long long) r.pstate,
            (unsigned long long) ((r.pstate & PSR_BTYPE_MASK) >> 10), (unsigned long long) r.regs[0], (unsigned long long) r.regs[1],
            (unsigned long long) r.regs[2]);
}

/**
 * Resumes pid until it stops with {@code want} (a signal-delivery stop). Returns the signal it
 * stopped with, or 0 if it died or the deadline passed. capture: any other signal also ends the
 * wait, undelivered, with the process stopped (a remote call that faulted: the fault is logged and
 * never handed to the process). Without capture other signals are logged and passed on.
 */
static int run_until(pid_t pid, int want, double deadline_ms, int capture) {
    if (ptrace(PTRACE_CONT, pid, NULL, NULL) != 0) { say("CONT failed: %s", strerror(errno)); return 0; }
    for (;;) {
        int st;
        pid_t w = waitpid(pid, &st, __WALL | WNOHANG);
        if (w == 0) {
            if (now_ms() > deadline_ms) { say("timed out waiting for signal %d", want); return 0; }
            usleep(200);
            continue;
        }
        if (w < 0 || WIFEXITED(st) || WIFSIGNALED(st)) { say("process gone (status 0x%x)", st); return 0; }
        if (!WIFSTOPPED(st)) continue;
        int sig = WSTOPSIG(st), event = st >> 16;
        if (event == PTRACE_EVENT_STOP) { ptrace(PTRACE_CONT, pid, NULL, NULL); continue; }
        if (sig == want) return sig;
        say("stopped by signal %d%s", sig, capture ? "; not delivered" : "; passing it on");
        log_stop(pid);
        if (capture) return sig;
        ptrace(PTRACE_CONT, pid, NULL, (void *) (long) sig);
    }
}

/**
 * Calls fn(a0, a1, a2) in a stopped pid and returns 1 with x0 in *ret. The call returns to address
 * 0 and stops there with SIGSEGV. On failure *fault is where it stopped (fn itself: nothing in the
 * target ran), or 0 if it died or did not stop in time.
 *
 * PSTATE.BTYPE is cleared, as a BLR would leave it for the callee. Stopped on the executable's
 * entry, the process carries BTYPE=0b11 from the linker's "br x0"; with that, the "bti c" that
 * opens __loader_dlopen raised a branch-target exception (SIGILL ILL_ILLOPC on DZIF, 26/10).
 */
static int remote_call(pid_t pid, const struct user_pt_regs *base, uintptr_t fn, uintptr_t a0, uintptr_t a1, uintptr_t a2,
                       uintptr_t *ret, uintptr_t *fault) {
    struct user_pt_regs r = *base;
    r.regs[0] = a0; r.regs[1] = a1; r.regs[2] = a2;
    r.regs[30] = 0;
    r.pc = fn;
    r.pstate &= ~(uint64_t) PSR_BTYPE_MASK;
    *fault = fn;
    if (!set_regs(pid, &r)) { say("SETREGSET failed: %s", strerror(errno)); return 0; }
    int sig = run_until(pid, SIGSEGV, now_ms() + 5000, 1);
    struct user_pt_regs out;
    if (!sig || !get_regs(pid, &out)) { *fault = 0; return 0; }
    if (sig == SIGSEGV && out.pc == 0) { *ret = out.regs[0]; return 1; }
    if (sig == SIGSEGV) log_stop(pid);
    *fault = out.pc;
    return 0;
}

/** Whether this process is out of every app cgroup (no "/uid_" in /proc/self/cgroup); logs them. */
static int out_of_app_cgroup(void) {
    FILE *f = fopen("/proc/self/cgroup", "r");
    if (!f) { say("cgroup: /proc/self/cgroup unreadable"); return 0; }
    char line[256];
    int ok = 1;
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\n")] = 0;
        say("cgroup: %s", line);
        if (strstr(line, "/uid_")) ok = 0;
    }
    fclose(f);
    return ok;
}

static void daemonize(const char *log) {
    pid_t p = fork();
    if (p < 0) exit(1);
    if (p > 0) { printf("daemon pid=%d log=%s\n", p, log); exit(0); }
    setsid();
    signal(SIGHUP, SIG_IGN);
    int fd = open(log, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); close(fd); }
    int nul = open("/dev/null", O_RDONLY);
    if (nul >= 0) { dup2(nul, 0); close(nul); }
}
/**
 * Out of the calling app's cgroup: restarting the userspace kills every process in it, and an
 * injector killed after stopping surfaceflinger would leave the zygote unrestarted.
 */
static int leave_app_cgroup(void) {
    int fd = open("/sys/fs/cgroup/cgroup.procs", O_WRONLY | O_CLOEXEC);
    if (fd < 0) say("cgroup: open /sys/fs/cgroup/cgroup.procs failed: %s", strerror(errno));
    else {
        char b[16];
        int n = snprintf(b, sizeof b, "%d", getpid());
        if (write(fd, b, (size_t) n) != n) say("cgroup: move to the root cgroup failed: %s", strerror(errno));
        close(fd);
    }
    return out_of_app_cgroup();
}

/** Polls every interval_us: 100 us while racing to the entry point, slower when only watching. */
static pid_t wait_new_sf(pid_t old, double deadline, useconds_t interval_us) {
    pid_t pid;
    while (!(pid = find_sf(old))) {
        if (now_ms() > deadline) { say("no new surfaceflinger in time"); return 0; }
        usleep(interval_us);
    }
    say("new surfaceflinger pid=%d", pid);
    return pid;
}
static void restart_zygote(double started) {
    double wait = started + 3000 - now_ms();
    if (wait > 0) usleep((useconds_t) (wait * 1000));
    say("ctl.restart zygote %s", __system_property_set("ctl.restart", "zygote") == 0 ? "sent" : "FAILED");
}

/**
 * Lets a stopped pid go. intact: its code and registers are back as they were. A process that is
 * not intact, or that cannot be detached (it would stay stopped while this injector runs on), is
 * killed instead, so init starts a clean surfaceflinger.
 */
static int release_target(pid_t pid, int intact) {
    if (intact && ptrace(PTRACE_DETACH, pid, NULL, NULL) == 0) return 1;
    if (intact) say("PTRACE_DETACH failed: %s", strerror(errno));
    say("killing pid %d: it cannot be left running as it is; init starts a clean surfaceflinger", pid);
    kill(pid, SIGKILL);
    return 0;
}

/** Exit codes: what failed, for the log and for scripts. */
enum { RC_OK = 0, RC_FAIL = 1, RC_USAGE = 2, RC_MISSED = 3, RC_CALL_FAULT = 4, RC_DLOPEN_NULL = 5 };

/** Puts back the registers the process had when it was stopped, read back to be sure. */
static int restore_regs(pid_t pid, struct user_pt_regs *saved) {
    struct user_pt_regs back;
    if (set_regs(pid, saved) && get_regs(pid, &back) && back.pc == saved->pc && back.sp == saved->sp && back.pstate == saved->pstate)
        return 1;
    say("restoring registers failed: %s", strerror(errno));
    return 0;
}

/**
 * The injection itself, on a stopped tracee. can_interrupt: attached with PTRACE_SEIZE (a
 * PTRACE_TRACEME child cannot be interrupted).
 */
static int inject_attached(pid_t pid, const char *lib, int late, int can_interrupt) {
    struct user_pt_regs regs;
    if (!get_regs(pid, &regs)) { say("GETREGSET failed: %s", strerror(errno)); release_target(pid, 1); return RC_FAIL; }
    struct module lk = {0, 0};
    char linker[256] = "";
    int have_linker = linker_path(pid, linker, sizeof linker) && find_module(pid, "/linker64", &lk);
    uintptr_t entry = auxv_entry(pid);
    int in_linker = have_linker && regs.pc >= lk.start && regs.pc < lk.end, threads = thread_count(pid);
    say("stopped pc=0x%llx pstate=0x%llx linker=%s [%lx-%lx] in_linker=%d threads=%d entry=0x%lx", (unsigned long long) regs.pc,
        (unsigned long long) regs.pstate, linker, lk.start, lk.end, in_linker, threads, entry);
    if (!have_linker || !entry) { say("linker or entry not found; detaching"); release_target(pid, 1); return RC_FAIL; }
    int st = 0;
    // One thread: still linking, running library constructors, or early in main(). A breakpoint
    // on the entry is reached only in the first two cases; it is removed again if it is not.
    if (threads == 1) {
        errno = 0;
        long orig = ptrace(PTRACE_PEEKTEXT, pid, (void *) entry, NULL);
        if (errno) { say("PEEKTEXT entry failed: %s", strerror(errno)); release_target(pid, 1); return RC_FAIL; }
        long trap = (long) (((unsigned long) orig & ~0xffffffffUL) | BRK0);
        if (ptrace(PTRACE_POKETEXT, pid, (void *) entry, (void *) trap) != 0) { say("POKETEXT entry failed: %s", strerror(errno)); release_target(pid, 1); return RC_FAIL; }
        int hit = run_until(pid, SIGTRAP, now_ms() + 3000, 0) == SIGTRAP;
        int stopped = hit;
        if (!hit && kill(pid, 0) == 0 && can_interrupt) {
            // Past the entry already, still running: stop it to take the breakpoint out.
            stopped = ptrace(PTRACE_INTERRUPT, pid, NULL, NULL) == 0 && waitpid(pid, &st, __WALL) == pid && WIFSTOPPED(st);
        }
        if (!stopped) { say("cannot stop the process to remove the breakpoint"); release_target(pid, 0); return RC_FAIL; }
        // The breakpoint must be gone, read back, before the process may run on.
        errno = 0;
        int restored = ptrace(PTRACE_POKETEXT, pid, (void *) entry, (void *) orig) == 0
                && ptrace(PTRACE_PEEKTEXT, pid, (void *) entry, NULL) == orig && errno == 0;
        if (!restored) { say("restoring the entry instruction failed: %s", strerror(errno)); release_target(pid, 0); return RC_FAIL; }
        if (!hit) { say("missed: entry breakpoint not reached in 3 s; removed (read back), detached (retry)"); return release_target(pid, 1) ? RC_MISSED : RC_FAIL; }
        if (!get_regs(pid, &regs) || regs.pc != entry) { say("trapped away from the entry (pc=0x%llx); detaching", (unsigned long long) regs.pc); release_target(pid, 1); return RC_FAIL; }
        say("at entry point pstate=0x%llx (btype=%llu)", (unsigned long long) regs.pstate, (unsigned long long) ((regs.pstate & PSR_BTYPE_MASK) >> 10));
    } else if (!late) {
        say("missed: the process already runs %d threads; detaching it untouched (retry, or --late)", threads);
        return release_target(pid, 1) ? RC_MISSED : RC_FAIL;
    } else {
        say("late injection at pc=0x%llx (a lock held here would deadlock the process)", (unsigned long long) regs.pc);
    }
    uintptr_t dlopen_off = elf_symbol(linker, "__loader_dlopen"), dlerror_off = elf_symbol(linker, "__loader_dlerror");
    if (!dlopen_off) { say("__loader_dlopen not found in %s; detaching", linker); release_target(pid, 1); return RC_FAIL; }
    struct user_pt_regs saved = regs, call = regs;
    // Scratch below the stack pointer, 16-byte aligned; the path sits above the callee's frame area.
    call.sp = (regs.sp - 0x2000) & ~(uint64_t) 0xf;
    uintptr_t path_at = call.sp + 0x1000;
    if (!poke(pid, path_at, lib, strlen(lib) + 1)) { say("writing the path failed: %s", strerror(errno)); release_target(pid, 1); return RC_FAIL; }
    uintptr_t handle = 0, fault = 0, fn = lk.start + dlopen_off;
    // caller = the entry point: the library joins the executable's own linker namespace.
    int ok = remote_call(pid, &call, fn, path_at, 2 /* RTLD_NOW */, entry, &handle, &fault);
    char err[512] = "";
    if (ok && !handle && dlerror_off) {
        uintptr_t msg = 0, efn = lk.start + dlerror_off;
        if (remote_call(pid, &call, efn, 0, 0, 0, &msg, &fault) && msg) peek_string(pid, msg, err, sizeof err);
        else if (fault != efn) ok = 0;
    }
    if (!ok) {
        // Fail closed: only a fault on the called function's first instruction proves that nothing
        // in the target ran (no lock taken, no memory changed); that process is put back as it was.
        int clean = fault == fn;
        say("remote call failed at pc=0x%lx (%s)", fault, clean ? "its first instruction: nothing ran" : "inside the target, or no stop");
        if (clean && restore_regs(pid, &saved) && release_target(pid, 1)) say("process restored and released untouched");
        else if (!clean) release_target(pid, 0);
        return RC_CALL_FAULT;
    }
    if (!release_target(pid, restore_regs(pid, &saved))) return RC_FAIL;
    if (!handle) { say("dlopen returned NULL: %s", err[0] ? err : "(no dlerror)"); return RC_DLOPEN_NULL; }
    // dlopen succeeded, nothing more: whether the GOT was hooked, the renderer is GLES and the
    // rewrite runs is in logcat -s OULG_SF (GOT_DONE, SRC, REWRITE) and the shader dump.
    say("DLOPEN_OK handle=0x%lx; detached. Not yet proof of interception: check logcat -s OULG_SF for GOT_DONE", handle);
    return RC_OK;
}

static int inject_into(pid_t pid, const char *lib, int late) {
    if (ptrace(PTRACE_SEIZE, pid, NULL, NULL) != 0) { say("PTRACE_SEIZE failed: %s (SELinux or DEFEX; check dmesg | grep avc)", strerror(errno)); return RC_FAIL; }
    int st = 0;
    if (ptrace(PTRACE_INTERRUPT, pid, NULL, NULL) != 0 || waitpid(pid, &st, __WALL) != pid || !WIFSTOPPED(st)) {
        // Not stopped: it cannot be detached; the kernel detaches it when this injector exits.
        say("no stop after interrupt (0x%x, %s)", st, strerror(errno));
        return RC_FAIL;
    }
    return inject_attached(pid, lib, late, 1);
}

/** Path of a mapping of pid whose path contains {@code needle}, logged; whether there is one. */
static int mapped(pid_t pid, const char *needle) {
    char path[64], line[512];
    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    int found = 0;
    while (!found && fgets(line, sizeof line, f)) if (strstr(line, needle)) { line[strcspn(line, "\n")] = 0; say("  mapped: %s", line); found = 1; }
    fclose(f);
    return found;
}

/**
 * --selftest: the same injection, into a throwaway process instead of surfaceflinger. A child
 * execs SELFTEST_EXE under PTRACE_TRACEME and stops in the dynamic linker, as surfaceflinger is
 * caught; the library is loaded at its entry point, and its mapping is checked before the child is
 * killed. Same linker, same BTI/PAC state, nothing restarted.
 */
#define SELFTEST_EXE "/system/bin/sleep"
/** Written by a passing --selftest with the build that passed it; --restart requires it. */
#define SELFTEST_STAMP "/data/local/tmp/oulg_selftest.ok"
static int selftest_passed(void) {
    char buf[64] = "";
    int fd = open(SELFTEST_STAMP, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = 0;
    buf[strcspn(buf, "\n")] = 0;
    return strcmp(buf, OULG_VERSION) == 0;
}
static int selftest(const char *lib) {
    pid_t child = fork();
    if (child < 0) { say("fork failed: %s", strerror(errno)); return RC_FAIL; }
    if (child == 0) {
        ptrace(PTRACE_TRACEME, 0, NULL, NULL);
        execl(SELFTEST_EXE, "sleep", "10", (char *) NULL);
        _exit(127);
    }
    int st = 0;
    if (waitpid(child, &st, __WALL) != child || !WIFSTOPPED(st)) { say("selftest child did not stop at exec (0x%x)", st); kill(child, SIGKILL); return RC_FAIL; }
    say("selftest child pid=%d (%s) stopped at exec", child, SELFTEST_EXE);
    int rc = inject_attached(child, lib, 0, 0);
    usleep(100000);
    int there = kill(child, 0) == 0 && mapped(child, lib);
    int alive = kill(child, 0) == 0;
    kill(child, SIGKILL);
    waitpid(child, &st, 0);
    int pass = rc == RC_OK && there && alive;
    say("SELFTEST %s rc=%d child_alive=%d lib_mapped=%d", pass ? "OK" : "FAILED", rc, alive, there);
    unlink(SELFTEST_STAMP);
    if (pass) {
        int fd = open(SELFTEST_STAMP, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd >= 0) { dprintf(fd, "%s\n", OULG_VERSION); close(fd); }
    }
    return pass ? RC_OK : (rc ? rc : RC_FAIL);
}

int main(int argc, char **argv) {
    const char *lib = NULL, *log = NULL;
    int restart = 0, clear = 0, late = 0, probe = 0, self = 0, timeout = 120;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--lib") && i + 1 < argc) lib = argv[++i];
        else if (!strcmp(argv[i], "--restart")) restart = 1;
        else if (!strcmp(argv[i], "--probe")) probe = 1;
        else if (!strcmp(argv[i], "--selftest")) self = 1;
        else if (!strcmp(argv[i], "--version")) { printf("oulg_inject %s\n", OULG_VERSION); return RC_OK; }
        else if (!strcmp(argv[i], "--clear-cache")) clear = 1;
        else if (!strcmp(argv[i], "--late")) late = 1;
        else if (!strcmp(argv[i], "--daemon") && i + 1 < argc) log = argv[++i];
        else if (!strcmp(argv[i], "--timeout") && i + 1 < argc) timeout = atoi(argv[++i]);
        else { fprintf(stderr, "unknown argument %s\n", argv[i]); return 2; }
    }
    if (!lib || lib[0] != '/') { fprintf(stderr, "--lib needs an absolute path\n"); return 2; }
    if (access(lib, R_OK) != 0) { fprintf(stderr, "cannot read %s: %s\n", lib, strerror(errno)); return 2; }
    if (restart && !selftest_passed()) { fprintf(stderr, "--restart needs a passing --selftest of this build (%s) first\n", OULG_VERSION); return 2; }
    if (restart && !log) { fprintf(stderr, "--restart needs --daemon LOG: the restart kills the calling shell\n"); return 2; }
    if (log) daemonize(log);
    t0 = now_ms();
    say("oulg_inject %s", OULG_VERSION);
    if (self) return selftest(lib);
    if (restart && !leave_app_cgroup()) { say("still in an app cgroup: refusing to restart (this injector would die with the userspace)"); return 1; }
    pid_t old = find_sf(0);
    say("lib=%s running surfaceflinger pid=%d", lib, old);
    if (probe) {
        char label[256] = "";
        ssize_t n = getxattr(lib, "security.selinux", label, sizeof label - 1);
        say("lib label=%s", n > 0 ? label : "(unreadable)");
        if (!old) { say("no surfaceflinger running"); return 1; }
        char linker[256] = "";
        struct module lk = {0, 0};
        int have = linker_path(old, linker, sizeof linker) && find_module(old, "/linker64", &lk);
        say("maps readable=%d linker=%s entry=0x%lx __loader_dlopen=0x%lx __loader_dlerror=0x%lx threads=%d", have, linker,
            auxv_entry(old), have ? elf_symbol(linker, "__loader_dlopen") : 0, have ? elf_symbol(linker, "__loader_dlerror") : 0, thread_count(old));
        if (ptrace(PTRACE_SEIZE, old, NULL, NULL) != 0) { say("PTRACE_SEIZE refused: %s (check dmesg | grep -i avc)", strerror(errno)); return 1; }
        // PTRACE_DETACH needs a ptrace-stop: the main thread is stopped for the detach alone.
        double s0 = now_ms();
        int st = 0;
        if (ptrace(PTRACE_INTERRUPT, old, NULL, NULL) != 0 || waitpid(old, &st, __WALL) != old || !WIFSTOPPED(st)) {
            say("PTRACE_SEIZE allowed, but no ptrace-stop (%s); detached by the kernel when this probe exits", strerror(errno));
            return 1;
        }
        if (ptrace(PTRACE_DETACH, old, NULL, NULL) != 0) { say("PTRACE_DETACH failed: %s; detached by the kernel when this probe exits", strerror(errno)); return 1; }
        say("PTRACE_SEIZE allowed; main thread stopped %.2f ms and detached (PTRACE_DETACH returned 0)", now_ms() - s0);
        return 0;
    }
    // --restart: the sequence of handoff.md (stop surfaceflinger, start it, zygote 3 s later). A
    // restart of surfaceflinger alone left the device 4-5 min on "Powered by Android" until a
    // watchdog restarted the zygote; with the zygote restarted at once it took ~20 s.
    if (restart) {
        pid_t running = old;
        if (__system_property_set("ctl.stop", "surfaceflinger") != 0) { say("setprop ctl.stop failed"); return 1; }
        double stop_deadline = now_ms() + 10000;
        while (find_sf(0) && now_ms() < stop_deadline) usleep(1000);
        if (find_sf(0)) {
            // Not stopped: nothing is cleared or injected. The service is asked to run; if it stops
            // late and init starts a new one, the zygote gets its restart as in a normal one.
            // A stop that completes later would leave no compositor: for 30 s, a missing
            // surfaceflinger is started again, and a new one gets the zygote restart.
            say("surfaceflinger did not stop within 10 s; aborting, no cache cleared, no injection");
            double until = now_ms() + 30000, last_start = 0;
            for (;;) {
                pid_t cur = find_sf(0);
                if (cur && cur != running) { say("surfaceflinger came back as pid=%d", cur); restart_zygote(now_ms()); break; }
                if (!cur && now_ms() - last_start > 1000) {
                    say("surfaceflinger absent; ctl.start %s", __system_property_set("ctl.start", "surfaceflinger") == 0 ? "sent" : "FAILED");
                    last_start = now_ms();
                }
                if (now_ms() > until) { say(cur == running ? "surfaceflinger pid=%d still running; nothing changed" : "surfaceflinger still absent after 30 s (pid %d stopped)", running); break; }
                usleep(10000);
            }
            return 1;
        }
        say("surfaceflinger stopped");
        old = 0;
    }
    if (clear) {
        static const char *caches[] = { CACHE_DIR "skia_shaders", CACHE_DIR "egl_shaders" };
        for (int i = 0; i < 2; i++) {
            if (unlink(caches[i]) == 0) say("deleted %s", caches[i]);
            else say("not deleted %s: %s", caches[i], strerror(errno));
        }
    }
    double started = now_ms();
    if (restart) {
        if (__system_property_set("ctl.start", "surfaceflinger") != 0) { say("setprop ctl.start failed"); return 1; }
        say("ctl.start surfaceflinger sent");
    }
    pid_t pid = wait_new_sf(old, now_ms() + timeout * 1e3, 100);
    if (!pid) { if (restart) restart_zygote(started); return 1; }
    int rc = inject_into(pid, lib, late);
    if (!restart) return rc;
    // The zygote is restarted whatever the injection did: without it the device hangs on the boot
    // animation. If that restart takes surfaceflinger down again, the new one is injected too.
    restart_zygote(started);
    // A surfaceflinger the zygote restarts is caught at 100 us too: the race is the same.
    pid_t again = wait_new_sf(pid, now_ms() + 30000, 100);
    // Only after a clean injection: a failed one is not retried on the new process.
    if (again && rc == 0) { say("surfaceflinger restarted with the zygote"); rc = inject_into(again, lib, late); }
    else if (again) say("surfaceflinger restarted with the zygote; previous injection failed, not retried");
    else say("surfaceflinger pid=%d kept through the zygote restart", pid);
    return rc;
}
