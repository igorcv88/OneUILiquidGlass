# Initial architecture and evidence boundaries

## Rendering contract

The native notification background View owns actual-height/actual-width, expansion centering, RTL bounds and clipping. Its private drawable is swapped in `onDraw` only. The original drawable stays available for native `setTint`, `updateBackgroundRadii`, `setCustomBackground`, state changes and restoration. The module does not exclude any ViewGroup from capture or replace notification children.

The material draws a ViewRoot-bound `BackgroundBlurDrawable` and a transparent AGSL lighting pass inside native eight-value corner radii. The four scalar compositor radii use top-left, top-right, bottom-left, bottom-right API order. The optical stage uses top-left, top-right, bottom-right, bottom-left order. Non-circular per-axis corner shapes require further firmware investigation; current AOSP notification radii have equal X/Y components. Native parent outline clipping remains in place.

Native draw code determines bounds every frame. Appear/disappear alpha comes from the original drawable plus native View transforms. There is no parallel animation clock or screenshot refresh loop. Press/focus/hover retains the original native stateful material. This may produce a visible material change on interaction; ripple visual integration is a later physical calibration task.

The compositor capability listener prevents a transparent material remaining active when cross-window blur is disabled. Missing state or API falls back to the original drawable. The module does not force-enable global blur or alter notification delivery, private lockscreen content or channel policy.

## Firmware resolution

Initial class names come from the inspected Iconify and OneUIX sources. They are candidates, not evidence that Samsung One UI 9 calls them. Missing classes and methods are explicitly logged. Row-derived state and panel expanded height must be resolved before glass applies. The panel constructor and `setExpandedHeightInternal` are observed; older/newer field names and a no-argument getter are tried. A missing panel callback leaves glass inactive.

The lifecycle logs expose manager events, row state, background changes, attach/detach, geometry and window identity. This is not a SurfaceFlinger layer trace. To identify the actual lower surfaces and determine if Samsung offers a sampleable backdrop, pair these logs with a developer-generated composition trace or firmware source/decompiled classes.

## Backdrop research

`BackgroundBlurDrawable` requests compositor blur, but does not give AGSL a texture. `RenderEffect` on a notification subtree blurs that subtree, not the app underneath. Capturing the SystemUI tree has the same process/window boundary. The backdrop pixels come from `IWindowManager.captureDisplay` with the shade window excluded (see *Captured backdrop* below). `CaptureApi` binds it through the classes named in that method's own signature, because `android.window.ScreenCapture` (Android 14–16) is absent on One UI 9 / SDK 37, where they live in `android.window.ScreenCaptureInternal`. If binding fails, `CaptureSurvey` logs the firmware's capture surface once (`CAPTURE_SURVEY_*`).

A future sampled-backdrop provider should expose texture, coordinate transform, valid region, timestamp and ownership/lifetime. Refraction/dispersion must be gated on that provider actually yielding the lower app content, and must preserve secure/protected surface handling. A compositor-only material remains an independent tier.

## Known pending work

Physical hook verification, shader compilation on the device, Samsung brief popup mapping, animated corner calibration, QS behavior with separate Samsung panels, lockscreen transition tests, keyboard window composition, contrast over varied app content, frame-time measurements and sampleable backdrop investigation. Existing JVM compilation and policy tests do not establish any of these behaviors.

## Backdrop selection (2026-10-07)

Device evidence (SM-S938B, SDK 37, One UI 9.0): `ro.surface_flinger.supports_background_blur` is unset, so AOSP cross-window blur and `BackgroundBlurDrawable` are unavailable. The material therefore uses a `Backdrop`:

- `COMPOSITOR` (`BackgroundBlurBridge`): preferred when `isCrossWindowBlurEnabled()` is true. Drawn inside the material's clip.
- `SAMSUNG` (`SemBlurBridge`): `View.semSetBlurInfo(SemBlurInfo)` with `BLUR_MODE_WINDOW`, set on the native background view and cleared with `null` on every release. One UI renders it under the view's content, so the material draws only the edge shader. Builder members (`setRadius`, `setBackgroundColor`, `setBackgroundCornerRadius`, `build`) are resolved reflectively. Required: `View.semSetBlurInfo`, the int constant `BLUR_MODE_WINDOW`, a `Builder(int)` constructor or `Builder()` plus `setBlurMode(int)`, `setRadius(int)` or `setBlurRadius(int)`, and `build()`; the color and corner setters are optional. `SEM_BLUR_BRIDGE` reports the variant in use. Read-only recon on `S938BXXUCZZIC` (dex strings of `framework.jar`) confirmed `SemBlurInfo`, `SemBlurInfo$Builder`, `BLUR_MODE_WINDOW`, `semSetBlurInfo`, `setBackgroundColor` and `setBackgroundCornerRadius`, and that the color and corner setters are accepted only in `BLUR_MODE_WINDOW`; the builder constructor and radius setter signatures were not resolvable from strings. Updates are posted after the current traversal because they originate in the hooked `onDraw`.
- No backdrop: `DECISION reason=blur=unavailable`, native rendering.

