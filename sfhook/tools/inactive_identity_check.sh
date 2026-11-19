#!/bin/sh
# Reader proof for the INACTIVE rules of app/.../config/Applicability.java
# (docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md section 5). In the context that makes a compositor
# key inactive, the generated fragment GLSL must not depend on that key, or the dependence must be
# provably dead. For each case the same dump is rewritten twice, varying only the key between its
# registry default and an extreme of its domain, and the concatenated mod_*.frag are compared (cmp).
# Host-only: it neither compiles nor links GLSL (check.sh does) and never edits the sources.
#
# A byte-identical pair is proof by identity. A divergent pair is accepted only when the key is in
# ALLOWLIST (registry key:class) and every changed line fits the class (P-NEUTRAL decision,
# docs/OULG_UI_PLAN/EXECUTION_TRACKER.md):
#   OVERWRITTEN  every changed line only feeds oulg_br / oulg_bw or the shifted s that
#                oulg_clearN overwrites (oulg_br =, oulg_bw =, "* smoothstep(0.25 * bevel",
#                "highp vec2 s =", "return s", plus the operands the real GLSL shows: "s -= " of
#                the body lens, "highp float core =" and the "band1 <= 0.0 &&" guard of the
#                oulg_br assignments) or sits in an UNREACHABLE function (the Frosted material)
#   UNREACHABLE  every changed line (either side of the diff) is inside oulg_material_base or
#                oulg_disc0, which a Clear card never calls (oulg_cl < 0.5 only)
#   ZERO_FACTOR  every changed line multiplies by the literal "0.000 *"
# A divergence outside the allowlist or outside its class pattern, or an allowlist entry that no
# longer diverges, fails. sf.ramp, sf.kgramp, sf.kggradient and sf.kgband are ACTIVE under Clear
# (the reader still uses them) and are asserted to diverge so the rule cannot silently regress.
# INACTIVE_IDENTITY_STRICT=1 makes every divergence fatal, allowlisted or not.
# Structural rows (no lens, shade profile 2 without shadetone=1) have no GLSL pair to compare.
set -eu
cd "$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'st=$?; rm -rf "$tmp"; exit $st' EXIT
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-parameter -I.. -o "$tmp/rc" refract_check.c
DUMP=testdata/synthetic_dump.txt
ALLOWLIST="sf.core:OVERWRITTEN sf.edgeblur:OVERWRITTEN sf.hubodylens:OVERWRITTEN \
sf.kgcore:OVERWRITTEN sf.kgedgeblur:OVERWRITTEN sf.kgcorecap:OVERWRITTEN sf.kgblurexp:OVERWRITTEN \
sf.kgbodylens:OVERWRITTEN sf.kgsat:UNREACHABLE sf.kgcool:UNREACHABLE sf.kgflatcap:UNREACHABLE \
sf.hubloom:UNREACHABLE sf.kclevel:ZERO_FACTOR"
UNREACHABLE_FUNCS="oulg_material_base oulg_disc0"
OVERWRITTEN_PATTERNS="oulg_br = |oulg_bw = |* smoothstep(0.25 * bevel|highp vec2 s =|return s|  s -= |highp float core =|band1 <= 0.0 &&"
fail=0
checked=0
identical=0
controls=0
seen=""

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

class_of() {
    for e in $ALLOWLIST; do
        [ "${e%%:*}" = "$1" ] && { echo "${e#*:}"; return 0; }
    done
    return 1
}

# Name of the GLSL function whose definition (column 0, "... oulg_name(... {") last precedes line $2 of $1.
enclosing() {
    awk -v n="$2" 'NR > n { exit }
        /^[a-z].*oulg_[A-Za-z0-9_]+\(.*\{/ { if (match($0, /oulg_[A-Za-z0-9_]+\(/)) name = substr($0, RSTART, RLENGTH - 1) }
        END { print name }' "$1"
}

# changes_fit <|-separated line patterns or ""> <1 to also accept lines inside UNREACHABLE_FUNCS>:
# every changed line of diff -U0 $tmp/a $tmp/b (either side) must contain one of the patterns or
# sit inside an unreachable function. Prints the lines that fit neither and fails if there are any.
changes_fit() {
    diff -U0 "$tmp/a" "$tmp/b" | grep '^@@' | awk '{
        split($2, a, ","); split($3, b, ",")
        print substr(a[1], 2), (a[2] == "" ? 1 : a[2]), substr(b[1], 2), (b[2] == "" ? 1 : b[2]) }' >"$tmp/hunks"
    ok=0
    while read -r as ac bs bc; do
        for side in a b; do
            if [ "$side" = a ]; then start=$as; count=$ac; else start=$bs; count=$bc; fi
            i=0
            while [ "$i" -lt "$count" ]; do
                ln=$((start + i))
                i=$((i + 1))
                text="$(sed -n "${ln}p" "$tmp/$side")"
                fn="$(enclosing "$tmp/$side" "$ln")"
                if [ "$2" = 1 ]; then
                    case " $UNREACHABLE_FUNCS " in *" $fn "*) continue ;; esac
                fi
                hit=0
                set -f
                old_ifs=$IFS
                IFS='|'
                for p in $1; do
                    [ -n "$p" ] || continue
                    case "$text" in *"$p"*) hit=1 ;; esac
                done
                IFS=$old_ifs
                set +f
                [ "$hit" = 1 ] || { echo "  $side:$ln (in ${fn:-<none>}): $text"; ok=1; }
            done
        done
    done <"$tmp/hunks"
    return "$ok"
}

