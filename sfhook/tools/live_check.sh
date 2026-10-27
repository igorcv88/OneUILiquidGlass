#!/bin/sh
# Host check of sfhook/live/oulg-live.sh: the Android commands it calls are stubbed, the injector is a
# shell stand-in, and the target directories are temporary. Walks status -> install -> selftest ->
# status -> reinstall over a "loaded" library -> uninstall and checks the key=value answers.
set -e
cd "$(dirname "$0")/.."
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/bundle" "$T/bin" "$T/lib" "$T/proc"
FP="samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCDZIF_OXMCDZIF:user/release-keys"
cat > "$T/stub/getprop" <<S
#!/bin/sh
case "\$1" in ro.build.fingerprint) echo "\${OULG_TEST_FP:-$FP}";; *) cat "$T/prop_\$1" 2>/dev/null;; esac
S
cat > "$T/stub/setprop" <<S
#!/bin/sh
printf '%s' "\$2" > "$T/prop_\$1"
S
printf '#!/bin/sh\necho 0\n' > "$T/stub/id"
printf '#!/bin/sh\nexit 0\n' > "$T/stub/chcon"
printf '#!/bin/sh\necho 4242\n' > "$T/stub/pidof"
chmod +x "$T/stub/"*
# Injector stand-in: a selftest writes the stamp; --stamp-status checks it.
cat > "$T/bundle/oulg_inject" <<S
#!/bin/sh
case "\$*" in *--selftest*) echo ok > "$T/stamp"; exit 0;; *--stamp-status*) [ -f "$T/stamp" ];; *) exit 0;; esac
S
echo "lib v1" > "$T/bundle/liboulg_sf.so"
cp live/oulg-live.sh live/allowed_fingerprints "$T/bundle/"
manifest() {
  { echo "version=$1"; echo "versionCode=$2"
    echo "sha256_inject=$(sha256sum "$T/bundle/oulg_inject" | cut -d ' ' -f 1)"
    echo "sha256_helper=$(sha256sum "$T/bundle/oulg-live.sh" | cut -d ' ' -f 1)"
    echo "sha256_lib=$(sha256sum "$T/bundle/liboulg_sf.so" | cut -d ' ' -f 1)"; } > "$T/bundle/live.prop"
}
manifest v0.21.0 22
run() { PATH="$T/stub:$PATH" OULG_BIN="$T/bin" OULG_LIBDIR="$T/lib" OULG_STAMP="$T/stamp" OULG_PROC="$T/proc" sh "$T/bundle/oulg-live.sh" "$@"; }
# /proc/<pid>/maps of the stand-in surfaceflinger.
expect() { echo "$1" | grep -qx "$2" || { echo "FAIL: expected $2 in:"; echo "$1"; exit 1; }; }
s="$(run status)"; expect "$s" "update_needed=1"; expect "$s" "installed_ok=0"; expect "$s" "fingerprint_ok=1"
expect "$(run install)" "install=ok"
s="$(run status)"; expect "$s" "update_needed=0"; expect "$s" "selftest_ok=0"; expect "$s" "installed_version=v0.21.0"
mkdir -p "$T/proc/4242"
inode="$(stat -c '%i' "$T/lib/liboulg_sf.so")"
printf '1000-2000 r-xp 00000000 00:01 %s %s\n' "$inode" "$T/lib/liboulg_sf.so" > "$T/proc/4242/maps"
expect "$(run status)" "sf_loaded=1"
printf '1000-2000 r-xp 00000000 00:01 %s %s (deleted)\n' "$inode" "$T/lib/liboulg_sf.so" > "$T/proc/4242/maps"
expect "$(run status)" "sf_loaded=0"
printf '1000-2000 r-xp 00000000 00:01 0 %s\n' "$T/lib/liboulg_sf.so" > "$T/proc/4242/maps"
expect "$(run status)" "sf_loaded=0"
expect "$(run selftest)" "selftest=ok"
s="$(run status)"; expect "$s" "selftest_ok=1"
# A corrupt bundle is refused; the installed copy is untouched.
echo tampered >> "$T/bundle/liboulg_sf.so"
set +e; o="$(run install)"; rc=$?; set -e
[ $rc -eq 2 ] || { echo "FAIL: corrupt bundle rc=$rc"; exit 1; }; expect "$o" "error=bundle_corrupt"
cp "$T/bundle/live.prop" "$T/manifest.good"
printf 'version=invalid\n' > "$T/bundle/live.prop"
set +e; o="$(run install)"; rc=$?; set -e
[ $rc -eq 2 ] || { echo "FAIL: malformed manifest rc=$rc"; exit 1; }
expect "$o" "error=bundle_corrupt"
cp "$T/manifest.good" "$T/bundle/live.prop"
# A new library version: update needed, installed by rename (a new inode).
echo "lib v2" > "$T/bundle/liboulg_sf.so"; manifest v0.22.0 23
old_inode="$(ls -i "$T/lib/liboulg_sf.so" | cut -d ' ' -f 1)"
expect "$(run status)" "update_needed=1"
expect "$(run install)" "install=ok"
new_inode="$(ls -i "$T/lib/liboulg_sf.so" | cut -d ' ' -f 1)"
[ "$old_inode" != "$new_inode" ] || { echo "FAIL: library overwritten in place"; exit 1; }
expect "$(run status)" "update_needed=0"
cp "$T/bundle/live.prop" "$T/manifest.good2"
sed 's/^sha256_helper=.*/sha256_helper=0000000000000000000000000000000000000000000000000000000000000000/' "$T/manifest.good2" > "$T/bundle/live.prop"
set +e; o="$(run install)"; rc=$?; set -e
[ $rc -eq 2 ] || { echo "FAIL: helper hash mismatch rc=$rc"; exit 1; }; expect "$o" "error=bundle_corrupt"
cp "$T/manifest.good2" "$T/bundle/live.prop"
printf '1000-2000 r-xp 00000000 00:01 %s %s.backup\n' "$new_inode" "$T/lib/liboulg_sf.so" > "$T/proc/4242/maps"
expect "$(run status)" "sf_loaded=0"
printf '1000-2000 r-xp 00000000 00:01 %s %s\n' "$new_inode" "$T/lib/liboulg_sf.so" > "$T/proc/4242/maps"
expect "$(run status)" "sf_loaded=1"
printf '1 0 0:1 / /system rw - overlay overlay lowerdir=/data/adb/metamodule/mnt/oulg_sf/system\n' > "$T/proc/4242/mountinfo"
expect "$(run status)" "legacy_conflict=1"
set +e; o="$(run restart rewrite)"; rc=$?; set -e
[ $rc -eq 2 ] || { echo "FAIL: mounted legacy module rc=$rc"; exit 1; }
expect "$o" "error=legacy_module_active_disable_it_and_reboot_first"
: > "$T/proc/4242/mountinfo"
expect "$(run status)" "legacy_conflict=0"
set +e; o="$(run restart now)"; rc=$?; set -e
[ $rc -eq 2 ] || { echo "FAIL: bad restart mode rc=$rc"; exit 1; }
# Compositor parameters: allowlisted names and numeric values only; nothing is written on refusal.
o="$(run setprops kgcore=72 kgsat=1.3 core=)"; expect "$o" "setprops=ok"; expect "$o" "set_kgcore=72"
expect "$o" "set_kgsat=1.3"; expect "$o" "set_core="
[ "$(cat "$T/prop_debug.oulg.sf.kgcore")" = 72 ] || { echo "FAIL: setprops did not write"; exit 1; }
for bad in 'optics=9' 'norewrite=1' 'kgcore=1;reboot' 'kgcore=$(id)' 'kgcore=1-2' 'kgcore=1.2.3' 'kgcore' '../x=1'; do
    set +e; o="$(run setprops kgsat=2 "$bad")"; rc=$?; set -e
    [ $rc -eq 2 ] || { echo "FAIL: setprops accepted $bad rc=$rc"; exit 1; }
