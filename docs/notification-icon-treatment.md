# Notification icon treatment (first device-validation build)

This feature is separate from PR #175's approved notification borders and PR #176's P3 mode watcher.
It does not install a new render-loop watcher or change the Now Bar.

## Scope and priority

- Default `debug.oulg.iconmode=1`: Android system packages, updated system packages and Shell.
  Other installed apps, including IconPack Studio-themed icons, keep their existing treatment.
- `0`: off; existing `icontone` text-contrast behavior is unchanged.
- `2`: additionally allow non-system icons only when a 24x24 rendering of the displayed icon
  closely matches the package manager's official application icon. Ambiguous results are excluded.
- `3`: force treatment for eligible small icon views. Large pictures, avatars, media covers and
  action icons are excluded by the existing `Foreground.smallIcon` view classifier.
- A user-imported PNG explicitly opts that package in (except when mode `0`), overriding automatic
  source selection. It must have transparency; on import, its alpha channel becomes a white glyph.
- Otherwise, sources are: official adaptive monochrome → notification small icon with valid alpha
  silhouette → preserve already-transparent neutral glyph → bundled library asset.
- PNGs can be bundled under `app/src/main/assets/notification_icons/<package>.png` in later PRs.
  **There are no bundled replacements in this first build**; unknown cases appear in the app.

## Diagnostics and privacy

Only the posting package name is reported to `ConfigProvider` when *every* source fails.
No notification title, body, sender, key or account is read into the registry. The provider permits
reports and PNG reads only from the app UID and the SystemUI UID, and validates package identifiers.
The app keeps at most 100 unresolved package names. An imported icon is stored in app-private
storage, not uploaded anywhere. “Request icon” opens an unsent GitHub issue draft.

Optional `debug.oulg.trace=1` emits `ICON_TREAT_ROUTE`, `ICON_TREAT_APPLIED`,
`ICON_TREAT_SOURCE_FAILED`, and `ICON_TREAT_REPORT_FAILED` with package/source metadata only.
Turn trace off after a short reproduction; no trace is required for normal use.

The notification-icon source classifier is independent of `icontone`, which controls the
legacy foreground-colour adjustment. It does not rely on the global view-tree traversal's
host-package cache. When trace is enabled, `ICON_TREAT_SKIP`, `ICON_TREAT_CANDIDATES`, and
at most twelve `ICON_TREAT_VIEW` events per unresolved row identify the stage that failed,
using only view classes/resource IDs, not message content.

### Samsung One UI 9 physical test, 2026-11-07

The first APK ran the hook but returned `ICON_TREAT_SKIP reason=entryUnavailable pkg=null mode=-1`
on visible notifications; `inactive` also appeared on native/non-glass lifecycle passes.
`mode=-1` here is the per-row unbound sentinel, **not** a reading of the user setting.
The next revision accepts alternative Samsung `NotificationEntry` fields/getters or a direct
`StatusBarNotification` on the row, and traces `ICON_TREAT_ENTRY_PROBE` once per unresolved
row class (field names only; no message content). Source identity is checked at most every
250 ms per row so retained rows refresh when their notification changes. The lease also clears
and restores native ImageView-level colour filters to prevent them overriding the adaptive ink.
If no `ICON_TREAT_ROUTE`/`APPLIED` events appear, inspect `ICON_TREAT_ENTRY_PROBE` to identify
the actual firmware field instead of adding speculative package/icon fallbacks.

The follow-up physical trace identified the actual row as
`com.android.systemui.statusbar.notification.row.ExpandableNotificationRow`, with
`mEntry=false`, `getEntry=false` and an `mEntryAdapter` field. This is a different association
model, so the next build reads the adapter and retrieves its `StatusBarNotification` via
`getSbn()`/equivalent. If the adapter exists but does not expose the SBN as expected, the
trace emits `ICON_TREAT_ADAPTER_PROBE` with adapter type and SBN member availability.

## Device validation before merge

1. Use the default mode with an IconPack Studio setup. Check WhatsApp/ChatGPT/Gmail or any
   other themed user app in pop-up, shade and lockscreen: their existing icons must not change.
2. Trigger Shell/LSPosed, Samsung Screenshot saved and Motion Assistant notifications. Observe
   whether each gets an official monochrome source, a valid small icon, or an unresolved entry.
