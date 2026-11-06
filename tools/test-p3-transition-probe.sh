#!/bin/sh
# Host-side smoke test. All Android-specific commands are mocked.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d)
trap 'if [ -d "$TMP" ]; then rm -r "$TMP"; fi' EXIT HUP INT TERM
MOCK="$TMP/mock-bin"
mkdir -p "$MOCK" "$TMP/results"

printf '#!/bin/sh\nprintf "Current color mode: ColorMode::DISPLAY_P3 (9)\\nLayerFE com.instagram.android dataspace=0x188a0000\\n"\n' > "$MOCK/dumpsys"
printf '#!/bin/sh\nprintf "mock logcat\\n"\n' > "$MOCK/logcat"
printf '#!/bin/sh\nexit 0\n' > "$MOCK/sleep"
printf '#!/bin/sh\nexit 0\n' > "$MOCK/monkey"
printf '#!/bin/sh\nexit 0\n' > "$MOCK/cmd"
printf '#!/bin/sh\nexit 0\n' > "$MOCK/input"
printf '#!/bin/sh\nprintf "MockBuild\\n"\n' > "$MOCK/getprop"
chmod +x "$MOCK"/*

PATH="$MOCK:$PATH" OULG_P3_OUT_ROOT="$TMP/results" \
    sh "$SCRIPT_DIR/collect-p3-transition.sh" --no-perfetto > "$TMP/probe_stdout.txt"

OUT=$(find "$TMP/results" -maxdepth 1 -type d -name 'OULG_P3_*' | head -n 1)
test -n "$OUT"
test -s "$OUT/markers.txt"
test -s "$OUT/logcat.txt"
test -s "$OUT/color_timeline.txt"
test -s "$OUT/sf_baseline.txt"
test -s "$OUT/sf_app.txt"
test -s "$OUT/sf_shade.txt"
test -s "$OUT/sf_app_return.txt"
test -s "$OUT/sf_home.txt"
test -s "$OUT/display_app.txt"
test -s "$OUT/activity_app.txt"
test -s "$OUT/README.txt"
test "$(wc -l < "$OUT/color_timeline.txt")" -eq 24
grep -q 'PHASE_APP_LAUNCH com.instagram.android' "$OUT/markers.txt"
grep -q 'PHASE_SHADE_EXPAND' "$OUT/markers.txt"
grep -q 'PHASE_SHADE_COLLAPSE' "$OUT/markers.txt"
grep -q 'PHASE_HOME' "$OUT/markers.txt"
grep -q 'mode=ColorMode::DISPLAY_P3 (9)' "$OUT/color_timeline.txt"
test -s "$OUT.tar.gz"

if sh "$SCRIPT_DIR/collect-p3-transition.sh" --app 'com.instagram.android;bad' > "$TMP/invalid_stdout.txt" 2>&1; then
    printf 'Invalid app package was incorrectly accepted\n' >&2
    exit 1
fi

printf 'P3 transition probe smoke test passed\n'
