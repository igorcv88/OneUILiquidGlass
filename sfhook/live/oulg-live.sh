#!/system/bin/sh
# Root helper for the app's SurfaceFlinger live control (route A, docs/sf-live-app.md). It is the only
# thing the app runs as root for this feature: a fixed set of commands, no shell passed through.
# Output is key=value lines on stdout; exit 0 ok, 1 failed, 2 refused (precondition or usage).
#
#   oulg-live.sh status          what is bundled, installed, self-tested and loaded now
#   oulg-live.sh install         copies the bundle next to this script into place (atomically)
#   oulg-live.sh selftest        injection into a throwaway process; required before restart
#   oulg-live.sh restart MODE    MODE rewrite (lens) or dump (shaders only): restarts the userspace
#   oulg-live.sh log             the last selftest and injection logs
#   oulg-live.sh uninstall       removes the installed files; the running compositor keeps them
#                                mapped until it restarts
#
# The bundle directory is this script's own (the app extracts its assets there), or $OULG_SRC.

# OULG_BIN / OULG_LIBDIR exist for the host test (tools/live_check.sh) only.
BIN=${OULG_BIN:-/data/local/tmp/oulg}
LIBDIR=${OULG_LIBDIR:-/data/misc/surfaceflinger}
LIB=$LIBDIR/liboulg_sf.so
INJ=$BIN/oulg_inject
LOG=$BIN/inject.log
SELFLOG=$BIN/selftest.log
PROCDIR=${OULG_PROC:-/proc}
SRC=${OULG_SRC:-$(cd "$(dirname "$0")" && pwd)}

out() { echo "$1=$2"; }
die() { out error "$2"; exit "$1"; }
prop() { [ -f "$2" ] && sed -n "s/^$1=//p" "$2" | head -n 1; }
sha() { [ -f "$1" ] && sha256sum "$1" | cut -d ' ' -f 1; }
sf_pid() { pidof surfaceflinger | cut -d ' ' -f 1; }

[ "$(id -u)" = 0 ] || die 2 "not_root"

legacy_conflict() {
    for module in /data/adb/modules/oulg_sf /data/adb/modules_update/oulg_sf; do
        [ -d "$module" ] && [ ! -e "$module/disable" ] && [ ! -e "$module/remove" ] && return 0
    done
    return 1
}

fingerprint_ok() {
    fp="$(getprop ro.build.fingerprint)"
    [ -n "$fp" ] && [ -f "$SRC/allowed_fingerprints" ] && grep -v '^#' "$SRC/allowed_fingerprints" | grep -qxF "$fp"
}
# Reject incomplete/malformed manifests instead of comparing two empty strings as equal.
match_sha() {
    expected="$(prop "$2" "$3")"
    [ "${#expected}" -eq 64 ] || return 1
    case "$expected" in *[!0-9a-f]*) return 1;; esac
    [ -f "$1" ] && [ "$(sha "$1")" = "$expected" ]
}
bundle_ok() {
    [ -f "$SRC/live.prop" ] || return 1
    match_sha "$SRC/oulg_inject" sha256_inject "$SRC/live.prop" || return 1
    match_sha "$SRC/liboulg_sf.so" sha256_lib "$SRC/live.prop" || return 1
    match_sha "$SRC/oulg-live.sh" sha256_helper "$SRC/live.prop" || return 1
}
installed_ok() {
    [ -f "$BIN/live.prop" ] || return 1
    match_sha "$INJ" sha256_inject "$BIN/live.prop" || return 1
    match_sha "$LIB" sha256_lib "$BIN/live.prop" || return 1
}
selftest_ok() { [ -x "$INJ" ] && [ -f "$LIB" ] && "$INJ" --lib "$LIB" --stamp-status >/dev/null 2>&1; }

cmd="$1"
case "$cmd" in
status)
    out helper 1
    legacy_conflict && out legacy_conflict 1 || out legacy_conflict 0
    out fingerprint "$(getprop ro.build.fingerprint)"
    fingerprint_ok && out fingerprint_ok 1 || out fingerprint_ok 0
    bundle_ok && out bundled_ok 1 || out bundled_ok 0
    out bundled_version "$(prop version "$SRC/live.prop")"
    out bundled_versionCode "$(prop versionCode "$SRC/live.prop")"
    out bundled_commit "$(prop commit "$SRC/live.prop")"
    out installed_version "$(prop version "$BIN/live.prop")"
    out installed_versionCode "$(prop versionCode "$BIN/live.prop")"
    out installed_commit "$(prop commit "$BIN/live.prop")"
    installed_ok && out installed_ok 1 || out installed_ok 0
    # Installed is current only if its hashes are the bundle's: a version bump forgotten in
    # version.prop still shows up as an update.
    if installed_ok && [ "$(prop sha256_inject "$BIN/live.prop")" = "$(prop sha256_inject "$SRC/live.prop")" ] \
        && [ "$(prop sha256_lib "$BIN/live.prop")" = "$(prop sha256_lib "$SRC/live.prop")" ]; then
        out update_needed 0
    else
        out update_needed 1
    fi
    selftest_ok && out selftest_ok 1 || out selftest_ok 0
    p="$(sf_pid)"
    out sf_pid "$p"
    # The library file as installed now: after a reinstall the running compositor still maps the
    # old inode, which /proc shows as "(deleted)"; that is not the current library.
    inode="$(stat -c '%i' "$LIB" 2>/dev/null)"
    if [ -n "$p" ] && [ -n "$inode" ] && awk -v file="$LIB" -v inode="$inode" '
        NF == 6 && $6 == file && ("i" $5) == ("i" inode) { found = 1; exit }
        END { exit !found }
    ' "$PROCDIR/$p/maps" 2>/dev/null; then out sf_loaded 1; else out sf_loaded 0; fi
    [ "$(getprop debug.oulg.sf.norewrite)" = 1 ] && out sf_rewrite 0 || out sf_rewrite 1
    out sf_optics "$(getprop debug.oulg.sf.optics)"
    out sfrefract "$(getprop debug.oulg.sfrefract)"
    exit 0
    ;;