# the class of an accepted divergence holds for every changed line
class_holds() {
    case "$1" in
        OVERWRITTEN) changes_fit "$OVERWRITTEN_PATTERNS" 1 ;;
        ZERO_FACTOR) changes_fit "0.000 *" 0 ;;
        UNREACHABLE) changes_fit "" 1 ;;
        *) echo "  unknown class $1"; return 1 ;;
    esac
}

# check <label> <context> <harness key> <registry default> <domain extreme> [registry key]
check() {
    reg="${6:-sf.$3}"
    gen "$tmp/a" "$2,$3=$4"
    gen "$tmp/b" "$2,$3=$5"
    checked=$((checked + 1))
    if cmp -s "$tmp/a" "$tmp/b"; then
        identical=$((identical + 1))
        return 0
    fi
    if [ "${INACTIVE_IDENTITY_STRICT:-0}" = 1 ]; then
        echo "FAIL (strict): $1 ($3 $4 vs $5 under $2) changes the GLSL"
        fail=1
        return 0
    fi
    if ! cls="$(class_of "$reg")"; then
        echo "FAIL: $1 ($3 $4 vs $5 under $2) changes the GLSL and $reg is not in the allowlist"
        fail=1
        return 0
    fi
    if out="$(class_holds "$cls")"; then
        seen="$seen $reg"
    else
        echo "FAIL: $1 ($3 $4 vs $5 under $2): changed lines do not fit class $cls of $reg"
        echo "$out"
        fail=1
    fi
}

# expect_divergent <label> <context> <harness key> <registry default> <domain extreme>
# Positive control: the key must change the GLSL, so its ACTIVE state is justified.
expect_divergent() {
    gen "$tmp/a" "$2,$3=$4"
    gen "$tmp/b" "$2,$3=$5"
    controls=$((controls + 1))
    if cmp -s "$tmp/a" "$tmp/b"; then
        echo "FAIL: $1 ($3 $4 vs $5 under $2) no longer changes the GLSL; the rule may be INACTIVE again"
        fail=1
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

# Frosted-only knobs with both profiles Clear. Entries are harness key=default:extreme:registry key.
# tone, flat, level and cdim are byte-identical; the others diverge inside their allowlist class.
CLEAR="huclear=1,kgclear=1"
for kv in hucore=56:0:sf.core huedge=8:48:sf.edgeblur hubodylens=0.06:0.15:sf.hubodylens \
    core=80:0:sf.kgcore kgedge=48:0:sf.kgedgeblur ccap=48:128:sf.kgcorecap blurexp=2:4:sf.kgblurexp \
    bodylens=0.05:0.15:sf.kgbodylens sat=0.95:2.5:sf.kgsat cool=0.50:1:sf.kgcool fcap=0.22:1:sf.kgflatcap \
    tone=0:1:sf.kgtone flat=0.50:1:sf.kgflat level=0.85:0:sf.kglevel cdim=0:1:sf.kgcoredim; do
    key="${kv%%=*}"; rest="${kv#*=}"; reg="${rest#*:}"; reg="${reg#*:}"; pair="${rest%:*}"
    check "Clear material ignores the Frosted-only knob $key" "$CLEAR" "$key" "${pair%%:*}" "${pair#*:}" "$reg"
done

# Ramp, gradient and band still reach oulg_lw / the light coordinates under Clear: ACTIVE.
for kv in huramp=1:4 ramp=1.5:4 gradient=1:0 band=0.85:0; do
    key="${kv%%=*}"; pair="${kv#*=}"
    expect_divergent "sf ramp/gradient/band stay ACTIVE under Clear" "$CLEAR" "$key" "${pair%%:*}" "${pair#*:}"
done

# Meniscus on: the field is the meniscus one and does not receive amp.
check "sf.hcamp with hcfold>0" "huclear=1,hcfold=2" hcamp 0.40 0.5
check "sf.kcamp with kcfold>0" "kgclear=1,kcfold=2" kcamp 0.40 0.5
# The veil multiplies the level (literal 0.000 factor).
check "sf.kclevel with kcveil=0" "kgclear=1,kcveil=0" kclevel 0.85 0.1
# oulg_hu_hl_on needs the adaptive blur.
check "sf.hchlneutral with hcadapt=0" "huclear=1,hcadapt=0" hchlneutral 0 1
# Legacy neutralizer only runs with a material block.
check "sf.hcneutral without a material block" "huclear=1,hcsat=1,hcscrim=0,hcscrimmax=0,hclift=0" hcneutral 0.75 0
# The bright-sample weight lives in oulg_disc0 only; kernels 1-3 build disc A inline.
for kernel in 1 2 3; do
    check "sf.hubloom with adaptive blur on kernel $kernel" "huclear=1,hcadapt=1,hckernel=$kernel" hubloom 0 2
done

# Every allowlist entry must still diverge: a stale entry would hide a regression of its proof.
for e in $ALLOWLIST; do
    case " $seen " in
        *" ${e%%:*} "*) ;;
        *)
            if [ "${INACTIVE_IDENTITY_STRICT:-0}" != 1 ]; then
                echo "FAIL: allowlist entry $e no longer diverges; remove it (the pair is now proven by identity)"
                fail=1
            fi
            ;;
    esac
done

echo "inactive identity: $checked pairs, $identical byte-identical, $((checked - identical)) divergent and class-checked" \
    "(allowlist: $(echo $ALLOWLIST | wc -w) keys), $controls ACTIVE controls"
exit "$fail"