Known limits of the Samsung path, pending device validation: it takes one corner radius (non-uniform corners stay native); it does not follow the native drawable's alpha during appear/disappear; the API signature is inferred and logged (`SEM_BLUR_FIELD/METHOD/CTOR`), not documented by Samsung.

## Composition without field replacement (2026-10-07)

On One UI 9 `NotificationBackgroundView.mBackground` is declared as `androidx.appcompat.graphics.drawable.SeslRecoilDrawable`, so assigning a `GlassDrawable` to it fails with `IllegalArgumentException` (device log: `GLASS_APPLIED` → `DRAW_SWAP_FAILED`). The draw hook no longer writes the field:

1. Before `onDraw`, the canvas argument is replaced by a throwaway `RenderNode` recording canvas. The native code runs unchanged and sets the drawable's bounds as usual; its output is discarded.
2. After `onDraw`, the glass is drawn on the real canvas with the native drawable's bounds, clipped like AOSP (`mClipTopAmount`, `mClipBottomAmount`, `mActualHeight`, skipped while `mExpandAnimationRunning`; `CLIP_FIELDS` reports which exist).
3. If the native draw throws, the real canvas is restored and the original method is re-invoked once; if composing throws, the view is invalidated and redraws natively.

Scope: every notification surface (heads-up, shade, lockscreen). Heads-up, keyguard and shade state are logged as `DECISION surface=` but no longer gate rendering; only enabled, attached, hardware-accelerated and not pressed/focused/hovered do. The Samsung blur accepts shapes with equal top and equal bottom corner pairs when the four-radius setter exists (order assumed top-first, which is irrelevant for such shapes), otherwise only uniform corners.

## Material tuning and shared shade backdrop (2026-10-07)

Device feedback after the composition fix: glass applied on every surface without errors, but the heads-up looked like an almost opaque dark card and shade rows looked unchanged.

- The Samsung blur received the fill color (`0x991b1d22`, 60 % opaque), which hid the blur. The blur now gets a near-transparent color (`0x14000000` / `0x14ffffff`), and the material draws its own fill (`0x4d1c1f26` dark, `0x47ffffff` light) under a stronger edge shader (0.55).
- `BLUR_MODE_WINDOW` blurs what is behind the window. In the expanded shade, the shade scrim is drawn inside the same window above that blur, so per-row blur was invisible and only cost GPU. Expanded-shade rows (not heads-up, not keyguard) now use `SharedBackdrop`: fill and edge only, over the shade's own blurred scrim. Heads-up and lockscreen rows keep the Samsung blur.
- `DECISION` is logged when either the surface or the reason changes and reports the chosen backdrop.

## Edge optics rewrite (2026-10-07)

`LiquidGlassShader` was rewritten from the principles listed in `docs/references.md` (WaEnhancerX entry). The previous shader shaded the body top to bottom, which reads as a moulded dome. The new pass has no vertical term: hairline, lit runs, opposite run and thickness shadow are all functions of the signed distance to the native rounded outline. Output is premultiplied additive light plus a shadow alpha. Tuning lives in `GlassSpec`: blur 16 dp, blur color 24-28 % (tone and legibility), fill 4-6 % on blurred surfaces and 8-18 % on shade rows (separation from the scrim), bevel 20 dp capped at 32 % of the short side, hairline 0.95 dp clamped to 1.5-5 px. AGSL compiles only on the device; a compile failure logs `SHADER_UNAVAILABLE` and leaves blur and fill in place.

## Captured backdrop and refraction (2026-10-07)

Probe data on S938BXXUCZZIC (One UI 9, SDK 37). The probe ran at scale 0.5, with the shade window excluded and the crop set to the row plus 80 px:

| Run | Samples | Rate | Ready P50 | Ready P95 | Ready P99 | Failures | Secure layers | Backdrop changed |
|---|---|---|---|---|---|---|---|---|
| Heads-up | 88 | 15 Hz | 7.94 ms | 10.31 ms | 13.70 ms | 0 | 0 | 25 of 87 captures |
| Shade centre | 22 | 2 Hz | 3.6–4.3 ms | up to 12.7 ms | – | 0 | 0 | 0 of 19 captures |

Every capture allocated a new buffer.

From this data, lockscreen rows, and heads-up rows while the shade is closed, get backdrop kind `CAPTURE`. Every row in the expanded shade keeps `SHARED`, because the shade draws its own scrim and blur below them, and sampling the unblurred app there would bypass that scrim. That includes a heads-up row that arrives while the shade is open: it is an unpinned heads-up drawn in the shade, and capturing for it sampled the home screen behind the shade (`Eligibility.captureSurface`).

