#!/bin/sh
# Identity proof for the INACTIVE rules of app/.../config/Applicability.java
# (docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md section 5): in the context that makes a compositor
# key inactive, the generated fragment GLSL must not depend on that key. For each case the same
# dump is rewritten twice, varying only the inactive key between its registry default and an
# extreme of its domain, and the concatenated mod_*.frag must be byte-identical (cmp).
# Host-only: it neither compiles nor links GLSL (check.sh does) and never edits the sources.
#
# Pairs whose GLSL differs are reported as "BLOCKER P-NEUTRAL" and are NOT fixed here (shaders and
# rules are out of scope of R6 phase B): the decision between keeping the rule INACTIVE, making it
# ACTIVE or requiring a neutral value belongs to Opus (docs/OULG_UI_PLAN/EXECUTION_TRACKER.md). The
# count of such pairs is pinned in EXPECTED_DIVERGENT so the script still fails when a new pair
# diverges or an existing one is fixed (update the pin and the tracker together).
# INACTIVE_IDENTITY_STRICT=1 makes every divergence fatal.
# Structural rows (no lens, shade profile 2 without shadetone=1) have no GLSL pair to compare.
set -eu
cd "$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'st=$?; rm -rf "$tmp"; exit $st' EXIT
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-parameter -I.. -o "$tmp/rc" refract_check.c
DUMP=testdata/synthetic_dump.txt
EXPECTED_DIVERGENT=19
fail=0
checked=0
divergent=0

# gen <out-file> <overrides>: rewrite the dump (debug 0) and keep every fragment rewrite in one file.
gen() {
    dir="$tmp/out.$$"
    rm -rf "$dir"
    mkdir -p "$dir"
    "$tmp/rc" "$DUMP" "$dir" 0 "$2" >/dev/null
    set -- "$1" "$dir"/mod_*.frag
    [ -e "$2" ] || { echo "FAIL: no fragment rewrite emitted"; exit 1; }
    out="$1"
    shift
    cat "$@" >"$out"
    [ -s "$out" ] || { echo "FAIL: empty rewrite"; exit 1; }
}

# check <label> <context> <harness key> <registry default> <domain extreme>
check() {
    gen "$tmp/a" "$2,$3=$4"
    gen "$tmp/b" "$2,$3=$5"
    checked=$((checked + 1))
    if ! cmp -s "$tmp/a" "$tmp/b"; then
        echo "BLOCKER P-NEUTRAL: $1 ($3 $4 vs $5 under $2) changes the GLSL; the INACTIVE rule has no reader proof"
        divergent=$((divergent + 1))
    fi
}

# Clear-only parameters with the Frosted material: oulg_clear_on is false, no Clear field is emitted.
FROSTED="huclear=0,kgclear=0"
for kv in hcband=0.42:1 hcfold=2:0 hcamp=0.40:0.5 hcbody=0:0.15 hcbody2=0:0.05 hcedge=24:0 hccore=24:0 \
    hcblurexp=2:4 hcadapt=1:0 hcsat=1.05:2 hcscrim=0:0.6 hcscrimmax=0:0.8 hclift=0.30:2 hcneutral=0.75:0 \
    hckernel=0:3 hchlneutral=0:1 kcband=0.42:1 kcfold=2:0 kcamp=0.40:0.5 kcbody=0:0.15 kcbody2=0:0.05 \
    kcedge=24:0 kccore=24:0 kcblurexp=2:4 kcsat=1:1.3 kcveil=0.04:0.2 kclevel=0.85:0.1 kcdim=0:0.3 \
    kcknee=0:0.6 kccool=0.50:2 kclift=0:2; do
    key="${kv%%=*}"; pair="${kv#*=}"
    check "Frosted material ignores sf.$key" "$FROSTED" "$key" "${pair%%:*}" "${pair#*:}"
done

# Frosted-only knobs with both profiles Clear: the Clear output replaces shift, blur and material.
CLEAR="huclear=1,kgclear=1"
for kv in hucore=56:0 huedge=8:48 huramp=1:4 hubodylens=0.06:0.15 \
    core=80:0 kgedge=48:0 ccap=48:128 ramp=1.5:4 blurexp=2:4 gradient=1:0 band=0.85:0 bodylens=0.05:0.15 \
    sat=0.95:2.5 tone=0:1 flat=0.50:1 level=0.85:0 cool=0.50:1 fcap=0.22:1 cdim=0:1; do
    key="${kv%%=*}"; pair="${kv#*=}"
    check "Clear material ignores the Frosted-only knob $key" "$CLEAR" "$key" "${pair%%:*}" "${pair#*:}"
done

# Meniscus on: the field is the meniscus one and does not receive amp.
check "sf.hcamp with hcfold>0" "huclear=1,hcfold=2" hcamp 0.40 0.5
check "sf.kcamp with kcfold>0" "kgclear=1,kcfold=2" kcamp 0.40 0.5
# The veil multiplies the level.
check "sf.kclevel with kcveil=0" "kgclear=1,kcveil=0" kclevel 0.85 0.1
# oulg_hu_hl_on needs the adaptive blur.
check "sf.hchlneutral with hcadapt=0" "huclear=1,hcadapt=0" hchlneutral 0 1
# Legacy neutralizer only runs with a material block.
check "sf.hcneutral without a material block" "huclear=1,hcsat=1,hcscrim=0,hcscrimmax=0,hclift=0" hcneutral 0.75 0
# The bright-sample weight lives in oulg_disc0 only; kernels 1-3 build disc A inline.
for kernel in 1 2 3; do
    check "sf.hubloom with adaptive blur on kernel $kernel" "huclear=1,hcadapt=1,hckernel=$kernel" hubloom 0 2
done

echo "inactive identity: $checked pairs, $((checked - divergent)) byte-identical, $divergent divergent (pinned: $EXPECTED_DIVERGENT)"
if [ "${INACTIVE_IDENTITY_STRICT:-0}" = 1 ] && [ "$divergent" -ne 0 ]; then fail=1; fi
if [ "$divergent" -ne "$EXPECTED_DIVERGENT" ]; then
    echo "FAIL: divergent pairs changed from the pinned $EXPECTED_DIVERGENT; review and update the pin and the tracker"
    fail=1
fi
exit "$fail"
