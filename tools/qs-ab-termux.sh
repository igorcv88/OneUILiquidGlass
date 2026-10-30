#!/system/bin/sh
# Read-only QS/notification performance capture. Run as shell/root in Termux.
# Usage: sh qs-ab-termux.sh A 60 [output-directory]
# Repeat with B after changing only the OULG master switch/build.
set -eu

label="${1:-}"
duration="${2:-60}"
case "$label" in A|B) ;; *) echo "Uso: sh qs-ab-termux.sh A|B [segundos] [pasta]" >&2; exit 2;; esac
case "$duration" in ''|*[!0-9]*) echo "Segundos devem ser um inteiro" >&2; exit 2;; esac
[ "$duration" -ge 15 ] || { echo "Use pelo menos 15 segundos" >&2; exit 2; }

stamp="$(date +%Y%m%d_%H%M%S)"
output="${3:-./qs-ab-$label-$stamp}"
mkdir -p "$output"
pid="$(pidof com.android.systemui | awk '{print $1}')"
[ -n "$pid" ] || { echo "SystemUI não encontrado" >&2; exit 1; }

{
    echo "mode=$label"
    echo "durationSeconds=$duration"
    echo "started=$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "pid=$pid"
    echo "build=$(getprop ro.build.fingerprint)"
    echo "animatorDurationScale=$(settings get global animator_duration_scale)"
    echo "transitionAnimationScale=$(settings get global transition_animation_scale)"
    echo "windowAnimationScale=$(settings get global window_animation_scale)"
    echo "oulgPerf=$(getprop debug.oulg.perf)"
    echo "oulgTrace=$(getprop debug.oulg.trace)"
    echo "brightness=$(settings get system screen_brightness)"
} > "$output/context.txt"

dumpsys gfxinfo com.android.systemui framestats > "$output/gfxinfo-before.txt" 2>&1 || true
dumpsys meminfo com.android.systemui > "$output/meminfo-before.txt" 2>&1 || true
dumpsys cpuinfo > "$output/cpuinfo-before.txt" 2>&1 || true
dumpsys battery > "$output/battery-before.txt" 2>&1 || true

# No logcat -c or batterystats --reset: preserve the device's diagnostic history.
logcat -v epoch -T 1 -s OULG:I OULG_BENCH:I '*:S' > "$output/oulg-log.txt" 2>&1 &
logger_pid=$!
cleanup() { kill "$logger_pid" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "Coleta $label iniciada por $duration s; repita o mesmo roteiro de gestos."
elapsed=0
while [ "$elapsed" -lt "$duration" ]; do
    {
        echo "sampleAt=$(date '+%Y-%m-%d %H:%M:%S %z')"
        top -b -n 1 -p "$pid" 2>&1 || true
    } >> "$output/top-samples.txt"
    remaining=$((duration-elapsed))
    if [ "$remaining" -lt 5 ]; then sleep "$remaining"; elapsed="$duration"
    else sleep 5; elapsed=$((elapsed+5)); fi
done

cleanup
trap - EXIT INT TERM
dumpsys gfxinfo com.android.systemui framestats > "$output/gfxinfo-after.txt" 2>&1 || true
dumpsys meminfo com.android.systemui > "$output/meminfo-after.txt" 2>&1 || true
dumpsys cpuinfo > "$output/cpuinfo-after.txt" 2>&1 || true
dumpsys battery > "$output/battery-after.txt" 2>&1 || true
echo "finished=$(date '+%Y-%m-%d %H:%M:%S %z')" >> "$output/context.txt"
echo "Arquivos em $output"
