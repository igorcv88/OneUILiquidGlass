<p align="center">
  <img src=".github/assets/oneui-liquid-glass-banner.png" alt="One UI Liquid Glass" width="100%" />
</p>

<p align="center">
  <img alt="Status: Beta" src="https://img.shields.io/badge/status-beta-8b8cff" />
  <img alt="Android 17" src="https://img.shields.io/badge/Android-17-3DDC84?logo=android&amp;logoColor=white" />
  <img alt="One UI 9" src="https://img.shields.io/badge/One%20UI-9-78baff" />
  <img alt="LSPosed API 93+" src="https://img.shields.io/badge/LSPosed-API%2093%2B-6C63FF" />
  <a href="https://github.com/igorcv88/OneUILiquidGlass/actions/workflows/release.yml"><img alt="Release build" src="https://img.shields.io/github/actions/workflow/status/igorcv88/OneUILiquidGlass/release.yml?branch=main&amp;label=release%20build" /></a>
  <a href="https://github.com/igorcv88/OneUILiquidGlass/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/igorcv88/OneUILiquidGlass?label=release" /></a>
  <a href="https://github.com/igorcv88/OneUILiquidGlass/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/igorcv88/OneUILiquidGlass/total" /></a>
  <a href="https://github.com/igorcv88/OneUILiquidGlass/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/igorcv88/OneUILiquidGlass?style=flat&amp;logo=github&amp;label=stars" /></a>
  <a href="https://github.com/igorcv88/OneUILiquidGlass/network/members"><img alt="Forks" src="https://img.shields.io/github/forks/igorcv88/OneUILiquidGlass?style=flat&amp;label=forks" /></a>
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue" /></a>
</p>

<p align="center">
  <strong>Glass material, optical edges and optional compositor refraction for Samsung SystemUI notifications.</strong>
</p>

One UI Liquid Glass is an independent **LSPosed module** for Samsung's notification surfaces: detailed heads-up pop-ups, lockscreen notification cards and the expanded notification shade. It preserves SystemUI's native content and interaction while changing the cards' backdrop and glass material. Brief/Edge Lighting pop-ups and the music player are outside the current scope.

The project consists of two separately installable components:

- **APK:** hooks SystemUI, composes the glass material and edge lighting, and draws live backdrop blur. It works without the SurfaceFlinger add-on.
- **Optional KernelSU module:** patches a copy of SurfaceFlinger for the supported firmware to enable live edge refraction and per-surface layered blur. The APK falls back to its own optics without it.

> [!IMPORTANT]
> This is an experimental module for a specific Samsung firmware family, **not a general-purpose Android theme**. It is not affiliated with Samsung or Google. Back up before enabling the optional SurfaceFlinger component, and follow the firmware and recovery requirements below.

## Project status

**Beta.** The current APK includes an Experimental glass toggle and a test-notification button. Most optical controls are currently exposed through the `debug.oulg.*` properties via Termux/root, not as APK sliders.

A grouped settings interface with per-surface previews, switches and a realistic expanded test notification is **planned**, not yet implemented. See the [UI refactor handoff](docs/HANDOFF_APP_REFACTOR_2026-10-26.md) and the [full property catalog](docs/PROPERTY_CATALOG_2026-10-26.md) for the implementation contract.

## Features

| Surface | Current rendering |
| --- | --- |
| **Detailed heads-up** | Live blur, edge lighting and a motion-gated captured lens band in APK-only mode; optional compositor lens and layered blur with the KernelSU add-on |
| **Lockscreen** | Blur attached to the card geometry, independent light/dark material, compositor profile when installed; optional glass treatment for the notification overflow pill |
| **Expanded shade** | Independent card blur, backdrop-dependent tone and text/icon adaptation, with controls to keep cards legible over light and dark content |
| **Diagnostics** | SystemUI lifecycle and material logs (`OULG`), optional performance/trace logging, compositor logs (`OULG_SF`) and safe fallbacks |

The optical material and the real refraction path are different. APK-only edge highlights simulate the look of a lens; only an eligible compositor shader or a captured sampled backdrop can actually displace content behind a card. The sampled band is motion-gated because capture latency can make moving content appear to trail the card.

## Requirements and compatibility

- Tested/developed for **Galaxy S25 Ultra SM-S938B**, Android 17 / **One UI 9**. Development was based on `S938BXXUCZZIC`; `S938BXXUCDZIF` is recognized by the optional installer, but matching framework files do **not** substitute for physical APK validation.
- **LSPosed API 93+**, with scope limited to **System UI** (`com.android.systemui`).
- **Detailed** notification pop-up style. Brief pop-ups/Edge Lighting are not hooked.
- **Optional compositor lens only:** KernelSU plus an overlay metamodule for `/system`; the firmware fingerprint must pass [the installer allowlist](sfhook/module/customize.sh). The installer does not support Magisk.

