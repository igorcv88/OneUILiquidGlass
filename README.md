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

The **Build diagnostic APK** workflow is `workflow_dispatch` only. No push/PR CI or automatic workflow triggers are configured. The debug APK is signed with the build environment's debug key, so different environments can require uninstall/reinstall.

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
