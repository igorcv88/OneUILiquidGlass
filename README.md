# OneUILiquidGlass

Independent LSPosed module for Samsung SystemUI heads-up notifications. Initial target: Galaxy S25 Ultra SM-S938B, Android 17 / One UI 9 Beta. Firmware compatibility is **not physically verified** yet.

## Current implementation

- Probe mode (default): resolves row/background/heads-up manager/panel classes and records attach, lifecycle, hierarchy, window, native tint and corner radii. No notification content is logged.
- Experimental glass mode: compositor backdrop blur via `ViewRootImpl.createBackgroundBlurDrawable()`, translucent day/night tint and procedural AGSL edge lighting/inner shadow.
- Native row hierarchy, content, listeners, clip, bounds and animation are retained. `mBackground` is substituted only for the duration of `NotificationBackgroundView.onDraw(Canvas)` and restored afterward, including exceptional exits. Tint, ripple and corner update methods always see the original drawable.
- Native rendering on lockscreen, expanded shade, interaction, missing state, software rendering, unavailable blur or unsupported geometry. Brief/Edge Lighting is a diagnostic lane only.
- Resource cleanup on detach, shade transition and blur capability changes. A drawable belongs to the current attached ViewRoot, not a process-wide texture cache.

Cross-window blur is not a sampleable image. Refraction and chromatic displacement of the app underneath are pending investigation of a Samsung-compatible backdrop source; the current AGSL stage draws procedural lighting only.

## Build and install

JDK 17, Android SDK 36 and Gradle 8.11.1:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Releases are published to GitHub Releases by **Build One UI Liquid Glass APK** (`.github/workflows/release.yml`), the same pipeline as the other repositories:

- Dispatch it on `main` (optionally with an exact `app_sha`), or push `.github/release-trigger` to `main` with a line `app_sha=<40-char commit>`. The trigger workflow dispatches the pinned release.
- Each run tags `v0.1.N` (versionCode `100 + N`), runs tests and lint, builds the release APK, signs it with the fixed release key from the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets, and attaches the APK and its SHA-256.
- Because every release uses the same key and a higher versionCode, a new release installs over the previous one as an update. Builds signed with a debug key (local or older workflow artifacts) need one uninstall before the first release install.

1. Install the APK and enable the module for **SystemUI** in LSPosed.
2. Leave glass off for the first probe. Restart SystemUI (or reboot).
3. Use Samsung **Detailed** notification pop-up style. Grant the app notification permission, press the test button and switch to another app during the five-second delay.
4. Collect `OULG` logs following [docs/physical-test.md](docs/physical-test.md).
5. Enable experimental glass in the app, restart SystemUI and open/close the shade once. Repeat the test. If panel state cannot be resolved, native rendering remains active and the logs explain the missing path.

Configuration is a process-start snapshot read through LSPosed's shared-preferences support (minimum API 93; the compile-time legacy Xposed API is 82). Configuration changes require a SystemUI restart. If `CONFIG enabled=false` persists after enabling it, verify the LSPosed preferences bridge before investigating rendering.

## Layout

- `SystemUIEntry`: process scope and configuration.
- `hooks/HeadsUpHooks`: firmware resolution, heads-up lifecycle, eligibility and temporary background substitution.
- `glass/BackgroundBlurBridge`: compositor acquisition and cleanup.
- `glass/GlassDrawable`, `LiquidGlassShader`, `GlassSpec`: material and geometry.
- `diagnostics/Probe`: bounded structural logs.
- `tools/collect-device.sh`: read-only device evidence collection.

See [architecture](docs/architecture.md) and [references](docs/references.md).