3. For an unresolved package, import a small transparent PNG. Recreate the notification. Verify
   dark/light glass tint and that the image no longer contains a baked-in background disc.
4. Test expanded/collapsed notifications, groups, shade opening, lockscreen and SDR ↔ P3 changes.
   Confirm no avatar, large image, media art or action symbol was replaced.
5. Switch mode `0` and verify the native icon is restored; switch back, update a notification,
   then remove its PNG and verify that its official fallback is used again.
6. Inspect `logcat -d -b main,system -s OULG:V` for `ICON_TREAT_` failures, SystemUI crashes,
   unexpected rebind loops, or prolonged resolution delays.

Known limit: there is no Android-wide public flag proving a launcher icon came from IconPack
Studio. Mode 2 uses a deliberately strict visual match and will skip false negatives. The safe
default never attempts to alter user-installed app icons.

## Separate icon controls (07/11 follow-up)

The new Icons tab owns all icon-specific controls. The default notification card glyph scale
is 0.76 and the separate status-bar *treated glyph* scale is 0.88, between the original
0.76 and the stock full-size drawing. Neither property affects native preserved icons,
and neither changes the width of a Samsung status-bar icon slot. Popup, lockscreen and
shade use one notification-card size. The status-bar palette defaults to the native Samsung
light/dark receiver; custom fixed and two-color modes are opt-in. The card palette defaults
to the existing adaptive black/white behavior.

