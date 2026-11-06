#!/system/bin/sh
# One-shot P3/SDR transition probe for Galaxy S25 Ultra / One UI.
# No settings, system properties, files outside this probe, or modules are changed.
# Usage (root): sh collect-p3-transition.sh [--app PACKAGE] [--no-perfetto]
set -u

APP=com.instagram.android
PERFETTO=1
OUT_ROOT=${OULG_P3_OUT_ROOT:-/sdcard/Download}
case "${1:-}" in
    --help|-h)
        printf 'Usage: su -c '\''sh /sdcard/Download/OULG_P3_probe.sh [--app PACKAGE] [--no-perfetto]'\''\n'
        printf 'Captures baseline, opens app, opens shade, collapses shade, returns Home.\n'
        exit 0
        ;;
esac
while [ "$#" -gt 0 ]; do
    case "$1" in
        --app)
            if [ "$#" -lt 2 ]; then printf 'Missing package after --app\n' >&2; exit 2; fi
            APP=$2
            shift 2
            ;;
        --no-perfetto) PERFETTO=0; shift ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done

if [ "$(id -u)" != 0 ]; then
    printf 'Run as root: su -c '\''sh /sdcard/Download/OULG_P3_probe.sh'\''\n' >&2
    exit 1
fi
if ! command -v dumpsys >/dev/null 2>&1 || ! command -v logcat >/dev/null 2>&1; then
    printf 'Android dumpsys/logcat not available\n' >&2
    exit 1
fi

# Prevent accidentally passing shell metacharacters into the intent launcher.
case "$APP" in
    *[!a-zA-Z0-9._]*|'') printf 'Invalid package: %s\n' "$APP" >&2; exit 2 ;;
esac

STAMP=$(date '+%Y%m%d_%H%M%S')
OUT="$OUT_ROOT/OULG_P3_${STAMP}_$$"
mkdir -p "$OUT" || exit 1
LOG_PID=
PERFETTO_PID=
SF_PID=
PERFETTO_TMP=/data/misc/perfetto-traces/oulg_p3_${STAMP}_$$.pftrace

mark() {
    LABEL=$1
    WALL=$(date '+%Y-%m-%dT%H:%M:%S%z')
    BOOT=$(cut -d ' ' -f 1 /proc/uptime)
    printf '%s uptime=%s %s\n' "$WALL" "$BOOT" "$LABEL" >> "$OUT/markers.txt"
    /system/bin/log -p i -t OULG_P3_TRACE "uptime=$BOOT $LABEL" 2>/dev/null || true
    printf '%s\n' "$LABEL"
}

snapshot() {
    SNAP_LABEL=$1
    mark "SNAPSHOT_START $SNAP_LABEL"
    dumpsys SurfaceFlinger > "$OUT/sf_${SNAP_LABEL}.txt" 2> "$OUT/sf_${SNAP_LABEL}.err" || true
    dumpsys display > "$OUT/display_${SNAP_LABEL}.txt" 2>/dev/null || true
    dumpsys activity activities > "$OUT/activity_${SNAP_LABEL}.txt" 2>/dev/null || true
    mark "SNAPSHOT_END $SNAP_LABEL"
}

# Sampling only while the probe runs. Each sample records the active display
# color mode; full per-layer dataspaces come from snapshots and Perfetto.
sample_sf() {
    N=0
    while [ "$N" -lt 24 ]; do
        WALL=$(date '+%Y-%m-%dT%H:%M:%S%z')
        BOOT=$(cut -d ' ' -f 1 /proc/uptime)
        MODE=$(dumpsys SurfaceFlinger 2>/dev/null | sed -n 's/^[[:space:]]*Current color mode: //p' | head -n 1)
        printf '%s uptime=%s mode=%s\n' "$WALL" "$BOOT" "${MODE:-UNKNOWN}" >> "$OUT/color_timeline.txt"
        N=$((N + 1))
        sleep 1
    done
}