done
[ "$(cat "$T/prop_debug.oulg.sf.kgsat")" = 1.3 ] || { echo "FAIL: refused setprops wrote a value"; exit 1; }
set +e; o="$(run setprops)"; rc=$?; set -e
[ $rc -eq 2 ] || { echo "FAIL: empty setprops rc=$rc"; exit 1; }
expect "$(run uninstall)" "uninstall=ok"
expect "$(run status)" "installed_ok=0"
# The observed OWO variant must migrate a manual library (mapped, no manifest)
# through install -> selftest -> restart without accepting arbitrary CSC/builds.
export OULG_TEST_FP="samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCDZIF_OWOCDZIF:user/release-keys"
cp "$T/bundle/liboulg_sf.so" "$T/lib/liboulg_sf.so"
inode="$(stat -c '%i' "$T/lib/liboulg_sf.so")"
printf '1000-2000 r-xp 00000000 00:01 %s %s\n' "$inode" "$T/lib/liboulg_sf.so" > "$T/proc/4242/maps"
s="$(run status)"; expect "$s" "fingerprint_ok=1"; expect "$s" "sf_loaded=1"
expect "$s" "installed_ok=0"; expect "$s" "installed_version="; expect "$s" "update_needed=1"
expect "$(run install)" "install=ok"
expect "$(run selftest)" "selftest=ok"
expect "$(run status)" "installed_ok=1"
expect "$(run restart rewrite)" "restart=started"
for rejected in \
    "${OULG_TEST_FP%OWOCDZIF:user/release-keys}XXXCDZIF:user/release-keys" \
    "samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCZZIC_OWOCZZIC:user/release-keys"; do
    export OULG_TEST_FP="$rejected"
    expect "$(run status)" "fingerprint_ok=0"
    for operation in install 'restart rewrite'; do
        set +e; o="$(run $operation)"; rc=$?; set -e
        [ $rc -eq 2 ] || { echo "FAIL: unknown firmware accepted: $operation rc=$rc"; exit 1; }
        expect "$o" "error=fingerprint_not_allowed"
    done
done
unset OULG_TEST_FP
echo "live helper: all checks passed"