`CaptureHub` runs one loop for all sampling rows:
- each tick captures the union of their on-screen rectangles plus 80 px, at half scale, below their windows;
- it runs at the highest requested rate (heads-up 15 Hz, lockscreen 5 Hz);
- it runs only while a row is shown and the screen is interactive.

Fallbacks:
- A frame with secure layers backs capture off for 3 s.
- Five consecutive failures disable capture for the process.
- A refraction program that fails to compile on the device disables capture.
- In all three cases the affected rows fall back to Samsung blur.

`LiquidGlassShader.REFRACT_SOURCE` draws the opaque material from the captured bitmap. Each channel is displaced inward along the outline normal by the Snell shift of a quarter-circle bevel. The shift is mirrored by `GlassSpec.refractionShift`, with a peak of 16.19 px about 11 px inside the outline for b = 70 px and n = 1.5. The program then:
- samples it already blurred: the frame is drawn into a RenderNode (bounds plus a 2 × radius + 2 px margin, CLAMP edges) whose effect chain runs a Skia Gaussian blur and then this program, so no blur is approximated by taps (a fixed 9-tap ring produced ghosted copies at radius 10);
- saturates it;
- veils it with a light tint and the fill;
- applies the shared edge optics.

Because the row slides and stacks through RenderNode properties, its screen position is re-read every pre-draw, and the view is invalidated when that position moves.

Live knobs are read as `debug.oulg.*` system properties, at most once a second, and logged as `TUNING`:

| Property | Default | Meaning |
|---|---|---|
| `backdrop` | `auto` | `grid` draws a synthetic numbered grid (lines every 40 px); `off` disables capture |
| `refract` | 1.0 | Multiplier on the physical shift (about 2.6 gives the stylised 42 px peak) |
| `ior` | 1.5 | Refractive index |
| `blur` | 8 | Gaussian blur radius (RenderEffect), screen px, max 32 |
| `sat` | 1.2 | Saturation |
| `disp` | 0.10 | Relative red/blue shift; physical glass is about 0.01 |
| `hz` | 15 | Heads-up capture rate |
| `hzkg` | 5 | Lockscreen capture rate |
| `tint` | -1 | Veil alpha override, 0–255 |

Not measured yet: SurfaceFlinger GPU time per capture and battery cost. Protected (DRM) layers capture black, and no flag reports them.

## Live material instead of capture by default (2026-10-08)

On the device, the captured backdrop under heads-up rows visibly lagged behind anything moving in the app below (perceived as ~5 fps). That lag is structural:
- The app behind a heads-up lives in another process and another window. SystemUI can only get its pixels as periodic screen captures, each landing one or more frames late.
- The WaEnhancerX Community glass is fluid because `GlassPane` redraws WhatsApp's own views into a RenderNode every frame. It records display lists and never captures pixels. That only works for content in the same process.
- Honor Liquid Glass Restore (VoreulCH, MIT, v2.2) ships no renderer. It flips flags and scales fields of Honor's proprietary pipeline, which, per its author, refracts a one-shot, pre-blurred wallpaper screenshot taken at pull-down. It offers no same-frame refraction of live app content to port.

Default backdrop policy (`debug.oulg.backdrop=auto`):