For One UI 9's maximum count, the implementation follows the hook signatures documented
in [OneUIX StatusBar.kt](https://github.com/SoClear/OneUIX/blob/main/hook/src/main/java/io/github/soclear/oneuix/hook/systemui/statusbar/StatusBar.kt):
`NotificationIconContainer.shouldForceOverflow(int,float,int)` and the status-bar-only
`NotificationIconContainerStatusBarViewModel.maxIcons`. This is an independent Java
implementation using those Samsung integration points, with no OneUIX source copied.
Unlike OneUIX's broader feature, this PR intentionally does **not** alter the separate
FaceWidget lockscreen count. Value 0 preserves Samsung's default. Configuring a nonzero
limit may require restarting SystemUI so the ViewModel constructor runs again. Actual
capacity still depends on the reserved width around the camera, clock and system icons.

Device tests: compare the treated and preserved app icons in the status bar at 0.88;
change each size independently; test status overflow at stock/4/6/10; verify no lockscreen
shelf regression; switch custom status colors over light/dark backgrounds, checking clock,
battery, Wi-Fi/mobile signal and notification glyphs separately. On One UI 9 the battery
may be a Compose view rather than a legacy BatteryMeterView; confirm coverage rather than
assuming the color hook applies. Check `ICON_TREAT_STATUS_LIMIT`,
`ICON_TREAT_STATUS_INK` and `ICON_TREAT_COMPACT_HOOK` events when tracing is enabled.

## Follow-up diagnostics: collapsed shelf, width and live ghost (07/11)

- The native-only compact icon path returned before shelf ink correction. The shelf now gets
  the same reversible filter even when its source was intentionally preserved, and the trace
  records `ICON_TREAT_SHELF_DECISION` (`mirror`, `source`, `nativeInk`, `viewFilter`). Test a light
  shade with the **collapsed** More notifications group; also expand it and verify the
  individual notification icons and status bar remain untouched.
- The status maximum (`max=5` in `ICON_TREAT_STATUS_LIMIT`) and the physical icon area are
  separate. On DZIF the screen capture measured 216 px at 54 px/slot and an overflow dot.
  `ICON_TREAT_STATUS_GEOMETRY` now logs the containing IndicatorGarden's measured/maximum
  width and `fitWithDot`. It intentionally does not disable overflow: that would draw outside
  the region Samsung actually reserved. Test while the camera privacy indicator, recording
  dot, charging state, network indicator, and a live capsule appear or disappear.
- A single notification may have a white *disc* supplied by its parent rather than a white
  small-icon glyph. `ICON_TREAT_ROW_ICON_VISUAL` records the bounded candidate's drawable,
  ImageView/parent background classes, alpha and tint, once per row binding. This is diagnostic
  only, including for the LSPosed updated-module notification.
- `LIVE_REMOVAL_HOOKS` confirms Samsung cancellation callbacks found on this firmware.
  When trace is on, `LIVE_REMOVAL_STATE` takes read-only samples immediately and 500/1800 ms
  after a callback. The counts (`tracked/shown/glass`) for chip, card and Now Bar, plus plugin
  cards/layers, distinguish a retained Samsung activity from a retained module glass layer.
  No forced dismissal is attempted; an old timer must not be mistaken for a newly posted timer.

Short test (no SystemUI restart and no `logcat -c`; the diagnostic checks only need a new APK):

```sh
su -c 'setprop debug.oulg.trace 1'
# Reproduce one collapsed shelf, status icon overflow and an ending live timer.
su -c 'logcat -d -b main,system -v threadtime -s OULG:V' > /sdcard/Download/OULG_ICON_LIVE_FOLLOWUP.log
gzip -c /sdcard/Download/OULG_ICON_LIVE_FOLLOWUP.log > /sdcard/Download/OULG_ICON_LIVE_FOLLOWUP.log.gz
su -c 'setprop debug.oulg.trace 0'
```

If `LIVE_REMOVAL_HOOKS installed=0`, collect the existing Samsung log tags
`OngoingActivityController` and `OngoingActivityDataHelper` for the same reproduction.
They include notification information; inspect before sharing. Do not leave trace enabled
for routine use, as it also activates unrelated expensive visual probes.

## DZIF physical follow-up at 17:11–17:13 (07/11)

The previous trace resolved the remaining causes more precisely:

- The shelf had `mirror=false` during multiple opening transitions, despite its native
  black label over a light card. It briefly turned black when generation 13 committed
  `scene=light`, and reverted at the following transition. The compact icon now reads
  the visible `NotificationShelf` label's **current text colour** (cached per icon view)
  and uses the same reversible tint for source-preserved and bitmap-substituted icons.
  `ICON_TREAT_SHELF_LABEL` and `ICON_TREAT_SHELF_DECISION label=` distinguish an
  available real label from the old scene-based fallback. The early shade fixes used
  the related `Foreground.ownsTone` / `TEXT_ORPHAN` / `orphandraw` mechanism for nested
  minimised *rows*, which are different from this `NotificationShelf` icon instance.
- The low-battery SystemUI row contained opaque `BitmapDrawable` backgrounds on the
  two `NotificationRowIconView` instances, plus a separate `CachingIconView` with
  `android:insignificant_icon_dummy_bg`, `drawable=null`, and an opaque
  `VectorDrawable` background. Glyph replacement previously hid each icon view's
  original disc only once; Samsung could recreate it while the replacement remained.
  Active leases now re-suppress a rebound background, and a dedicated reversible lease
  hides **only** the Android dummy background while treatment is active. Observe
  `ICON_TREAT_BG_REBOUND` and `ICON_TREAT_DUMMY_BG` for actual changes.
- The 5-icon limit was already installed; DZIF continues to reserve **216 px** for a
  notification icon container at **54 px** per slot, leaving only 3 icons with the
  overflow dot. The surrounding indicator garden measured 617 px. The
  `ICON_TREAT_STATUS_GEOMETRY allocation=` chain now reports the actual child/parent
  layout widths, LayoutParams and linear-layout weights. Do not force the overflow
  result false until the controlling width allocation is identified.
- On keyguard entry, `State.tone` previously restored the text and edge style, but
  could leave the previous shade's `measuredVeil` in the same reused card. A keyguard
  transition now clears this stale tone/veil with `LOCK_TONE_RESET`. This does not yet
  implement independent wallpaper classification: `LOCK_TONE_PROBE` takes one
  **trace-only** capture after settling, recording full/row luminance and the current
  surface without changing the lockscreen palette. Confirm capture quality before
  applying an independent lockscreen colour decision.

For next device test, open the shade over a **light app** with the compact group shown,
then lock from a light and a dark shade, and reproduce a low-battery notification.
Check the four `ICON_TREAT_...` events above and `LOCK_TONE_RESET` / `LOCK_TONE_PROBE`.
Status-bar geometry must be sampled with at least five active notification icons.
