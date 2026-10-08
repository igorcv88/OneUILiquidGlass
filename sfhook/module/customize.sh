# KernelSU install script. The surfaceflinger binary is copied and patched on this device; no
# Samsung binary ships in the module. Only the firmware this was analyzed on is accepted.
EXPECTED="samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCZZIC_OXMCZZIC:user/release-keys"
FP="$(getprop ro.build.fingerprint)"
[ "$FP" = "$EXPECTED" ] || abort "! Firmware diferente: $FP"
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
