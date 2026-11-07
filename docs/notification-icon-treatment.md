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
