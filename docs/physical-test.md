# First physical validation — SM-S938B / One UI 9

## Evidence collection

Install/enable for SystemUI, restart it and leave experimental glass disabled first. Use Detailed notification pop-ups. Open and close the shade once, then post the module's delayed test notification over another app. Expand it, update it with the test button again, dismiss it, rotate the device and repeat while the keyboard is visible. Repeat with QS open, locked screen and Samsung Brief style. Notification policy can suppress presentation; the module does not override it.

Read logs in Termux:

```sh
su -c 'logcat -d -v threadtime -s OULG:I "*:S"' > /sdcard/Download/OneUILiquidGlass-probe.txt
```

Or copy the repository collector to the phone and run it with a shell that can read logcat:

```sh
su -c 'sh /sdcard/Download/collect-device.sh'
```

Avoid clearing all device logs. The collector reads firmware identifiers and this module's tag, without changing settings. It does not log notification keys, source packages or text. Review output before sharing; the build fingerprint identifies the firmware.

## Probe acceptance

Look for `PROCESS`, `CONFIG`, `CLASS`, `HOOK`, `DRAW_HOOK`, `READY`, manager/row events, `ATTACH`, `ANCESTOR`, `BLUR_FACTORY`, `SHADE`, `GEOMETRY` and `DETACH`. `READY` only means hook installation finished. It does not prove any notification traversed a hook. Match the same row/view identities across show, expansion and dismissal.

For Brief mode, `BRIEF_EVENT` is separate evidence; a missing event means its candidate classes/path need firmware mapping. A successful blur factory proves API creation only, not that the lower app is blurred visually.

## Glass pass

Enable experimental glass, restart SystemUI and repeat the matrix. `CONFIG enabled=true`, a known collapsed shade, unlocked row and `crossBlur=true` should precede `GLASS_APPLIED`. Confirm by sight that the actual app under the card changes the blur, content remains sharp, corners match, the card expands and dismisses with its native geometry, and blur does not linger after dismissal.

Compare light/dark apps with moving content. Record short screen videos plus logs. Screen recording itself may change compositor capability; check `BLUR_CAPABILITY_CHANGED`. A compositor blur request is not proof of optical refraction. This version should show blur/tint/lighting, not texture displacement.

Check expanded QS, lock/unlock, keyboard, rotation, changing day/night theme and device blur availability. Native materials should return when ineligible; repeated heads-up cycles should not accumulate listeners or stale blur regions. After a rendering error, that row stays native until replaced. Disable the module in LSPosed and restart SystemUI to recover.

Report firmware fingerprint, APK version, Samsung Detailed/Brief selection, test sequence, logs, visual result and any SystemUI exception. Screenshots alone cannot establish lifecycle cleanup or synchronization.
