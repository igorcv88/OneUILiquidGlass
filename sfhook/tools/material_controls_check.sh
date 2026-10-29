#!/bin/sh
# Confirm that the six independent popup/keyguard controls reach the emitted shader.
# This is intentionally a host-only test; compilation/linking are checked by check.sh.
set -eu
cd "$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-parameter -I.. -o "$tmp/rc" refract_check.c
mkdir -p "$tmp/base" "$tmp/tuned"
"$tmp/rc" testdata/synthetic_dump.txt "$tmp/base" 0 >/dev/null
"$tmp/rc" testdata/synthetic_dump.txt "$tmp/tuned" 0 \
    'hubodylens=0.08,hublur=0.65,hurefract=1.10,kgedge=12,kgblur=0.8,kgrefract=1.15' >/dev/null

has() { grep -Fq "$1" "$2"/mod_*.frag || { echo "FAIL: missing shader field: $1"; exit 1; }; }
has 's -= 0.0000 * q' "$tmp/base"
has 's *= oulg_pf < 0.5 ? 1.000 : 1.000;' "$tmp/base"
has 'oulg_br *= oulg_pf < 0.5 ? 1.000 : 1.000;' "$tmp/base"
has 's -= 0.0800 * q' "$tmp/tuned"
has 's *= oulg_pf < 0.5 ? 1.100 : 1.150;' "$tmp/tuned"
has 'oulg_br *= oulg_pf < 0.5 ? 0.650 : 0.800;' "$tmp/tuned"
has 'oulg_br = mix(12.00, min(80.00, 52.00), oulg_bw);' "$tmp/tuned"
has 'band1 <= 0.0 && 12.00 > 0.0' "$tmp/tuned"
echo 'material controls: defaults and six tuned fields reached the GLSL'
