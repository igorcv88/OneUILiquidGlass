# OneUILiquidGlass

iOS 26–style Liquid Glass for Samsung One UI 9 notifications: heads-up pop-ups, lockscreen notifications and the notification shade.

| Surface | Look |
| --- | --- |
| Heads-up pop-up and lockscreen | Live glass: the content behind is refracted at the rim, lightly blurred near the rim and strongly blurred in the body |
| Notification shade | Diffuse blur, no refraction |

The project ships as two parts. Both are attached to every [release](https://github.com/igorcv88/OneUILiquidGlass/releases):

- **`OneUILiquidGlass-<version>.apk`**: an LSPosed module for SystemUI. It draws the glass material and the rim lighting, and requests the blur behind each card.
- **`OneUILiquidGlass-SurfaceFlinger-<version>.zip`**: a KernelSU module. It loads a small library into SurfaceFlinger that rewrites the compositor's rounded-rect blur shaders. That library produces the live refraction and the layered blur. Without it, the glass is a plain blur with rim lighting.

## Requirements

- **Device:** Galaxy S25 Ultra **SM-S938B**, firmware **S938BXXUCZZIC** (Android 17, One UI 9). The KernelSU module refuses any other firmware fingerprint, because it patches this firmware's SurfaceFlinger binary.
- **Root:** [KernelSU](https://kernelsu.org). The module installer does not support Magisk.
- **A KernelSU metamodule that mounts `/system`:** the module installs its files under `system/`, which only take effect through a mount metamodule. The installer aborts if `/data/adb/metamodule` is missing. Either of these works:
  - [Meta-OverlayFSX (ViPER-safe fork)](https://github.com/igorcv88/Meta-Overlayfsx-ViPER-safe), the one this project is tested with;
  - [Meta-OverlayFSX, upstream](https://github.com/RipperHybrid/meta-overlayfsx).
- **LSPosed** (API 93 or newer), with the module enabled for **SystemUI**.
- **Samsung pop-up style:** **Detailed**. Brief pop-ups (Edge Lighting) are not covered.

## Install

1. Install the overlay metamodule in KernelSU and reboot.
2. Install `OneUILiquidGlass-SurfaceFlinger-<version>.zip` in KernelSU and **reboot**. The module patches a copy of `/system/bin/surfaceflinger` at install time, and the metamodule mounts it only at boot. Restarting SurfaceFlinger without a reboot keeps the stock binary.
3. Install the APK. In LSPosed, enable it with the scope **System UI**.
4. Open the app and turn on **Experimental glass**.
5. Restart SystemUI:

   ```sh
   su -c 'kill $(pidof com.android.systemui)'
   ```

On each boot the KernelSU module turns the compositor lens on (`debug.oulg.sfrefract=1`) and clears SurfaceFlinger's shader cache, so the rewritten shaders are compiled again.

### Check that it works

```sh
su -c 'logcat -d -s OULG_SF | head'
su -c 'F=/data/misc/surfaceflinger/oulg_shaders.txt; echo "VS_OK=$(grep -c "===== REWRITE_OK vertex" $F) FS_OK=$(grep -c "===== REWRITE_OK fragment" $F) FAIL=$(grep -c "===== REWRITE_FAILED" $F)"'
```

`LOADED` in the first output means the library is inside SurfaceFlinger. The counters fill once a pop-up or the lockscreen has been shown. `FAIL` should be 0.

For a visual check, set `debug.oulg.sf.debug=1`, reboot (or restart SurfaceFlinger with a cleared cache), and the glass cards turn magenta. Set it back to 0 afterwards.

## Update

- **APK:** install the new release over the old one, then restart SystemUI. Releases share one signing key. A debug build needs one uninstall before the first release install.
- **KernelSU module:** install the new zip in KernelSU and reboot. KernelSU stages module updates and applies them only at boot.

## Settings

Settings are system properties, read live by the app about once a second unless noted. Set one with `su -c 'setprop <name> <value>'`. `debug.*` properties reset on reboot.

| Property | Default | Effect |
| --- | --- | --- |
| `debug.oulg.sfrefract` | 0 in the app; set to 1 at boot by the KernelSU module | Compositor lens on pop-ups and the lockscreen |
| `debug.oulg.sflens` | 0.7 | Lens strength, 0.1–1.2 |
| `debug.oulg.shadeblur` | 1 | Each shade card gets its own blur |
| `debug.oulg.semradiuslens` | 16 | Blur radius under lens cards (Samsung blur) |
| `debug.oulg.kgblurradius` | -1 (same as `semradiuslens`) | Blur radius in px on the lockscreen |
| `debug.oulg.semradius` | 180 | Blur radius under shade cards |
| `debug.oulg.semalpha` | spec tone | Opacity of the light veil over the blur, 0–255 |
| `debug.oulg.sf.core` | 48 | Body blur radius in px; 0 turns the layered blur off. Read when shaders compile: needs a SurfaceFlinger restart with a cleared cache |
| `debug.oulg.sf.taps` | 24 | Samples per pixel of the body blur; raise to 32 if the body shows grain. Same restart rule |
| `debug.oulg.sf.norewrite` | 0 | 1 leaves every SurfaceFlinger shader unchanged. Same restart rule |

`docs/architecture.md` describes the remaining diagnostic and tuning properties.

## Troubleshooting and recovery

- **SurfaceFlinger fails at boot:** the KernelSU module has a watchdog. If SurfaceFlinger restarts repeatedly or never starts within the first 90 s, the module disables itself and reboots into the stock compositor.
- **Turn the glass off:** switch off Experimental glass and restart SystemUI, or disable the module in LSPosed.
- **Logs:** app `logcat -s OULG`, SurfaceFlinger library `logcat -s OULG_SF`. With `debug.oulg.trace=1`, the app writes extra diagnostics.
- **Remove everything:** uninstall the KernelSU module and reboot, then uninstall the APK.

## Build

- **App:** JDK 17 and Android SDK 36.

  ```sh
  ./gradlew testDebugUnitTest lintDebug assembleDebug
  ```

- **KernelSU module:** Android NDK r29 (29.0.14206865). The script writes `sfhook/build/oulg-sf.zip`.

  ```sh
  ANDROID_NDK=/path/to/ndk sfhook/build.sh
  ```

- **Shader rewrite check on a device shader dump:**

  ```sh
  gcc -I sfhook -o rc sfhook/tools/refract_check.c
  ./rc oulg_shaders.txt out/
  ```

  Then run `glslangValidator` on each `mod_*` file in `out/`.

### Releases

**Build One UI Liquid Glass APK and SurfaceFlinger module** (`.github/workflows/release.yml`) builds and publishes both parts:

- It runs on `main`, dispatched from the Actions tab (optionally with an exact `app_sha`) or by pushing `.github/release-trigger` with a line `app_sha=<40-char commit>`.
- Each run tags `v0.1.N` (versionCode `100 + N`) and runs tests and lint.
- It builds and signs the release APK with the fixed key from the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets.
- It builds the KernelSU module with NDK r29. The module version comes from `sfhook/module/module.prop`.
- It attaches both files, each with its SHA-256.

## Layout

- `app/`: the LSPosed module.
  - `hooks/HeadsUpHooks`: notification lifecycle, eligibility, blur choice and drawing the glass over the native background.
  - `glass/SemBlurBridge`: Samsung blur on pop-ups and the shade.
  - `glass/BackgroundBlurBridge`: blur drawn with the glass on the lockscreen.
  - `glass/GlassDrawable`, `LiquidGlassShader`, `GlassSpec`: material and rim lighting.
  - `glass/Tuning`: the `debug.oulg.*` properties.
- `sfhook/`: the KernelSU module.
  - `oulg_sf.c`: the library loaded into SurfaceFlinger.
  - `refract.h`: the shader rewrites (lens, B-spline sampling, layered blur).
  - `dtneeded.c`: the patcher that makes SurfaceFlinger load the library.
  - `module/`: the install, boot and watchdog scripts.
- `docs/`: [architecture and history](docs/architecture.md), [handoff](docs/handoff.md), [references](docs/references.md).
