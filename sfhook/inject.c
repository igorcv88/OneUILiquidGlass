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
//   --probe        read-only check on the running surfaceflinger: PTRACE_SEIZE and an immediate
//                  detach (the process is never stopped), its linker and entry, the library's label
//   --restart      record the running surfaceflinger, then setprop ctl.restart surfaceflinger
//                  (restarts the whole userspace) and inject into the new one
//   --clear-cache  delete skia_shaders/egl_shaders first, so programs are compiled from source
//   --daemon LOG   detach from the calling shell (and its app cgroup, killed with the userspace)
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

/**
 * Resumes pid until it stops with {@code want} (a signal-delivery stop), passing other signals on.
 * Returns 1 on that stop, 0 if the process died or the deadline passed.
 */
static int run_until(pid_t pid, int want, double deadline_ms) {
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
        if (sig == want) return 1;
        say("passing signal %d", sig);
        ptrace(PTRACE_CONT, pid, NULL, (void *) (long) sig);
    }
}

/** Calls fn(a0, a1, a2) in pid, returning x0; the call returns to address 0 and traps. */
static int remote_call(pid_t pid, const struct user_pt_regs *base, uintptr_t fn, uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t *ret) {
    struct user_pt_regs r = *base;
    r.regs[0] = a0; r.regs[1] = a1; r.regs[2] = a2;
    r.regs[30] = 0;
    r.pc = fn;
    if (!set_regs(pid, &r)) { say("SETREGSET failed: %s", strerror(errno)); return 0; }
    if (!run_until(pid, SIGSEGV, now_ms() + 5000)) return 0;
    struct user_pt_regs out;
    if (!get_regs(pid, &out)) return 0;
    if (out.pc != 0) { say("call faulted inside the target at pc=0x%llx", (unsigned long long) out.pc); return 0; }
    *ret = out.regs[0];
    return 1;
}

static void daemonize(const char *log) {
    pid_t p = fork();
    if (p < 0) exit(1);
    if (p > 0) { printf("daemon pid=%d log=%s\n", p, log); exit(0); }
    setsid();
    signal(SIGHUP, SIG_IGN);
    // Out of the calling app's cgroup: restarting the userspace kills every process in it.
    int fd = open("/sys/fs/cgroup/cgroup.procs", O_WRONLY | O_CLOEXEC);
    if (fd >= 0) { char b[16]; int n = snprintf(b, sizeof b, "%d", getpid()); write(fd, b, (size_t) n); close(fd); }
    fd = open(log, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); close(fd); }
    int nul = open("/dev/null", O_RDONLY);
    if (nul >= 0) { dup2(nul, 0); close(nul); }
}