install)
    fingerprint_ok || die 2 "fingerprint_not_allowed"
    legacy_conflict && die 2 "legacy_module_active_disable_it_and_reboot_first"
    bundle_ok || die 2 "bundle_corrupt"
    mkdir -p "$BIN" || die 1 "mkdir_failed"
    # New files are written beside the old ones and renamed over them: surfaceflinger keeps the
    # library it loaded mapped, and writing into that inode would change its code under it.
    cp "$SRC/oulg_inject" "$BIN/.oulg_inject.new" && chmod 755 "$BIN/.oulg_inject.new" \
        && mv -f "$BIN/.oulg_inject.new" "$INJ" || die 1 "inject_copy_failed"
    cp "$SRC/liboulg_sf.so" "$LIBDIR/.liboulg_sf.so.new" && chmod 644 "$LIBDIR/.liboulg_sf.so.new" \
        && chcon u:object_r:system_lib_file:s0 "$LIBDIR/.liboulg_sf.so.new" \
        && mv -f "$LIBDIR/.liboulg_sf.so.new" "$LIB" || die 1 "lib_copy_failed"
    cp "$SRC/live.prop" "$BIN/live.prop" || die 1 "manifest_copy_failed"
    installed_ok || die 1 "installed_hash_mismatch"
    out install ok
    out version "$(prop version "$BIN/live.prop")"
    exit 0
    ;;
selftest)
    installed_ok || die 2 "not_installed"
    "$INJ" --lib "$LIB" --selftest > "$SELFLOG" 2>&1
    rc=$?
    [ $rc -eq 0 ] && out selftest ok || out selftest failed
    out rc "$rc"
    out log "$SELFLOG"
    [ $rc -eq 0 ] || exit 1
    exit 0
    ;;
restart)
    mode="$2"
    case "$mode" in rewrite|dump) ;; *) die 2 "usage_restart_rewrite_or_dump" ;; esac
    fingerprint_ok || die 2 "fingerprint_not_allowed"
    legacy_conflict && die 2 "legacy_module_active_disable_it_and_reboot_first"
    installed_ok || die 2 "not_installed"
    selftest_ok || die 2 "selftest_required"
    if [ "$mode" = dump ]; then setprop debug.oulg.sf.norewrite 1; else setprop debug.oulg.sf.norewrite ""; fi
    rm -f "$LIBDIR/oulg_shaders.txt"
    # The injector detaches itself, leaves the app's cgroup and restarts the userspace; it sets
    # debug.oulg.sfrefract=1 only after surfaceflinger loaded the library, and clears it otherwise.
    extra=""
    [ "$mode" = rewrite ] && extra="--sfrefract"
    "$INJ" --lib "$LIB" --clear-cache --restart $extra --daemon "$LOG" >/dev/null 2>&1
    rc=$?
    [ $rc -eq 0 ] || die 1 "injector_refused_rc_$rc"
    out restart started
    out log "$LOG"
    exit 0
    ;;
log)
    echo "--- selftest"; cat "$SELFLOG" 2>/dev/null
    echo "--- inject"; cat "$LOG" 2>/dev/null
    exit 0
    ;;
uninstall)
    setprop debug.oulg.sfrefract ""
    setprop debug.oulg.sf.norewrite ""
    rm -f "$LIB" "$BIN/live.prop" "$INJ" "${OULG_STAMP:-/data/local/tmp/oulg_selftest.ok}"
    # Programs rewritten by the library stay in the shader cache; without it they are compiled
    # from the stock source again at the next start.
    rm -f "$LIBDIR/skia_shaders" "$LIBDIR/egl_shaders"
    out uninstall ok
    out restart_needed 1
    exit 0
    ;;
*)
    die 2 "usage_status_install_selftest_restart_log_uninstall"
    ;;
esac