finish() {
    trap - EXIT HUP INT TERM
    mark 'CAPTURE_END'
    if [ -n "$SF_PID" ]; then wait "$SF_PID" 2>/dev/null || true; fi
    if [ -n "$LOG_PID" ]; then kill "$LOG_PID" 2>/dev/null || true; wait "$LOG_PID" 2>/dev/null || true; fi
    if [ -n "$PERFETTO_PID" ]; then
        wait "$PERFETTO_PID" 2>/dev/null || true
        if [ -s "$PERFETTO_TMP" ]; then
            mv "$PERFETTO_TMP" "$OUT/sf_transactions.pftrace" || true
        fi
    fi
    {
        printf 'App: %s\n' "$APP"
        printf 'Firmware: '; getprop ro.build.display.id
        printf 'P3 Instagram expected layer dataspace: 0x188a0000\n'
        printf 'Test phases: baseline -> app -> shade -> collapse -> home\n'
        printf 'sf_*.txt: per-layer dataspace and actual output color mode.\n'
        printf 'display_*.txt: DisplayManager state; activity_*.txt: foreground state.\n'
        printf 'color_timeline.txt: display mode sampled once per second.\n'
        printf 'sf_transactions.pftrace: optional per-transaction SurfaceFlinger trace.\n'
        printf 'Installed module / SF status is not altered by this script.\n'
        printf '\nObserved OULG settings:\n'
        for PROP in debug.oulg.edgepremul debug.oulg.kgsharedoptics debug.oulg.sf.kgfixedrim; do
            printf '%s=%s\n' "$PROP" "$(getprop "$PROP")"
        done
    } > "$OUT/README.txt"
    if command -v tar >/dev/null 2>&1; then
        tar -czf "$OUT.tar.gz" -C "$OUT_ROOT" "$(basename "$OUT")" 2>/dev/null || true
    fi
    printf '\nSaved directory: %s\n' "$OUT"
    if [ -s "$OUT.tar.gz" ]; then printf 'Share: %s\n' "$OUT.tar.gz"; fi
    du -h "$OUT" "$OUT.tar.gz" 2>/dev/null || true
}
trap finish EXIT HUP INT TERM

mark "CAPTURE_START app=$APP"
printf 'Starting logcat (main, system, events)...\n'
logcat -b main,system,events -v epoch -T 1 > "$OUT/logcat.txt" 2> "$OUT/logcat.err" &
LOG_PID=$!
sample_sf &
SF_PID=$!

if [ "$PERFETTO" -eq 1 ] && command -v perfetto >/dev/null 2>&1; then
    # Transactions MODE_ACTIVE is chronological and lighter than the heavy
    # layers MODE_ACTIVE snapshot-on-every-change capture. Ring buffer 12 MiB.
    {
        printf 'duration_ms: 23000\n'
        printf 'buffers { size_kb: 12288 fill_policy: RING_BUFFER }\n'
        printf 'data_sources { config { name: "android.surfaceflinger.transactions" surfaceflinger_transactions_config { mode: MODE_ACTIVE } } }\n'
    } > "$OUT/trace_config.pbtxt"
    perfetto -c "$OUT/trace_config.pbtxt" --txt -o "$PERFETTO_TMP" > "$OUT/perfetto.log" 2>&1 &
    PERFETTO_PID=$!
else
    printf 'Perfetto disabled or unavailable\n' > "$OUT/perfetto.log"
fi

mark 'PHASE_BASELINE_BEGIN (Termux; do not touch screen)'
sleep 2
snapshot baseline

mark "PHASE_APP_LAUNCH $APP"
if ! monkey -p "$APP" -c android.intent.category.LAUNCHER 1 > "$OUT/launch.log" 2>&1; then
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$APP" >> "$OUT/launch.log" 2>&1 || true
fi
sleep 4
snapshot app

mark 'PHASE_SHADE_EXPAND'
cmd statusbar expand-notifications > "$OUT/shade.log" 2>&1 || true
sleep 3
snapshot shade

mark 'PHASE_SHADE_COLLAPSE'
cmd statusbar collapse >> "$OUT/shade.log" 2>&1 || true
sleep 2
snapshot app_return

mark 'PHASE_HOME'
input keyevent KEYCODE_HOME > "$OUT/home.log" 2>&1 || true
sleep 2
snapshot home

mark 'PHASE_DONE'
