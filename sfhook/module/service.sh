#!/system/bin/sh
# Watchdog: if surfaceflinger restarts 4 times within the first 90 s of boot, disable this module
# and reboot into the stock compositor.
MODDIR=${0%/*}
pids=""
i=0
while [ $i -lt 45 ]; do
  p="$(pidof surfaceflinger)"
  if [ -n "$p" ]; then
    case " $pids " in *" $p "*) ;; *) pids="$pids $p" ;; esac
  fi
  if [ "$(echo $pids | wc -w)" -ge 4 ]; then
    touch "$MODDIR/disable"
    log -t OULG_SF "WATCHDOG surfaceflinger restarted repeatedly; module disabled"
    reboot
    exit 0
  fi
  sleep 2
  i=$((i + 1))
done
