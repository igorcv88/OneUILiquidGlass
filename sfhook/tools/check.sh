#!/bin/sh
# Host check of refract.h against a shader dump (default: the synthetic Skia-style programs in
# testdata/): every rewrite must compile with glslangValidator, every fragment rewrite must link with
# the vertex rewrite (FillRRect) or alone (clip), and the expected number of programs must match.
# Usage: sfhook/tools/check.sh [dump] [expected "vertex fragment" counts]
set -e
cd "$(dirname "$0")"
python3 property_check.py
sh material_controls_check.sh
DUMP="${1:-testdata/synthetic_dump.txt}"
EXPECT="${2:-1 5}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-parameter -I.. -o "$OUT/rc" refract_check.c
cc -std=c99 -Wall -Wextra -Werror -O2 -o "$OUT/fc" field_check.c -lm
"$OUT/fc"
cc -std=c99 -Wall -Wextra -Werror -O2 -o "$OUT/hc" health_check.c
"$OUT/hc"
LINE="$("$OUT/rc" "$DUMP" "$OUT")"
echo "$LINE"
set -- $EXPECT
[ "$LINE" = "${LINE%vertex=$1 fragment=$2}vertex=$1 fragment=$2" ] || { echo "FAIL: expected vertex=$1 fragment=$2"; exit 1; }
fail=0
for f in "$OUT"/orig_* "$OUT"/mod_* "$OUT"/min_*; do
  [ -e "$f" ] || continue
  glslangValidator -S "${f##*.}" "$f" >"$OUT/log" 2>&1 || { echo "FAIL compile $(basename "$f")"; cat "$OUT/log"; fail=1; }
done
for f in "$OUT"/mod_*.frag; do
  [ -e "$f" ] || continue
  if grep -q voulg_tag "$f"; then
    for v in "$OUT"/mod_*.vert "$OUT"/min_*.vert; do
      glslangValidator -l "$v" "$f" >"$OUT/log" 2>&1 || { echo "FAIL link $(basename "$v") + $(basename "$f")"; cat "$OUT/log"; fail=1; }
    done
  fi
done
[ $fail -eq 0 ] && echo "glsl: all rewrites compile and link"
# The KG source-luminance probe is an opt-in shader path absent from production. Compile and link
# its real generated GLSL too, so the diagnostic APK cannot ship a probe that fails at runtime.
mkdir -p "$OUT/probe"
"$OUT/rc" "$DUMP" "$OUT/probe" 2 >/dev/null
for f in "$OUT/probe"/mod_*.frag; do
  [ -e "$f" ] || continue
  glslangValidator -S frag "$f" >"$OUT/log" 2>&1 || { echo "FAIL probe compile $(basename "$f")"; cat "$OUT/log"; fail=1; }
  if grep -q voulg_tag "$f"; then
    for v in "$OUT"/mod_*.vert "$OUT"/min_*.vert; do
      glslangValidator -l "$v" "$f" >"$OUT/log" 2>&1 || { echo "FAIL probe link $(basename "$v") + $(basename "$f")"; cat "$OUT/log"; fail=1; }
    done
  fi
done
[ $fail -eq 0 ] && echo "glsl: source-luminance probe compiles and links"
# Clear Glass: the field twin and bounds (U1-U8), parameter ranges, library sizes and text invariance;
# then the generated GLSL: byte identity with optics 11 while off, and every mode/debug pair.
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-function -O2 -I.. -o "$OUT/cc" clear_check.c -lm
"$OUT/cc" || fail=1
python3 clear_shader_check.py "$OUT/rc" "$DUMP" "$OUT/clear" || fail=1
exit $fail