int main(int argc, char **argv) {
    const char *lib = NULL, *log = NULL;
    int restart = 0, clear = 0, late = 0, probe = 0, timeout = 120;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--lib") && i + 1 < argc) lib = argv[++i];
        else if (!strcmp(argv[i], "--restart")) restart = 1;
        else if (!strcmp(argv[i], "--probe")) probe = 1;
        else if (!strcmp(argv[i], "--clear-cache")) clear = 1;
        else if (!strcmp(argv[i], "--late")) late = 1;
        else if (!strcmp(argv[i], "--daemon") && i + 1 < argc) log = argv[++i];
        else if (!strcmp(argv[i], "--timeout") && i + 1 < argc) timeout = atoi(argv[++i]);
        else { fprintf(stderr, "unknown argument %s\n", argv[i]); return 2; }
    }
    if (!lib || lib[0] != '/') { fprintf(stderr, "--lib needs an absolute path\n"); return 2; }
    if (access(lib, R_OK) != 0) { fprintf(stderr, "cannot read %s: %s\n", lib, strerror(errno)); return 2; }
    if (log) daemonize(log);
    t0 = now_ms();
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
        ptrace(PTRACE_DETACH, old, NULL, NULL);
        say("PTRACE_SEIZE allowed; detached without stopping it");
        return 0;
    }
    if (clear) {
        unlink(CACHE_DIR "skia_shaders");
        unlink(CACHE_DIR "egl_shaders");
        say("shader caches deleted");
    }
    if (restart) {
        if (__system_property_set("ctl.restart", "surfaceflinger") != 0) { say("setprop ctl.restart failed"); return 1; }
        say("ctl.restart surfaceflinger sent");
    }
    pid_t pid = 0;
    double deadline = now_ms() + timeout * 1e3;
    while (!(pid = find_sf(old))) {
        if (now_ms() > deadline) { say("no new surfaceflinger within %d s", timeout); return 1; }
        usleep(100);
    }
    say("new surfaceflinger pid=%d", pid);
    if (ptrace(PTRACE_SEIZE, pid, NULL, NULL) != 0) { say("PTRACE_SEIZE failed: %s (SELinux or DEFEX; check dmesg | grep avc)", strerror(errno)); return 1; }
    ptrace(PTRACE_INTERRUPT, pid, NULL, NULL);
    int st;
    if (waitpid(pid, &st, __WALL) != pid || !WIFSTOPPED(st)) { say("no stop after interrupt (0x%x)", st); return 1; }
    struct user_pt_regs regs;
    if (!get_regs(pid, &regs)) { say("GETREGSET failed: %s", strerror(errno)); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
    struct module lk = {0, 0}, exe = {0, 0};
    char linker[256] = "";
    int have_linker = linker_path(pid, linker, sizeof linker) && find_module(pid, "/linker64", &lk);
    find_module(pid, SF_EXE, &exe);
    uintptr_t entry = auxv_entry(pid);
    int in_linker = have_linker && regs.pc >= lk.start && regs.pc < lk.end, threads = thread_count(pid);
    say("stopped pc=0x%llx linker=%s [%lx-%lx] in_linker=%d threads=%d entry=0x%lx", (unsigned long long) regs.pc, linker, lk.start, lk.end, in_linker, threads, entry);
    if (!have_linker || !entry) { say("linker or entry not found; detaching"); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
    // One thread: still linking, running library constructors, or early in main(). A breakpoint
    // on the entry is reached only in the first two cases; it is removed again if it is not.
    if (threads == 1) {
        errno = 0;
        long orig = ptrace(PTRACE_PEEKTEXT, pid, (void *) entry, NULL);
        if (errno) { say("PEEKTEXT entry failed: %s", strerror(errno)); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
        long trap = (long) (((unsigned long) orig & ~0xffffffffUL) | BRK0);
        if (ptrace(PTRACE_POKETEXT, pid, (void *) entry, (void *) trap) != 0) { say("POKETEXT entry failed: %s", strerror(errno)); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
        int hit = run_until(pid, SIGTRAP, now_ms() + 3000);
        if (!hit && kill(pid, 0) == 0) {
            // Past the entry already, still running: stop it to take the breakpoint out.
            ptrace(PTRACE_INTERRUPT, pid, NULL, NULL);
            waitpid(pid, &st, __WALL);
        }
        ptrace(PTRACE_POKETEXT, pid, (void *) entry, (void *) orig);
        if (!hit) { say("missed: entry breakpoint not reached in 3 s; removed, detached (retry)"); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 3; }
        if (!get_regs(pid, &regs) || regs.pc != entry) { say("trapped away from the entry (pc=0x%llx); detaching", (unsigned long long) regs.pc); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
        say("at entry point");
    } else if (!late) {
        say("missed: surfaceflinger already runs %d threads; detached untouched (retry, or --late)", threads);
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        return 3;
    } else {
        say("late injection at pc=0x%llx (a lock held here would deadlock surfaceflinger)", (unsigned long long) regs.pc);
    }
    uintptr_t dlopen_off = elf_symbol(linker, "__loader_dlopen"), dlerror_off = elf_symbol(linker, "__loader_dlerror");
    if (!dlopen_off) { say("__loader_dlopen not found in %s; detaching", linker); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
    struct user_pt_regs saved = regs, call = regs;
    // Scratch below the stack pointer, 16-byte aligned; the path sits above the callee's frame area.
    call.sp = (regs.sp - 0x2000) & ~(uint64_t) 0xf;
    uintptr_t path_at = call.sp + 0x1000;
    if (!poke(pid, path_at, lib, strlen(lib) + 1)) { say("writing the path failed: %s", strerror(errno)); ptrace(PTRACE_DETACH, pid, NULL, NULL); return 1; }
    uintptr_t handle = 0;
    // caller = the entry point: the library joins surfaceflinger's own linker namespace.
    int ok = remote_call(pid, &call, lk.start + dlopen_off, path_at, 2 /* RTLD_NOW */, entry, &handle);
    char err[512] = "";
    if (ok && !handle && dlerror_off) {
        uintptr_t msg = 0;
        if (remote_call(pid, &call, lk.start + dlerror_off, 0, 0, 0, &msg) && msg) peek_string(pid, msg, err, sizeof err);
    }
    if (!set_regs(pid, &saved)) say("restoring registers failed: %s", strerror(errno));
    ptrace(PTRACE_DETACH, pid, NULL, NULL);
    if (!ok) { say("dlopen call failed; surfaceflinger may crash and restart"); return 1; }
    if (!handle) { say("dlopen returned NULL: %s", err[0] ? err : "(no dlerror)"); return 1; }
    say("INJECTED handle=0x%lx; detached. Check: logcat -s OULG_SF", handle);
    return 0;
}
