#!/system/bin/sh
# Watchdog for the first 90 s of boot: disable this module and reboot into the stock compositor if
# surfaceflinger restarts 4 times, or is absent for 10 polls in a row (20 s; it never came up).
MODDIR=${0%/*}
# debug.* properties do not survive a reboot. With this module active the app's compositor lens is
# wanted, so turn it on unless it was set explicitly (setprop debug.oulg.sfrefract 0 still wins).
[ -z "$(getprop debug.oulg.sfrefract)" ] && setprop debug.oulg.sfrefract 1
pids=""
missing=0
i=0
fail() {
  touch "$MODDIR/disable"
  log -t OULG_SF "WATCHDOG $1; module disabled"
  reboot
  exit 0
}
while [ $i -lt 45 ]; do
  p="$(pidof surfaceflinger)"
  if [ -n "$p" ]; then
    missing=0
    case " $pids " in *" $p "*) ;; *) pids="$pids $p" ;; esac
  else
    missing=$((missing + 1))
  fi
  [ "$(echo $pids | wc -w)" -ge 4 ] && fail "surfaceflinger restarted repeatedly"
  [ $missing -ge 10 ] && fail "surfaceflinger absent for 20 s"
  sleep 2
  i=$((i + 1))
done
