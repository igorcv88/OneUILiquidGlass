#!/bin/sh
# Host check of refract.h against a shader dump (default: the synthetic Skia-style programs in
# testdata/): every rewrite must compile with glslangValidator, every fragment rewrite must link with
# the vertex rewrite (FillRRect) or alone (clip), and the expected number of programs must match.
# Usage: sfhook/tools/check.sh [dump] [expected "vertex fragment" counts]
set -e
cd "$(dirname "$0")"
DUMP="${1:-testdata/synthetic_dump.txt}"
EXPECT="${2:-1 5}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-parameter -I.. -o "$OUT/rc" refract_check.c
cc -std=c99 -Wall -Wextra -Werror -O2 -o "$OUT/fc" field_check.c -lm
"$OUT/fc"
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
exit $fail
