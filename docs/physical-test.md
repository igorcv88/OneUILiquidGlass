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

## Expanded header gap

Applies with glass mode on. Keep the font scale, display size and resolution fixed for every screenshot.

1. **Reference.** Start a large Chrome download, pull down the shade and expand the notification. Send yourself a regular message (not a conversation bubble) and expand it too.
2. **Before.** Run `su -c 'setprop debug.oulg.headergap 0'`. Collapse and expand each notification again, then take a screenshot.
3. **After.** Run `su -c 'setprop debug.oulg.headergap 4'`. Collapse and expand, then take a screenshot. At density 3.0 the title starts 12 px lower; at 3.5 it starts 14 px lower. The space between the title and the progress bar is the same in both screenshots.
4. **Measure** with the shade open on the expanded notification. The dump contains notification text, so review it before sharing.

   ```sh
   su -c 'uiautomator dump /sdcard/Download/n.xml >/dev/null; grep -oE "resource-id=\"android:id/(notification_header|title|text|progress|right_icon)\"[^>]*bounds=\"[^\"]*\"" /sdcard/Download/n.xml | sed -E "s/\" .* bounds=/\" bounds=/"'
   ```

   The gap is the top of `title` minus the bottom of `notification_header`; compare it between the two dumps.
5. **Log.** `su -c 'logcat -d -s OULG | grep HEADER_GAP'` should show `installed=true` and `HEADER_GAP_PASS ... outcome=applied` for both notifications.
   - With `debug.oulg.trace=1`, `HEADER_GAP_MEASURE` gives `gapPx` in window coordinates without uiautomator. Between `headergap 0` and `4` it should differ by 12 px at density 3.0 and by 14 px at 3.5.
   - If you see `structure`, `noHeader`, `noTitle` or `HEADER_GAP_UNSUPPORTED`, set `debug.oulg.trace=1`, restart SystemUI, post the notification again, expand it, and send the `HEADER_GAP_TREE` lines.
6. **Must not change.**
   - The collapsed row and the heads-up pop-up (including an expanded pop-up) look the same as with `headergap 0`.
   - With a group, expand the group and then one child.
   - At font scale 200 %, a long title wraps without overlapping the header or clipping the bottom of the card. `outcome=noRoom` is the expected fallback when the card is at its height cap.
   - The glass covers the taller card exactly: no gap at the bottom rim, and the refraction band is on the outline.
   - Check RTL with the device language set to Arabic or Hebrew.
   - The progress updates every second without the title jumping.
