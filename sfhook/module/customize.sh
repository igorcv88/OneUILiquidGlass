# KernelSU install script. The surfaceflinger binary is copied and patched on this device; no
# Samsung binary ships in the module. Only firmware this was analyzed on is accepted.
#
# One line per accepted ro.build.fingerprint. The value must be read off a device with
#   getprop ro.build.fingerprint
# and never derived from a firmware name: Build.FINGERPRINT carries the CSC suffix, which the
# firmware's own build number does not give you.
#
# S938BXXUCDZIF (One UI 9 Stable) is now listed. Its line was read with
# `getprop ro.build.fingerprint` on a DZIF device; it was deliberately left out while only
# partition-level fingerprints were available, because
# samsung/pa3qxxx/qssi_64:17/CP2A.260605.016/S938BXXUCDZIF:user/release-keys is /system's value,
# not Build.FINGERPRINT - it carries neither the pa3q device nor the OXMCDZIF CSC suffix. The
# suffix is the part a firmware name cannot give you, which is why guessing it would have made
# this gate accept a string no device was ever confirmed to report.
#
# The offline analysis that justifies accepting it: the DZIF surfaceflinger takes this exact
# patch (88 bytes, DT_DEBUG consumed, first DT_NEEDED becomes SurfaceFlingerProp.so) and still
# imports glShaderSource and eglGetProcAddress. See docs/dzif-port.md.
ALLOWED_FINGERPRINTS="
samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCZZIC_OXMCZZIC:user/release-keys
samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCDZIF_OXMCDZIF:user/release-keys
"
FP="$(getprop ro.build.fingerprint)"
[ -n "$FP" ] || abort "! ro.build.fingerprint vazio; nao da para verificar o firmware"
MATCHED=0
# Split on newlines only and with globbing off: an unquoted expansion would
# otherwise glob a fingerprint containing a shell wildcard against the current
# directory, and silently compare against whatever that matched.
OULG_OLD_IFS="$IFS"
IFS='
'
set -f
for ALLOWED in $ALLOWED_FINGERPRINTS; do
  if [ "$FP" = "$ALLOWED" ]; then MATCHED=1; break; fi
done
set +f
IFS="$OULG_OLD_IFS"
[ "$MATCHED" = 1 ] || abort "! Firmware diferente: $FP"
# Files under $MODPATH/system only take effect through a metamodule that mounts them.
[ -e /data/adb/metamodule ] || abort "! Nenhum metamodulo de montagem do /system (ex.: meta-overlayfs) instalado"
mkdir -p "$MODPATH/system/bin" "$MODPATH/system/lib64"
cp -f /system/bin/surfaceflinger "$MODPATH/system/bin/surfaceflinger" || abort "! copia do surfaceflinger falhou"
chmod 0755 "$MODPATH/tools/dtneeded"
NAME="$("$MODPATH/tools/dtneeded" "$MODPATH/system/bin/surfaceflinger")"
RC=$?
[ $RC -eq 0 ] || [ $RC -eq 2 ] || abort "! patch falhou ($RC)"
[ -n "$NAME" ] || abort "! nome da biblioteca vazio"
mv -f "$MODPATH/lib/liboulg_sf.so" "$MODPATH/system/lib64/$NAME" || abort "! biblioteca ausente"
set_perm "$MODPATH/system/bin/surfaceflinger" 0 2000 0755 u:object_r:surfaceflinger_exec:s0
set_perm "$MODPATH/system/lib64/$NAME" 0 0 0644 u:object_r:system_lib_file:s0
rm -rf "$MODPATH/tools" "$MODPATH/lib"
ui_print "- surfaceflinger alterado; biblioteca: $NAME"
ui_print "- Reinicie. Log: logcat -s OULG_SF"