The optional module was designed around [Meta-OverlayFSX (ViPER-safe fork)](https://github.com/igorcv88/Meta-Overlayfsx-ViPER-safe) or [upstream Meta-OverlayFSX](https://github.com/RipperHybrid/meta-overlayfsx). It modifies the mounted SurfaceFlinger execution path and has a boot watchdog; it can also affect system stability and integrity-sensitive configurations.

## Installation

Download the binaries only from [GitHub Releases](https://github.com/igorcv88/OneUILiquidGlass/releases):

1. Install `OneUILiquidGlass-<version>.apk`.
2. Enable the module in LSPosed and scope it **only** to System UI.
3. Open the app, enable **Experimental glass**, then restart SystemUI:

   ```sh
   su -c 'kill $(pidof com.android.systemui)'
   ```

4. Use the app's test-notification button to inspect the visual result. The current button schedules a BigText notification after five seconds; the proposed larger test with action buttons is described in the handoff.

For the **optional compositor refraction**, install the compatible KernelSU metamodule first and reboot; then install `OneUILiquidGlass-SurfaceFlinger-<version>.zip` in KernelSU and **reboot again**. Restarting only SurfaceFlinger after installation will not mount the patched binary. The service script enables `debug.oulg.sfrefract=1` at boot unless that property is explicitly set to 0.

You can verify that the native library loaded with:

```sh
su -c 'logcat -d -s OULG_SF | head'
```

`LOADED` means the library was injected; shader rewrite activity is logged after an eligible pop-up or lockscreen card is drawn. A loaded library alone does not prove that the specific card's shader was rewritten.

## Tuning and previews

Today, most controls are **live SystemUI properties** refreshed about once per second:

```sh
su -c 'setprop debug.oulg.liveoptics 0.5'
su -c 'setprop debug.oulg.liverim 2'
su -c 'setprop debug.oulg.lensgate directional'
```

`debug.*` overrides reset at reboot unless restored separately. Most `debug.oulg.sf.*` shader parameters are read **only when a shader compiles**: changing their property does not change already compiled shaders. Clearing the shader cache or restarting SurfaceFlinger is a separate, higher-risk operation. The app must never present a value as applied when the compositor is still running the previous shader.

Some examples from the **current code defaults** (not necessarily the effective overrides on a running phone):

| Property | Default | Effect |
| --- | --- | --- |
| `liveblur` / `sfrefract` | 1 / 0 | APK-only live material vs optional compositor lens |
| `liveradius` / `liveshaderadius` | 80 / 120 px | Blur for pop-up/lockscreen vs expanded shade |
| `liveoptics` / `liverim` | 1 / 2 | APK-only optical gain vs heads-up outer contour |
| `liveglint` / `shadeglint` | 0.10 / 0.10 | Internal corner glint on pop-up/shade |
| `lensgate` / `lenssettle` | directional / 100 ms | Motion gate for captured lens |
| `sflens` / `kglens` | 0.45 / 0.40 | Lens strength per compositor profile |
| `shadetone` / `veilmax` | 3 / 0.25 | Adaptive shade tone and veil limit |
| `lightglass` / `shadelightglass` | 0.75 / 0.27 | Light material opacity on pop-up vs shade |
| `sf.core` / `sf.kgcore` | 48 / 80 px | Shader body blur profile parameters; compilation-time |

The [property catalog](docs/PROPERTY_CATALOG_2026-10-26.md) enumerates every currently parsed setting, its default, domain and application phase. For engineering details, see [architecture](docs/architecture.md), [validation](docs/validation.md), [physical tests](docs/physical-test.md) and [optics handoff](docs/handoff.md).

## Troubleshooting and recovery

- **Disable the look:** turn off Experimental glass and restart SystemUI, or disable the APK in LSPosed.
- **No heads-up:** verify Detailed pop-up mode, channel importance, notification permission and Do Not Disturb before treating it as a hook failure.
- **Blur fallback:** find `BLUR_DRAWABLE_FALLBACK`, `DECISION`, `TUNING` in `logcat -s OULG`.
- **Diagnostics:** set `debug.oulg.trace=1` for extra traces; `debug.oulg.perf=1` enables hook timings. Turn them off after testing.
- **Compositor failure:** the KernelSU module has a watchdog that disables the add-on after repeated SurfaceFlinger startup failure within its recovery window. If necessary, disable/remove that KernelSU module and reboot.
- **Uninstall:** remove the optional KernelSU module and reboot before uninstalling the APK.

## Build and release

**APK:** JDK 17, Android SDK 36.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

**KernelSU module:** Android NDK r29 (`29.0.14206865`).

```sh
ANDROID_NDK=/path/to/ndk sfhook/build.sh
```

For a shader rewrite check, compile `sfhook/tools/refract_check.c` and validate generated `mod_*` shader files with `glslangValidator`.

The [release workflow](.github/workflows/release.yml) runs on `main` or via manual dispatch/trigger. It tags `v0.1.N`, builds/tests/lints the APK, signs releases using repository secrets, builds the KernelSU ZIP and publishes checksums with the two files.

## Repository layout

- `app/`: LSPosed module, `MainActivity`, config provider, notification hooks, optics and live property parser (`glass/Tuning.java`).
- `sfhook/`: optional KernelSU compositor library, shader rewrite and boot watchdog.
- `docs/`: architecture, design handoff, property catalog, validation and previous investigations.
- `.github/assets/`: project icon and banner.

## License and attribution

MIT License. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Independent SystemUI project; the WaEnhancer Community design and README organization informed this project's documentation, but the projects do not share a runtime or settings storage.
