#!/system/bin/sh
# Run in Termux with root or over adb shell. Reads diagnostics only.
set -eu
out="${1:-/sdcard/Download/OneUILiquidGlass-diagnostics.txt}"
{
    printf '=== Firmware ===\n'
    getprop ro.product.model
    getprop ro.build.fingerprint
    getprop ro.build.version.sdk
    getprop ro.build.version.oneui
    printf '\n=== Module logs ===\n'
    logcat -d -v threadtime -s OULG:I '*:S'
    printf '\n=== Blur support ===\n'
    getprop ro.surface_flinger.supports_background_blur
    settings get global disable_window_blurs
} > "$out"
printf 'Saved: %s\n' "$out"