| Surface | Backdrop | Why |
|---|---|---|
| Heads-up over an app or the home screen | Samsung compositor blur (`SemBlurInfo`, `BLUR_MODE_WINDOW`) with a light veil, an optional color curve (`semcurve`, default `spatial` preset) and our edge optics | Rendered by SurfaceFlinger in the same frame as the app, so motion behind stays fluid. Edge optics only: no pixel refraction is possible without a live source |
| Lockscreen rows (heads-up on the keyguard included) | `WallpaperBackdrop`: the lock (or system) wallpaper, decoded once per wallpaper id, center-cropped to the display at half scale and uploaded as a hardware bitmap | The source is static, so it is redrawn on the GPU every frame at the row's position, through the same RenderNode + Gaussian blur + refraction chain. Full refraction, no lag. A live wallpaper reports unavailable and falls back to the Samsung blur |
| Expanded shade | `SHARED` (the shade's own scrim and blur) | Unchanged |

`debug.oulg.backdrop=capture` keeps the periodic-capture path for experiments. `grid` keeps the synthetic test texture. `off` disables sampling.

New Samsung knobs:

| Property | Default | Meaning |
|---|---|---|
| `semradius` | -1 | Compositor blur radius in px; -1 uses 16 dp |
| `semcurve` | `spatial` | One of `spatial`, `dim`, `ultra` or `none`, selecting the `COLOR_CURVE_TYPE_*_BACKGROUND_{LIGHT,DARK}` preset for the theme. Six comma-separated floats call `setColorCurve` directly |
| `semalpha` | -1 | Veil alpha, 0–255 |

Any knob change bumps `Tuning.generation`, which re-applies the Samsung blur and redraws the row on its next pre-draw.

## Hybrid heads-up and lens profile (2026-10-08, after device test)

### What the device test showed

- **Lock wallpaper.** The lock wallpaper on the test device is a live wallpaper (`WALLPAPER_BACKDROP_UNAVAILABLE reason=live`), so lockscreen rows fell back to the Samsung blur.
- **Samsung blur with the `spatial` curve.** With the `spatial` color-curve preset, screenshots showed the backdrop sharp behind heads-up rows: the Samsung blur did not render. `semcurve` now defaults to `none`.
- **Compositor blur alone.** It reads as a grey card with no lens, which is not a liquid-glass material.

### Policy in `auto`

**Heads-up rows** use `HybridBackdrop`:
- The body is the Samsung compositor blur, so it stays live.
- The lens band is drawn from captures at `rimhz` (default 60 Hz), with a Gaussian of `rimblur` px (default 24) before refraction.
- The shader runs with `rimOnly`: it is opaque over the first 35 % of the bevel, fades to transparent at the bevel and is transparent in the body.
- Only that band can trail motion, by about one to two frames at 60 Hz, instead of the whole card lagging at 15 Hz.
- The rim uses the Samsung veil colour and no saturation boost, so it matches the body.

**Lockscreen rows** use the still wallpaper when there is one. Over a live wallpaper they use a capture at `hzkg`: the keyguard barely moves, so capture lag does not show.

When the capture is unavailable (secure content, failures), heads-up rows drop to the plain Samsung blur.

### Lens profile

The default profile is the bounded-slope lens recommended by the WaEnhancerX laudo (LG-01):

```
shift = lens × bevel × (1 − depth/bevel)²
```

- Its sampling slope is at least 1 − 2 × lens, so the image never folds.
- `lens` defaults to 0.30 (21 px at a 70 px bevel) and is capped at 0.45.
- `GlassSpec.lensShift` mirrors the shader formula, and a unit test checks the slope bound.

The physical Snell model (about 16 px, zero at the outline and weak to the eye) is still available with `debug.oulg.profile=snell`.

### Knobs added

| Property | Default | Meaning |
|---|---|---|
| `profile` | `lens` | `lens` or `snell` |
| `lens` | 0.30 | Lens shift at the outline as a fraction of the bevel, 0–0.45 |
| `rimhz` | 60 | Capture rate for the heads-up lens band, 1–120 Hz |
| `rimblur` | 24 | Gaussian radius of the lens band, px |

`hz` and `hzkg` now accept up to 120 Hz.

### Samsung blur shape, strength and veil (2026-10-08, second device pass)

Screenshots of the compositor-only build showed three problems:
- The blur sat in a band in the middle of the card, not out to the sides. The suspected cause is the four-radius corner setter.
- Content behind the lower part stayed sharp.
- Dark mode read as grey.

Changes:
- `SemBlurBridge.applyShape` now picks the blur shape by `semshape`:

  | Value | Shape |
  |---|---|
  | `auto` (default) | Single-radius setter when the shape is uniform, otherwise the clip path |
  | `single` | Single-radius setter |
  | `path` | Exact rounded-rect `setBackgroundClipPath` |
  | `four` | Previous behaviour (four-radius setter) |
  | `none` | Rectangular region |

  The mode in use is logged as `SEM_BLUR_SHAPE`.
- The default compositor radius is `GlassSpec.SAMSUNG_RADIUS` = 180. SystemUI's own blur region uses 250 in traces.
- The veil is a light white frost in both themes, closer to the iOS notification material: `0x40ffffff` in light mode, `0x14ffffff` in dark mode. `semalpha` still overrides it.
- `Probe.painters` logs, once per row, every view under a notification row that paints a background (`PAINTER`). This is to name the opaque layer that covers the glass when Theme Park is off.

### Theme Park and foreign blurs (2026-10-08, third device pass)

Device evidence:
- **Theme Park off.** The heads-up row showed a full-card compositor blur. With `semshape=none` it came out as a rectangle with visible square corners.
- **Theme Park on.** The blur appeared only in a band in the middle of the card, whatever the shape mode.
- **No PAINTER lines.** No view under a row paints an opaque background, so the module does not need Theme Park. The black card without it is the native drawable, which the module already replaces.

Working hypothesis: Theme Park sets its own Samsung blur on the same notification views and replaces ours.

Changes:
- **Blur guard.** `View.semSetBlurInfo` is hooked in SystemUI.
  - Calls that do not come from `SemBlurBridge` are logged once per view class and caller, as `SEM_BLUR_FOREIGN`.
  - Such calls are blocked on background views, and on their rows, when the module drives that material with the Samsung blur (Samsung or hybrid).
- **`shadeblur` knob.** With `debug.oulg.shadeblur=1`, expanded-shade rows get their own Samsung blur instead of the shared shade backdrop. This is an experiment for the case where Theme Park is off and the shade rows read flat grey.


### Intermittent dark cards (2026-10-08, fourth device pass)

Device evidence:
- With Theme Park off and `shadeblur=1`, opening the shade sometimes showed cards in Samsung's dark native look. They returned to the white frosted look once a new notification arrived, which rebuilds every material.
- The guard did fire on SystemUI's own call: `SEM_BLUR_FOREIGN managed=true blocked=true` on `NotificationBackgroundView`, with Theme Park off.
- The caller could not be named. LSPosed obfuscates its hook classes, so the name filter returned the hook wrapper.

The Samsung blur used to be applied only when the material was built or its shape or tint changed. Nothing restored it if it was lost afterwards. Some material paths also fell back to the native background with no log line, and one of them was sticky:
- unsupported corners mid-animation drew the native background for that frame;
- a non-circular radius left the row native for its whole lifetime.

Changes:
- **Event-driven blur repair.** Nothing polls and nothing runs per frame. The blur is applied again on events that can drop it:
  - the shade opening or closing;
  - each row lifecycle event;
  - a call to any setter-like View blur method that touches a card whose blur this module drives. Every such method is hooked once at install and logged as `BLUR_MUTATORS`. A call is logged as `SEM_BLUR_MUTATED` with its caller.

  A clip-path blur is rebuilt from a layout listener when the view resizes. After each apply, the `SemBlurInfo` field is read once. If the call was swallowed, this is logged as `SEM_BLUR_SWALLOWED`.
- **Corners.** A shape the corner setters cannot express uses the clip path. It no longer falls back to the native background.
- **Fallback log.** A geometry rejection is no longer sticky. Every frame where the native background draws instead of the glass is logged as `NATIVE_FALLBACK reason=…`, deduplicated per reason change.
- **Caller.** `SEM_BLUR_FOREIGN` names the three frames after the last `semSetBlurInfo` frame instead of filtering class names.

### Native blur restored on release (2026-10-08, fifth device pass)

Device evidence:
- After a test that switched lockscreen rows to `backdrop=off` and back, lockscreen notifications lost their frosted look and showed only the refraction.
- Cross-window blur was disabled in every log, including the earlier passes where the lockscreen looked right. That rules it out as the cause.

Cause: SystemUI sets its own blur on notification background views once, when the row is created; on the lockscreen this is the frost under the refraction. `SemBlurBridge.release()` cleared the view's blur to `null`, which erased that native blur for the row's lifetime.

Fix:
- The blur guard records the last blur that SystemUI (or a theme) set on each notification view.
- On release, `SemBlurBridge` restores that blur instead of clearing it, and logs `SEM_BLUR_NATIVE_RESTORED`.

### Nested clear blocked (2026-10-08, sixth device pass)

Device evidence: Samsung's `View.semSetBlurInfo` logs every call. Each of our applies on a `NotificationBackgroundView` was followed, in the same millisecond and nested inside it, by `semSetBlurInfo(null)` on the same view. `SEM_BLUR_SWALLOWED held=null` was the result, and this is why the heads-up body showed no blur.

The framework code, read from this firmware's `framework.jar`, shows the path:
1. Window mode calls `invalidateBlurBackground()`.
2. That installs a `BackgroundBlurDrawable` through `setBackground()`.
3. `View` itself never passes null to `semSetBlurInfo`, so the clear comes from SystemUI's notification view reacting to the background change.

Fix: while a bridge applies, the blur guard blocks a nested `semSetBlurInfo(null)` aimed at that same view. It logs `SEM_BLUR_NESTED_CLEAR` with the issuing frames, so the culprit is named on the next device log.

### SurfaceFlinger probe, phase 1 (2026-10-08)

Facts that make a compositor-side refraction feasible on this firmware:
- RenderEngine runs on **GLES (Ganesh)** and is linked statically into the stripped `surfaceflinger` binary.
- The binary imports `glShaderSource` and `eglGetProcAddress` from the GL libraries, so a library loaded first can intercept every shader it compiles.
- With the module's Samsung blur working, the heads-up card is a SurfaceFlinger blur region with its exact geometry, for example `50,152–1390,368 | 1340×216 | blur 180 | corner 108` on `VRI-NotificationShade`.

`sfhook/` is a KernelSU module that only reads (phase 1).

Install (`customize.sh`):
- Refuses any firmware fingerprint other than `S938BXXUCZZIC`.
- Copies `/system/bin/surfaceflinger` into the module and patches it on the device with `dtneeded`. No Samsung binary leaves the phone.
  - `dtneeded` replaces the `DT_DEBUG` entry with a `DT_NEEDED` entry at the head of the list. The name reuses the `SurfaceFlingerProp.so` suffix of an existing `.dynstr` string.
  - The change is 88 bytes, all inside `.dynamic`.
- Installs `oulg_sf.c` under that name.

At runtime:
- `oulg_sf.c` interposes `glShaderSource` and `eglGetProcAddress`.
- It writes each distinct shader source once, complete, to `/data/misc/surfaceflinger/oulg_shaders.txt`, then passes the call through unchanged.
- Logcat (tag `OULG_SF`) only gets `LOADED` and one short line per shader. On device, logcat truncated messages at about 1000 characters and dropped most of the burst.

Safety: a boot watchdog in `service.sh` disables the module and reboots if surfaceflinger restarts 4 times within 90 s.

Shader cache: Samsung's SurfaceFlinger keeps compiled programs in `/data/misc/surfaceflinger/skia_shaders` and `egl_shaders`. It loads them with `glProgramBinary` and never calls `glShaderSource` for them. `post-fs-data.sh` deletes both files before surfaceflinger starts, so every shader is compiled again and logged. The cache is rebuilt automatically.

Build: `ANDROID_NDK=… sfhook/build.sh` produces `sfhook/build/oulg-sf-phase1.zip`.

### SurfaceFlinger refraction, phase 2 (2026-10-08)

The device dump has 138 programs. Skia draws a blurred texture clipped to a circular rounded rect with programs that sample `uTextureSampler_0_S1` at `vTransformedCoords_<k>_S0`. The clip arrives as uniforms: `uinnerRect_S<n>` is the rect inset by the radius, and `uradiusPlusHalf_S<n>` is the radius + 0.5. `sk_FragCoord` is in device pixels.

`sfhook/refract.h` rewrites those programs; 12 of the 138 match. The rewrite applies only when the radius fraction is .625 (radius + 0.5), which is the tag. Any other rounded-rect draw keeps its exact behaviour. Inside the bevel band, `bevel = clamp(0.42 r, 16, 56)`:
- **Lens:** the sample point moves inward by `0.30·bevel·t²`, with `t = 1 − depth/bevel`, the same lens profile as the captured rim. The move is converted to texture space through `dFdx`/`dFdy`, which are taken in uniform control flow.
- **Highlight:** the rim is brightened by `0.22·t³`.

Safety:
- The hook compiles each rewrite immediately. If the driver rejects it, the original source goes back before Skia compiles.
- `debug.oulg.sf.norewrite=1` disables every rewrite.
- `sfhook/tools/refract_check.c` replays a dump so each rewrite can be checked with `glslangValidator`. All 12 rewrites of the device dump compile.

Module side: `debug.oulg.sfrefract=1` changes two things.
- `SemBlurBridge` sets the single corner radius to `floor(r) + 0.125`, the tag.
- Heads-up and lockscreen rows keep the live Samsung blur instead of a sampled or captured backdrop, so the compositor's lens replaces the captured one.

### Phase 2 on the device (2026-10-09)

Diagnostic after a live swap: `LIB_NOVA=1 SRC=136 ALVO=66 RW_OK=12 RW_FAIL=0`. The rewrite compiles on the device.

Correction (later the same day): the look first credited here to the compositor lens came from the app at 0.1.14, which has no `sfrefract` and so never tags a card. That look is the module's own refraction: the wallpaper on the lockscreen, the captured rim on heads-up rows. From 0.1.15, with `debug.oulg.sfrefract=1`, cards carry the tag and drop the module's own refraction, and the device shows only the Samsung blur (radius 180) with no visible lens. So the rewritten programs do not reach the tagged blur regions, or the tag does not survive to SurfaceFlinger. Not resolved; `sfrefract` stays off by default.

- **Expanded shade:** looks fine. A small stutter on pull-down is still open; it reads as dropping from 120 to 60 Hz. No A/B measurement yet.

Operational notes:
- **Updating without a reboot.** KernelSU stages an update of an installed module in `/data/adb/modules_update/<id>`, which moves to `modules/` only at boot. A live swap must bind-mount the library from `modules_update`, and it is checked with `grep -c "REWRITE shader"` inside surfaceflinger's mount namespace.
- **Restarting surfaceflinger.** Every `stop`/`start` restarts the whole userspace (boot animation, system_server, apps). On the device each restart took longer, up to 4–5 min on "Powered by Android". The hook only works when a shader is first compiled, so a restart is needed only to load a new library or to drop the rewrites, and should otherwise be avoided. Cause not measured yet.
- **Cache keeps the rewrite.** Skia caches the program binary it linked, rewrite included. `debug.oulg.sf.norewrite=1` or removing the module takes effect only after `skia_shaders` and `egl_shaders` are deleted and surfaceflinger is restarted. The running process keeps its linked programs, and the flag is read only in `glShaderSource`. Leftover rewritten binaries are inert while no card carries the radius tag (`sfrefract=0`).

### Main-thread cost in the shade (2026-10-09)

gfxinfo for SystemUI over 10 shade open/close cycles:

| Build | Median | 90th | Janky | Slow UI thread |
|---|---|---|---|---|
| Module off | 5 ms | 12 ms | 13.8 % | 30 |
| Module on, before | 13 ms | 34 ms | 36.7 % | 163 |
| Reflection cache | 5 ms | 17 ms | 23.6 % | 80 |
| Cache, diagnostics gated | 5 ms | 12 ms | 20.1 % | 49 |

GPU time was 1–7 ms in every run, so the cost is on SystemUI's main thread.

- **Reflection cache.** The pre-draw listener and the draw hook run once per row per frame and read rows reflectively. `Reflect` now caches field and method lookups per class and name, misses included. An uncached lookup scanned declared members up the hierarchy and threw an exception per level it missed.
- **Remaining jank.** `debug.oulg.perf=1` showed the hook bodies at about 1 % of the main thread, so the remaining jank lies elsewhere. The diagnostic dumps that walk view trees and windows ran on the main thread, the scrim walk right as the shade starts to open. They are now off unless `debug.oulg.trace=1`:
  - scrims, painters, hierarchy, view and material snapshots;
  - window surveys;
  - caller stack walks.
- **Perf slots.** The `shade` slot times the expanded-height hook.
- **After gating.** With the cache and the dumps gated, the module is close to off: same median and 90th percentile, 49 vs 30 slow-UI-thread frames, 99th percentile 53 vs 42 ms. All hook bodies together take about 0.5 % of the main thread while the shade animates. The heaviest per call is `drawBefore`, about 40–75 µs per row redraw.

### Compositor lens, second attempt (2026-10-09)

Why the first attempt never showed:
- **Wrong programs.** The rewrite targeted programs that clip to a rounded rect (`uinnerRect`/`uradiusPlusHalf`). A blur region is drawn with `canvas->drawRRect` and an image shader, which Ganesh renders with `FillRRectOp`. In the device dump, that op's vertex shaders take `radii_selector`/`radii_x`/`radii_y`/`skew` as instance attributes. Its fragment shaders only get normalized arc coordinates (`varccoord_S0`). None of the 12 programs rewritten before draws a blur region.
- **Tag destroyed.** The heads-up card is a pill: 216 px high with corner 108. The tag `floor(r) + 0.125` gave 108.125. `SkRRect` scales radii that exceed half the side, back to 108.0, so the fraction disappeared.

The rewrite now works in two stages:
- **Vertex shaders.** Rounded-rect vertex shaders with texture coordinates gain two outputs:
  - `voulg_tag`: whether the card is tagged, the radius in px and the half size in px. The radius is `radii.x / pixellength.x`, read before Skia's clamps.
  - `voulg_vp`: the normalized position.
- **Fragment shaders.** Those that sample one texture through `vTransformedCoords` compute the rounded-rect distance in px. Inside the bevel they shift the sample inward, by `0.30·bevel·t²` with the same bevel and highlight as before. The shift is mapped to texture space through the Jacobians of `voulg_vp` and the texture coordinates, so rotation and flips are handled.
- **New tag.** The tag is `floor(r) − 0.375`, fraction .625. It is never above the original radius, so Skia keeps it.
- **Expanded shade.** Cards in the expanded shade (`shadeblur=1`) are not tagged and stay a diffuse blur.
- **Blur radius of lens cards.** The lens bends the image that was already blurred. At the Samsung radius of 180 px nothing is left to bend, and on the device the cards read as a plain blur. Lens cards now take `semradiuslens`, default 12 px. With `semradius` 4 the device showed clear glass with a visible rim lens. In the expanded shade, cards blinked while the list scrolled at that radius, and stopped at 180. Samsung did not re-apply the blur during that scroll: `semSetBlurInfo` logged 0 calls. Shade cards keep `semradius`.

On the host, 11 vertex and 24 fragment shaders of the device dump are rewritten, and all compile with `glslangValidator`.

Link safety: Skia compiles a program's fragment shader before its vertex shader. A vertex rewrite that fails falls back to a version that declares the outputs and zeroes them. If that also fails, or a rounded-rect vertex shader is not recognised, fragment rewrites stop for the rest of the process.

### Lockscreen and lens fixes (2026-10-09, later)

- **Dark card on the lockscreen.** A pressed, focused or hovered row used to release its glass and draw Samsung's dark native card. That flashed on every tap, and a partial pull-down left the row focused. The glass now stays on, and a press adds a light wash.
- **Lens after a partial pull-down.** On a partial pull-down the status bar state goes 1 → 2 → 1, and rows stay "off keyguard" after the shade springs back. The lens decision now follows `StatusBarState`: keyguard means lens. It is re-decided for every card when the state or the shade changes, because a card that is not redrawn keeps its blur, which is a view property.
- **SurfaceFlinger v0.4.**
  - It also rewrites rounded-rect clip programs. Their `uradiusPlusHalf` carries the tag as fraction .125.
  - The rim highlight is dropped from the compositor; the module draws its own, and two rims misaligned by a frame flickered during drags.
  - `debug.oulg.sf.lens` sets the lens strength, default 0.45. `debug.oulg.sf.debug=1` paints tagged cards magenta on the FillRRect path and cyan on the clip path, to show which path draws them.
  - Both are read when shaders compile.
  - On the 147-shader device dump: 12 vertex, 26 FillRRect and 13 clip rewrites, all compile.

### Debug tint result and live lens (2026-10-09, later)

`debug.oulg.sf.debug=1` painted lockscreen and heads-up cards magenta. The tag reaches SurfaceFlinger, and `FillRRectOp` draws the blur region. Expanded-shade cards stayed untinted, as designed.

The tint also flashed on every app launch. A launching window's corner radius sweeps continuously, and some frames hit the tag band.

Changes in v0.5:
- **Whole-pixel sides.** A tagged rect must also have sides that are whole pixels: the half axes come from `skew` and the full size from the clip inset. A window scaled mid-animation is fractional.
- **Live lens strength.** The tag fraction now spans [0.55, 0.70] and encodes the lens strength `k = 0.1 + 1.1·(f − 0.55)/0.15`. The module sets it from `debug.oulg.sflens` (default 0.45). The strength therefore changes live, without a SurfaceFlinger restart; the `debug.oulg.sf.lens` property is gone.

### Smooth blur texture on lens cards (v0.6)

The device showed the lens working live at `sflens` 1.2 and `semradiuslens` 12, but two things looked wrong:
- **Blocky body.** The blur read as a 144p video. The blur region's texture is downscaled, and bilinear upscaling of a lightly blurred image shows its texels as blocks.
- **Over-strong rim.** The refraction at the rim was too intense at 1.2.

Tagged cards now sample that texture through a cubic B-spline built from 4 bilinear taps. External (video) textures keep the plain sample, because `textureLod` is not allowed on them. Untagged draws are unchanged.

The defaults move to `sflens` 0.8 and `semradiuslens` 16.

The scrim trace no longer matches "dim" inside `*ImageView` class names. That match used up its budget before the real scrims were logged.

### Layered blur on lens cards (v0.7)

The device at v0.6 showed a good rim, but the body was too readable through: one blur (`semradiuslens`, light so the rim has detail to bend) covered the whole card. The user wants a strong blur in the body and a light one at the rim, with a gradual step.

The FillRRect fragment rewrite now adds a second blur on tagged cards, inside the same draw:
- **Depth.** The rounded-rect distance from the outline, in px, now also inside the straight part (before, it saturated at the corner radius).
- **Radius.** `core · smoothstep(0.25·bevel, ramp·bevel, depth)`: 0 at the rim, where the B-spline sample and the lens stay as in v0.6, the full core radius in the body.
- **Disc.** A Vogel (golden-angle) disc of bilinear taps, Gaussian-weighted (`exp(−2ρ²)`), in screen pixels mapped to texture space by the derivatives of the texture coordinates. It is centred on the lens-shifted coordinate. Each pixel rotates its disc by interleaved gradient noise, so tap gaps show as fine grain rather than ghost copies.
- **Blend.** Below a 6 px radius the disc fades into the B-spline sample, so the start of the ramp does not show bilinear blocks.

Two nested Samsung blur regions were rejected: SurfaceFlinger draws each with a hard edge, a step and not a gradient.

Parameters, read when the shader compiles (restart SurfaceFlinger with the cache cleared to change them):
- `debug.oulg.sf.core`: body radius in px, default 48; 0 turns the second blur off.
- `debug.oulg.sf.taps`: taps per pixel, default 24, 4–48.
- `debug.oulg.sf.ramp`: depth where the body radius is reached, in bevels, default 1.5, 0.5–8.

Host checks: the rewrite compiles and links with `glslangValidator`, and a WebGL2 render of the rewritten fragment shader (Chromium/SwiftShader, synthetic quarter-resolution blur texture) shows the gradient from a sharp refracted rim to a blurred body. With a lightly blurred source, 16 taps left visible grain at a 48 px radius; 32 were clean; 24 is the default. The device dump was not available in this session, so the check ran on synthetic FillRRect shaders modelled on Skia's, not on the 147-shader dump. Cost: `taps` extra texture reads per pixel of tagged cards only.

## Future work

- **Separate blur for the notification center and the control center.** Theme Park and HomeUp set a single blur amount for both panels. The user runs 12 %: lower leaves the control center unreadable, higher over-blurs the notification list. A split needs its own investigation: find where SystemUI applies the panel blur, whether the two panels are separate blur regions or one window, and whether the module can own one of them.
- **Tuning the compositor lens without a restart.** Lens strength, bevel width and highlight are constants in `refract.h`, so each change needs a surfaceflinger restart and a cleared cache. The radius fraction already carries the tag and could also carry the strength, so the module could tune it live.
