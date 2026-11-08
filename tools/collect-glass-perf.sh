#!/system/bin/sh
# Bounded SystemUI profiling: shade, QS, live surfaces and the minimized shelf.
# Run as root on the device; emits one log in Downloads and restores diagnostic properties.
set -u

if [ "$(id -u)" != 0 ]; then
    printf 'Execute como root: su -c '\''sh /sdcard/Download/OULG_GLASS_PERF.sh'\''\n' >&2
    exit 1
fi

STAMP=$(date '+%Y%m%d_%H%M%S')
OUT="/sdcard/Download/OULG_GLASS_PERF_${STAMP}.log"
RAW="/sdcard/Download/OULG_GLASS_PERF_${STAMP}.tmp"
OLD_PERF=$(getprop debug.oulg.perf)
OLD_TRACE=$(getprop debug.oulg.trace)
LOG_PID=

finish() {
    trap - EXIT HUP INT TERM
    setprop debug.oulg.perf "${OLD_PERF:-0}"
    setprop debug.oulg.trace "${OLD_TRACE:-0}"
    if [ -n "$LOG_PID" ]; then
        kill "$LOG_PID" 2>/dev/null || true
        wait "$LOG_PID" 2>/dev/null || true
    fi
    {
        printf '\n=== PERF / CAPTURE / SHELF EVENTS ===\n'
        grep -E 'event=(PERF|PERF_SPIKE|PERF_FRAME|CAPTURE_STATS|ICON_TREAT_SHELF_|ICON_TREAT_COMPACT_HOOK|SHADE_OPEN_BEGIN|SHADE_OPEN_END|SHADE_PALETTE_|SHADE_CAPTURE_UNAVAILABLE|SHADE_PREFETCH|TEXT_CONTESTED|TEXT_REAPPLY|TUNING_REFRESH_REJECTED)' "$RAW" || true
        printf '\n=== ERROR EVENTS ===\n'
        grep -E 'event=.*(FAILED|DISABLED|CONTESTED|ERROR|EXCEPTION|UNAVAILABLE)' "$RAW" || true
        printf '\n=== COUNTS ===\n'
        for EVENT in PERF CAPTURE_STATS PERF_FRAME SHADE_OPEN_BEGIN SHADE_PALETTE_COMMIT SHADE_PALETTE_HOLD SHADE_CAPTURE_UNAVAILABLE ICON_TREAT_SHELF_INK ICON_TREAT_SHELF_CONTESTED TEXT_REAPPLY; do
            printf '%s=' "$EVENT"
            grep -c "event=$EVENT " "$RAW" || true
        done
    } >> "$OUT"
    rm -f "$RAW"
    printf '\nArquivo: %s\n' "$OUT"
}
trap finish EXIT HUP INT TERM

{
    printf 'OULG profiling: %s\n' "$(date)"
    printf 'Build: %s\n' "$(getprop ro.build.display.id)"
    printf 'Original perf=%s trace=%s\n' "$OLD_PERF" "$OLD_TRACE"
    printf 'SystemUI pid=%s\n' "$(pidof com.android.systemui || true)"
} > "$OUT"

logcat -v threadtime -b main,system -s OULG:V > "$RAW" 2>&1 &
LOG_PID=$!
setprop debug.oulg.perf 1
setprop debug.oulg.trace 0

stage() {
    printf '\n=== %s: %s ===\n' "$(date '+%H:%M:%S')" "$1" | tee -a "$OUT"
    /system/bin/log -p i -t OULG_PROFILE "$1" 2>/dev/null || true
}

stage 'ETAPA 1/4: mantenha a tela desbloqueada, sem painel aberto (15 s)'
sleep 15
stage 'ETAPA 2/4: abra e use a central de notificacoes (15 s)'
sleep 15
stage 'ETAPA 3/4: abra e use o Quick Settings (15 s)'
sleep 15
stage 'ETAPA 4/4: teste Now Bar / live update, depois mostre More notifications (15 s)'
sleep 15

stage 'SHELF TRACE: deixe More notifications visivel por 8 s'
setprop debug.oulg.trace 1
sleep 8
setprop debug.oulg.trace 0

stage 'GFXINFO: estado agregado de frames da SystemUI'
if command -v timeout >/dev/null 2>&1; then
    timeout 6 dumpsys gfxinfo com.android.systemui 2>&1 | head -n 75 >> "$OUT"
else
    printf 'GFXINFO omitido: comando timeout indisponivel; evitando bloqueio no final.\n' >> "$OUT"
fi
stage 'CPUINFO: consumo momentaneo por processo'
if command -v timeout >/dev/null 2>&1; then
    timeout 6 dumpsys cpuinfo 2>&1 | head -n 45 >> "$OUT"
else
    printf 'CPUINFO omitido: comando timeout indisponivel; evitando bloqueio no final.\n' >> "$OUT"
fi
