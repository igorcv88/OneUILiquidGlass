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

### Lockscreen gray and flashes: blur drawn with the glass (2026-10-09, later)

Three defects on the lockscreen with the compositor lens, all now fixed and confirmed on device over several lock cycles:

- **Gray cards after a partial pull-down, until the next lock.** A Samsung blur with no color curve of its own takes the compositor's last one, and the panel blur leaves its dark curve behind. `semcurve=spatial` reproduced the gray permanently; the explicit curve `0,0,0,255,0,255` kept the glass normal. `debug.oulg.semcurve` now defaults to `auto`: that neutral curve on lens cards, none on shade cards.
- **Two frames of a wrong, heavily blurred texture at the start and end of a slow pull, and the blur region lagging the card by a frame ("ghost").** With `sf.debug=1` the card stayed magenta in those frames, so the rewritten shader drew them; the input texture was wrong. Ruled out on the way: the scrims, the panel window blur (dropped, or kept alive at radius 1), `setBackgroundBlurRadius`, the `CapturedBlurContainer`, keeping the lens through the shade-locked state, SurfaceFlinger layer caching (worse when off) and `debug.renderengine.restore_blur_step` (helped for one lock cycle only).
  - **Cause:** the Samsung blur was installed on the view with `semSetBlurInfo`, so its region followed the view's bounds and update path. The glass follows the native drawable's bounds, which track the card's actual height during the pull.
  - **Fix:** on the lockscreen and the shade over it, lens cards take their blur from the `BackgroundBlurDrawable` that `ViewRootImpl.createBackgroundBlurDrawable()` returns, drawn inside the glass with the glass's bounds. It carries the same radius and lens tag (`BackgroundBlurBridge` lens mode). This path works although `isCrossWindowBlurEnabled()` reports false on this firmware: the listener no longer releases it, and the Samsung blur is taken off the view while managed and restored on release.
  - **Veil and radius:** the path uses the Samsung veil (`semalpha` over the Samsung tone). `setBlurRadius` takes px; `debug.oulg.kgblurradius` sets it, -1 = `semradiuslens`.
- **Ghost on a pull up toward the bouncer.** The cards fade through ancestor view alpha. That fades the drawn glass but not the compositor's blur region. Before each frame the region now takes the accumulated alpha of the view's ancestors.

`debug.oulg.kgblurpath` (default 1) switches the lockscreen back to the Samsung path with 0. A hidden card's blur is cleared rather than set back to SystemUI's radius-180 blur, which is restored only if the card is shown without glass. The radius 1–4 panel blur ramps a touch starts on the idle lockscreen are still dropped (`debug.oulg.kgwinblur=1` lets them through). Neither was isolated as necessary; both were active in the configuration confirmed on device.

The idea came from an outside review of the earlier handoff, which had concluded too early that both defects were out of the app's reach.

### One optical library for both programs; per-surface profiles (sfhook v0.8, 2026-10-10)

Device report at v0.7 with `sfrefract=1`, `sflens=0.30`, `semradiuslens=16`, `semalpha=12`:
- `kgblurpath=0` (Samsung blur on the view): body homogeneous and well filtered, but the region flickers and lags on the lockscreen.
- `kgblurpath=1` (`BackgroundBlurDrawable` drawn with the glass): stable, but the body looks under-filtered and blotchy.
- `semradiuslens` 12 → 16 helped; 16 → 20 did not fix it.

**Which program draws each path.** The Samsung path is proven to be the FillRRect program: `sf.debug=1` painted it magenta. The drawable path was never tinted, so its program is not proven on device. The code shows two rewrites with different content:
- `oulg_rewrite_fragment` (FillRRect) applies the lens, the B-spline and the layered body blur.
- `oulg_rewrite_clip` (CircularRRectEffect, uniforms `uinnerRect`/`uradiusPlusHalf`) applied only the lens. It sampled the texture bilinearly, the v0.6 "144p" defect, and had no body blur.

A card drawn through the clip program looks exactly like the report: rim refraction present, body under-filtered and blotchy, better at radius 16 than 12 but not cured by 20. A WebGL2 render of the v0.7 clip rewrite over the same synthetic quarter-resolution texture leaves 17 times the background structure in the body that the FillRRect rewrite leaves (RMS 27.1 vs 1.6). This is strong evidence, not device proof. The deciding test is the tint: with `sf.debug=1` and `kgblurpath=1`, cyan means the clip program, magenta means FillRRect. If it is magenta, the remaining difference is the compositor's blur input, which the Samsung path may build differently (it can carry a color curve); `BLUR_DRAWABLE_API` logs what the drawable can set.

**Change: one shared library.** Both rewrites now inject the same GLSL: `oulg_field`, the B-spline, the Vogel discs and the sampling switch. Each program only maps its own geometry into the card's pixel frame:
- FillRRect: `q = vp · hs`, back to the screen through the inverse Jacobian of `vp`, then to the texture through the texture Jacobian.
- Clip: `q = sk_FragCoord − centre`, where the centre and size come from `uinnerRect` and `uradiusPlusHalf`, back through `dFdx`/`dFdy`, with the `u_skRTFlip` sign on y.

The clip path also gained the full rounded-rect depth, inside the inner rect too, which the body ramp needs. Before, its depth was only defined in the rim band. On the render check, the two programs draw the same card within 1/255.

**Surface isolation: optical profiles.** Heads-up rendering is the established baseline and must not move with lockscreen work. The tag now carries a profile beside the strength:
- **Profile 0, established:** fraction band [0.55, 0.70], unchanged. Heads-up cards, and lockscreen cards with `debug.oulg.kgoptics=0`.
- **Profile 1, lockscreen:** fraction band [0.30, 0.45], taking the largest tagged radius not above r (at most 1.15 px off).
- The vertex shader and the clip program decode `kp = k + 2·profile`, and `oulg_field` splits it.
- **Who gets profile 1:** `Eligibility.keyguardOptics(barState, headsUp)` decides. It is true for bar state 1 or 2 and never for a heads-up.
- **A/B:** the module sets the profile live from `debug.oulg.kgoptics` (default 1), so switching needs no SurfaceFlinger restart.

Profile 0's output is bit-identical to v0.7's: the render check shows a maximum difference of 0 against the v0.7 FillRRect rewrite. The expanded shade is untagged and samples exactly as before.

**Lockscreen material (profile 1).** Parameters, read at compile time:
- `sf.kgcore` 56 px: body blur radius.
- `sf.kgtaps` 32: same tap density as 24 at 48 px.
- `sf.kgramp` 1.5.
- `sf.kgsat` 1.25: vibrancy. The body's saturation rises with the body blur, around Rec. 709 luma, and stays premultiplied-valid. It counters the gray, washed-out look of a veiled, desaturated blur.

The lens is the same as profile 0: the rim matches within 2/255, the residue of the 0.25 px tag-radius difference. On the synthetic texture, profile 1 leaves less body structure (1.44 vs 1.62) at slightly more grain (3.93 vs 3.79, mostly chroma from the vibrancy).

**Cost.** The B-spline is skipped once the disc fully replaces it (body radius ≥ 6 px).
- Profile 0 drops from about 24.3 to 21.3 texture reads per card pixel on a 1340×216 card, at identical output.
- Profile 1 reads about 26–28 per pixel in the body.
- Cards drawn by the clip program go from 1 to about 21–28 reads per pixel. That is the price of the filtering they were missing, and equals what FillRRect-drawn cards already cost.
- Untagged draws are unchanged.

**Field validation** (`sfhook/tools/field_check.c`, 8·10⁷ grid points, 7 card shapes, 6 strengths, both profiles):
- Shift is inward and at most `k·bevel`.
- C1 at the band's inner edge.
- 2-D Jacobian of `q → q + s(q)` matches `det = (1 − 2kt)(1 − k·bevel·t²/ρ)` within 2·10⁻⁹. On the straight edges the second factor is 1.
- det > 0 for k ≤ 0.5. The map folds, as a mirrored rim band, exactly where t > 1/(2k) for k > 0.5. At the user's 0.30 the minimum radial stretch is 0.4, so there is no fold. The code default `sflens` 0.7 does fold over the outer 29 % of the bevel.
- The body radius is monotone, 0 before a quarter bevel and the core radius past the ramp.
- Both programs decode the same `kp`.
- A mediump (fp16) `uradiusPlusHalf` keeps the profile below r = 127.5. From 128 the fp16 step can drop a clip-drawn card's tag (both profiles; FillRRect is highp).

**Host checks:** `sfhook/tools/check.sh` runs the field check, then rewrites `tools/testdata/synthetic_dump.txt`. That dump holds Skia-style FillRRect and clip programs, sampler2D and external, flipped and not. Every rewrite must compile and link with glslangValidator. `sfhook/tools/render_check.mjs` renders both programs and both profiles in WebGL2 (headless Chromium, SwiftShader) and prints the comparisons above. The synthetic programs are modelled on Skia's output; the device's 138-shader dump was not available in this session.

### Lockscreen material: one thick-slab model (sfhook v0.9, 2026-10-10)

Brief: surpass both lockscreen renderers while keeping `kgblurpath=1`'s stability. The interior read as cloudy and dirty, and the rim as low-quality refraction with a decorative glowing outline. Heads-up rendering stays the reference: profile 0 is untouched (bit-identical to v0.7) and the expanded shade is untagged. All of this lives in profile 1, which lockscreen cards carry by default.

**One spatial model.** Every term derives from the bevel coordinate `t = 1 − depth/bevel`, the outward normal `n` and one profile `G(t) = t²(3 − 2t)`.

- **Refraction.** The shift is `A·bevel·G(t)`, with `A = min(k, 0.5)` (0.55 in v0.9; see v0.10). The radial Jacobian is `1 − 6At(1−t)`:
  - It is 1 at the outline, so text crossing the rim is offset, not stretched.
  - It is 1 at the inner edge, so the join to the interior is C1.
  - Its minimum is mid-band, `1 − 1.5A ≥ 0.25`, so the map never folds.
  - Profile 0's quadratic, by contrast, has its strongest stretch at the outline (`1 − 2k`) and folds above k = 0.5.
  - The sampling map's singular values stay ≤ 1, so the texture is only magnified, never minified (no aliasing). A detail still grows up to 1/σ_min on screen: 2.5× at 0.40, 4× at the cap.
- **Rim transmission and reflection.** The surface tilt follows the same `G` (shift ∝ thickness × slope), reaching 1.2 rad at the outline. Schlick's excess reflectance mixes the refracted backdrop toward a bright environment. The rim therefore brightens over dark backdrops and stays nearly neutral over bright ones; no outline is drawn.
- **Specular.** A crescent on the light-facing rim (light from the upper left, as the module's edge optics) and a fainter internal one opposite. They are tinted 30 % by the backdrop's hue and fade over bright backdrops.
- **Interior.**
  - A 56 px Vogel disc with 40 taps.
  - Vibrancy: saturation rises with the body blur, to 1.25.
  - A soft knee on luma above 0.65, at most −15 %, against milky glare on bright wallpapers.
- **Lighting moved to the compositor, for profile-1 cards only.** The compositor now owns the lighting, from the real backdrop. The module's edge shader steps back on those cards (`GlassDrawable.setCompositorLit`): no lit runs, no thickness shadow, and the hairline at half gain for the outline. With `kgblurpath=1` the region and the glass are drawn in the same frame, so the v0.4 problem of two misaligned rims does not apply. Heads-up and shade cards keep `decor = hairGain = 1`, which gives identical output.

**Knobs.**
- **Live:**
  - `debug.oulg.kgoptics` (lockscreen material on or off).
  - `debug.oulg.kglens` (profile-1 strength, default 0.40; −1 follows `sflens`).
  - `debug.oulg.huoptics=1`: opt-in preview of the lockscreen material on heads-up cards.
- **Compile time** (SurfaceFlinger restart with the cache cleared): `debug.oulg.sf.kgcore` 56, `.kgtaps` 40, `.kgramp` 1.5, `.kgsat` 1.25, `.kgtone` 0.15, `.kgrim` 1.0, `.kgspec` 0.18.

**Measured on the host** (`render_check.mjs`). Reference backdrop: a photographic gradient with a bright half and a dark half, large high-contrast text crossing the top and bottom rims, turned into the compositor's input by a 4× downscale and a light blur. Card 720×300, corner 92.

| | grain | structure (5–40 px) |
|---|---|---|
| v0.7 clip (the suspected `kgblurpath=1` program) | 0.33 | 12.5 |
| profile 0 (heads-up; v0.7 FillRRect, identical) | 1.04 | 2.61 |
| profile 1 at 24 / 32 / 40 / 48 taps | 1.27 / 0.87 / 0.67 / 0.54 | 2.25 |

- **Lighting alone.** Profile 1 minus the same build without it, mean luma on the straight rims in 1/255: lit top edge +6.7 over bright and +10.0 over dark; bottom edge +1.8 and +3.0.
- **Program equivalence.** FillRRect and clip draw the same card within 1/255 in both profiles.
- **Cost** (SwiftShader, relative only). About 0.27 ms of CPU-rasterizer time per 1000 tap-pixels, linear in taps. Per pixel in the body, profile 1 at 40 taps reads ~1.7× profile 0, plus the lighting ALU in the band. Measured lighting cost: about +15 % of a card draw.
- **Rejected.** Gaussian importance sampling of the disc (taps by the radial CDF, equal weights). It draws the same kernel and was expected to give ~31 % more effective samples by the i.i.d. argument. Measured: more grain (1.33 vs 1.04 at 24 taps). A stratified pattern's outer rings get too sparse.

**Still device-dependent.**
- Which program draws `kgblurpath=1` (tint test).
- The real compositor input: its downscale factor and its blur algorithm (Kawase or Samsung's).
- Adreno timing (`timestats renderEngineTiming`).
- Whether 40 taps is the right point for battery.
- Real WhatsApp notifications.

**Considered, not built.**
- **Sampling the full-resolution original under the rim.** RenderEngine mixes the original input into blur regions below a radius of 10 px (`mixFactor`). A rewrite of that two-texture program could refract sharp content at the rim while the body uses the blur, the strongest rim quality available. Its GLSL is not in any dump seen so far, and a blind rewrite would not match. Next step: the device dump with `kgblurradius` below 10.
- **Mip-based reconstruction.** The blur texture has no mip chain (`SkMipmapMode::kNone`), so `textureLod` cannot reach coarser levels.
- **Quad-shared samples through derivatives.** Derivatives are undefined in the non-uniform branch that samples, and coarse derivatives would raise the variance.

### Shade legibility, review fixes (sfhook v0.10, 2026-10-11)

**White app behind the expanded shade.**
- **Report:** the cards turn white under white (dark-mode) text and nothing can be read. Over any other app they look right.
- **Cause:** shade cards are an untagged Samsung blur of the app below the shade window, under a light veil (`semalpha` over a white tone). Nothing adapts to the backdrop, so a white app gives a white card.
- **Fix: profile 2, shade legibility.** Shade cards carry a third tag band, `[0.05, 0.20]` on r (`[0.55, 0.70]` on the clip uniform). It gets no lens, no extra blur and no B-spline: the plain sample, tone-mapped per pixel against the text colour. The tag's strength channel carries that colour: 0.3 for light text (dark theme), 1.0 for dark text; the shader splits them at 0.65.
  - **Light text:** luma above `cap − 0.12` is compressed exponentially toward `cap` (0.55), keeping hue. White becomes 0.549, mid 0.5 becomes 0.483, and anything below 0.43 is unchanged.
  - **Dark text:** luma below `lift + 0.12` is lifted toward `lift` (0.45) by mixing with white. Black becomes 0.451.
  - The curve is C1 at its knee (`field_check.c`).
- **Knobs:**
  - `debug.oulg.shadetone` (live, default 1; 0 leaves shade cards untagged).
  - `debug.oulg.sf.shadecap` and `.shadelift` (compile time).
- **Untouched:** heads-up cards (profile 0) and the lockscreen (profile 1).

**White strip under every collapsed group (v0.10.4).**
- **Census.** The strip is the group's first child row drawn as a stack under the summary: `scale=0.92x1.0 ty=15 alpha=0.5 ClipTopAmount=201`, so only 15 px show.
- **Why the tag is lost.** Its blur region keeps the full 1340×216 bounds. Scaled, it reaches the compositor 1232.8 px wide with elliptical corners, so the whole-pixel and band checks drop the tag. The region then shows the plain blur of the white app.
- **Not done:** relaxing the shader's whole-pixel check. It would bring back false positives during app-open animations.
- **Fix:** while a row is scaled (stack state), its blur region is off, and the glass still draws the sliver.
  - Samsung path: `SemBlurBridge.setSuspended`.
  - Drawable path: region fade 0.
- The notification-shelf tag (v0.10.1) stays, harmless: the shelf carried no blur in the census.

**Independent review of v0.9: what changed.**
- **Magnification.** The `refract.h` text claimed "magnification ≤ 1". It is the sampling map's singular values that are ≤ 1, so the *texture* is magnified by up to 1/σ_min.
  - `field_check` now prints the bound per strength:
    - profile 0: 1.25× at 0.10, 2.50× at 0.30, 4.99× at 0.40, 9.97× at 0.45, fold from 0.5;
    - profile 1: 1.18× / 1.82× / 2.50× / 3.08× / 4.00×.
  - It checks that profile 1 at its 0.40 default magnifies no more than profile 0 at the user's 0.30 (both 2.5×).
  - Profile 1's cap drops from 0.55 to 0.5, so the worst case is 4× instead of 5.7×.
- **Edge lighting handed over only when confirmed.** v0.9 dropped the module's lit runs and shadow whenever Java chose profile 1. Now the edge shader steps back only when all of these hold:
  - the tag will reach every program: radius above 42 and below 127.5 (the fp16 limit), and on the Samsung path the single-radius shape (a clip path carries no tag);
  - the library has announced its optics: `debug.oulg.sf.optics ≥ 9`. The library sets it when a fragment rewrite compiles; the boot script sets it when the property is empty; with a live swap and an SELinux refusal, set it by hand.

  Otherwise both lightings apply, which is brighter, rather than none.
- **Colour curve.** The lockscreen drawable keeps a curve once set, so it now always receives one: the policy's, or the neutral curve (an identity) instead of nothing.
- **One update per state change.** `setOptics(keyguard, lens)` replaces `setKeyguard` + `setLens`, which could rebuild the blur twice with an intermediate state.
- **Not changed:**
  - **The two discs in one program.** Only one runs per pixel. Register allocation is the larger of the two, not their sum; to be measured on the Adreno with the rest of the GPU cost.
  - **Tag precision above 127.5 px.** Handled by not handing lighting over there (above), not by a new encoding: changing the encoding would change the heads-up tag.

## Shade cards on a pull up: ghost and flashes (2026-10-16)

Device report (screen recording, unlocked shade over the launcher, `shadeblur=1`, `sfrefract=1`): on a slow pull up that closes the shade, the cards flashed light and dark from frame to frame. Further up, only a blurred, veiled card-shaped region remained after the content had faded (a ghost). A fast pull did not show it.

- **Ghost.** This is the same mechanism as the lockscreen ghost. The stack fades through ancestor view alpha, which fades the drawn glass but not the Samsung blur region installed with `semSetBlurInfo`. In window mode that call installs a `BackgroundBlurDrawable` as the view's background (read from `framework.jar`, see *Nested clear blocked*). Before each frame its alpha now takes the accumulated alpha of the view's ancestors, as the drawable path already does on the lockscreen. If the firmware leaves no blur drawable on the view, the region is dropped below half alpha instead. `SEM_BLUR_FADE` logs which of the two applies.
- **Flashes.** The recording shows the cards narrowing while they fade: an ancestor scales the stack. A scaled region reaches the compositor with a fractional size and radius. The shade tag (profile 2, `[0.05, 0.20]` on the radius fraction, whole-pixel sides) therefore matched on some frames and not on others, and the legibility tone map switched on and off. While any ancestor scales the card, a non-lens card now carries no tag. The blur is rebuilt once at each edge, not per frame, and `SEM_BLUR_SCALED` logs the switch. Lens cards are unchanged.

Not verified on device yet. The flash diagnosis comes from the recording and the tag rules, not from a log.

**Device trace after that fix (`ROW_FRAME`, 2026-10-16).** The upper cards were fixed. The last row and the rows above the shelf still flashed, worse the brighter the app behind and not at all over black. The trace shows the cause:
- While the shade closes, One UI scales every top-level row itself (`s=0.99x0.99` and below, parent `NotificationStackScrollLayout`), not only an ancestor.
- The stacked-group rule suspended the blur region of any scaled row. It was written for a group's first child drawn as a sliver (`0.92x1.0`). Applied to these rows, it switched their region on and off 21 to 23 times per pull, each time the scale crossed 0.995. With no region, the card showed the shade's dark backdrop.
- **Fix:** the rule now applies only to a row whose parent is a `NotificationChildrenContainer`.
- **Untagged while scaled:** the card dropped from the tone-mapped look to the plain blur, which is white over a white app. While a shade card is untagged by scale, its veil stands in for the tone map. It is black under light text, white under dark text, at `debug.oulg.shadescaleveil` alpha (default 115, about 45 %, live; 0 keeps the normal tint). Over white this lands near the tone map's 0.55 cap. Mid-tones come out darker than the tone map would make them.

**Second device pass (screenshots frame by frame, 2026-10-16).**
- Once the scaled top-level rows kept their region, every card left the ghost. The drawable alpha set on the Samsung path changes, as the trace shows, but the region on screen does not follow it.
- The cards had looked right earlier only because their region was being removed.
- **Conclusion (from that, not measured in the compositor):** the `semSetBlurInfo` region does not take the alpha of the `BackgroundBlurDrawable` it installs.
- **Fix:** expanded-shade cards (unlocked, not heads-up) now take their blur from the drawable path the lockscreen uses (`BackgroundBlurBridge` lens mode, drawn inside the glass). Its region alpha is proven to follow the card on the lockscreen pull up.
- **Tags and veil:** the drawable path untags and veils scaled cards like the Samsung path.
- **Curve:** a shade card that never carried a curve sends none, as before. Heads-up cards keep the Samsung path.
- **Switch:** `debug.oulg.shadeblurpath` (live, default 1); 0 restores the Samsung path.

**Third device pass (screen recording, 2026-10-16).** The recording catches the flash at rest, with the cards not moving:
- Over a white app the top cards sat at luma about 237, the plain blur. Every 4 to 6 frames they dropped to about 211 for a single frame, then went back.
- Inferred cause: the profile-2 tag recognised on some frames only. Not yet confirmed by a trace.
- **New mode:** `debug.oulg.shadetone=2` leaves shade cards untagged and gives them the stand-in veil permanently, so there is nothing for the compositor to recognise.
- **Trace:** `ROW_FRAME` now also logs the blur path, the tag profile sent, the scaled flag and the drawable's alpha.

**Fourth device pass (trace and recording, `shadetone=2`, 2026-10-16).**
- The flicker stopped once the tag was gone, which confirms that the tag caused it.
- The cards turned too dark, and the white pill was still left at the end of a pull.
- The trace shows the drawable path active (`path=drawable`) and its alpha following the card (0.85 → 217, 0.27 → 70).
- So on this firmware a partly transparent region in the unlocked shade turns whiter, not fainter. This is inferred from the recording, not measured in the compositor.
- **Fix:** in the unlocked shade the region is now whole or gone. It is removed once the accumulated alpha drops below `debug.oulg.shadefadecut` (default 0.5, live; 0 restores the alpha fade). Both paths do this; the Samsung path suspends its region.

**Fifth device pass (2026-10-16).**
- `shadetone=0` was steady but too bright over a white app: without the tag the card does not respond to its backdrop.
- With the Samsung path, the tag and the fade cut together, the cards were steady at rest and still flashed during the pull.
- **Fix:** a card now stays untagged from the frame it scales or fades until it has been still for 300 ms (`SETTLE_MS`). Before this, the tag followed the scale frame by frame, and with the finger nearly still the scale wobbled around 1.

**Sixth device pass and the fix that follows (2026-10-16).**
- **What the device showed:** with the 300 ms hold, the cards opened gray for about a second, then turned tone-mapped. In motion each card flashed at its own moment, apparently as it crossed brighter parts of the backdrop.
- **Conclusion:** a tone recomputed by the compositor every frame cannot stay steady while cards move on this firmware.
- **`debug.oulg.shadetone=3`, now the default:**
  - Shade cards never carry the tag.
  - When the shade opens, one capture of what is behind the shade window (`CaptureHub`) gives each card the mean luminance under it (`ShadeTone`).
  - From that luminance the card gets a veil that it keeps until the shade closes:
    - **light text:** black, bringing the mean down to `debug.oulg.shadecap` (0.55);
    - **dark text:** white, lifting it to `debug.oulg.shadelift` (0.45).
  - A backdrop already on the legible side of the knee keeps the normal tint.
  - The tone is per card, not per pixel, and it does not follow a backdrop that changes while the shade is open.
- **The shelf** (SystemUI's own blur) is not covered: it stays untagged.
- **Device result:** steady, and the user found it better than before, at rest too. The capture runs once per opening (`SHADE_TONE luma=0.11 veil=0` over a dark app, `luma=0.95 veil=50000000` over a white one).
- **Remaining defect, fixed:** a white flash where each card appeared, the plain blur shown for the frame or two the capture takes. A shade card's region now stays off until its veil is known, or for at most 250 ms (`TONE_TIMEOUT_MS`).

**Lockscreen body: the cause of "blotchy" (2026-10-16).**
- **Trace:** on the lockscreen the native background drawable has alpha 204 (`native=204`). `GlassDrawable` passes the native alpha to its backdrop, and `BackgroundBlurBridge` applied it to the region (`drawableA=204`).
- **Effect:** a region at 80 % mixes the sharp wallpaper into the blur. This is the blotchy, under-filtered body that the drawable path showed on the lockscreen since it was introduced. The Samsung path ignores the native alpha.
- **Fix:** the region now takes only the fade; the glass still honours the native alpha.

### Lockscreen material v0.11: rim that reaches grazing, adaptive transmission (2026-10-16)

Device report: grey rim, a body that dark backdrop objects dominate, no visible contour or thickness. Measured `kgoptics` 0 vs 1 differed by 1-6 RGB. **Cause in the code:** the rim tilt peaked at 1.2 rad, where Schlick's `(1 − cos θ)^5` is 0.105; the reflection could never exceed 10 %.

Profile 1 only (profile 0 stays bit-identical, `render_check` max 0; profile 2 untouched):
- **Rim:** tilt `G(t)·kgtilt` (1.42 rad). The environment is cool and brighter on the sky-facing rim. A 1.6 px Gaussian contour from the outline is the grazing edge of the same surface (`kgline` 0.55). The specular band now sits inside the contour, mid-bevel (`smoothstep(0.30, 0.62, t)·(1 − smoothstep(0.80, 0.95, t))`), light-facing lobe `facing³` plus 0.45 of the opposite one (`kgspec` 0.30).
- **Body:** after vibrancy and the highlight knee, luma moves `kgflat·body` (0.50) of the way to `kglevel` (0.40). Darkening is a gain; lifting adds cool scattered light, so near-black does not turn into amplified noise. Then a luma-neutral cool tint (`kgcool` 0.5). Disc 80 px with 48 taps.
- **Host preview:** `sfhook/tools/kg_preview.mjs` renders the real FillRRect rewrite over a photographic crop of the user's lockscreen and composites region colour, glass fill and hairline as on the device. Over the dark jacket crop: body luma SD 0.151 → 0.072, p95 0.64 → 0.54, top contour +0.02 → +0.18 over the body, worst-case white-text contrast 2.5 → 3.5. Not device-validated; the real compositor input and Samsung filter are unknown.

### v0.20: one lockscreen contour; pop-up lens 0.45; optional bloom control (2026-10-16)

**Double outline on the device.**
- At the bottom edge of a lockscreen card the profile read 0.43 about 5 px inside, then 0.60 at the outline. The inner peak is the module's hairline (half gain, centred ~6 px inside).
- **Fix:** compositor-lit cards skip the edge shader. The compositor contour widens (σ 1.13 → 1.6 px, gain 0.62).

**Pop-up lens.**
- `sflens` now defaults to 0.45 instead of 0.7. The quadratic heads-up field folds above 0.5.
- At 0.45 it no longer folds, but it still magnifies about 10× in the outermost pixels.

**Bloom control.**
- New `OULG_FS_DISC_BLOOM_FMT`, emitted for profile 0 when `hubloom > 0`: tap weight × `exp(−6·bloom·max(l_tap − l_centre − 0.10, 0))`.
- **Off by default.** At 1.5 it cuts a vivid object's spread into the card by ~40 %, but it doubles the backdrop structure left in the body (render_check 2.61 → 5.2).

**Unchanged:** profile 0 with `hubloom = 0` is bit-identical.

### Lockscreen v0.19: a narrower rim glow (2026-10-16)

On the device, the v0.18 lockscreen rim glow fell from 0.59 at the outline to 0.17 about 28 px in. That reads as a lit rim, not a surface reflection. Profile 1 changes:
- Fresnel and the specular band use `tg = 1 − (1 − t)/kgglow`, with `kgglow` 0.7, so they are 30 % narrower.
- `kgrim` 0.72 and `kgspec` 0.19 make them weaker.
- The contour line is unchanged.

**Host lift results:**
- 6–15 px: 33–43 % lower.
- 15–30 px: about half.
- 30 px and beyond: unchanged.

`kgglow 1`, `kgrim 1` and `kgspec 0.30` reproduce v0.18.

**Pop-up, not changed.** On the host, the two white streaks and the green stain hardly change with lens strength (0.7 to 0.4). They are backdrop content spread by the 48 px body blur near the rim, not a blurred specular.

### Lockscreen v0.18: core level with the inner rim (2026-10-16)

A core lighter than the inner rim reads as raised. Measured lift over the wallpaper, inner rim (30–45 px) vs core (> 68 px):

| | inner rim | core |
|---|---|---|
| v0.17, skin | +0.103 | +0.132 |
| v0.17, dark | +0.025 | +0.072 |
| v0.18, skin | +0.079 | +0.090 |
| v0.18, dark | +0.028 | +0.047 |
| v0.18, light | +0.024 | +0.014 |

**Changes:**
- The flattening cap drops to 0.22.
- A 4 % gain applies to the deep core only: `1 − kgcoredim·smoothstep(0.5, 1, body)`.

The cap alone could not close the gap on dark backdrops, where the wider core blur mixes in brighter surroundings. Because the gain is multiplicative, the core still follows the wallpaper.

- **Rim:** 0–30 px is unchanged. The 30–45 px band darkens slightly over dark backdrops.
- **Rollback:** `kgcorecap = 80`, `kgflatcap = 1` and `kgcoredim = 0` together are bit-identical to v0.11.
- **Profile 0:** bit-identical.

### Lockscreen v0.17: a dynamic core (2026-10-16)

The v0.11 flattening (`kgflat` 0.5 toward 0.40) is weighted by the body ramp. Measured on the host against the wallpaper under the card:
- the rim sits +0.14 above it and follows it at 81 %;
- the core sits +0.20 above it and follows it at only 40–46 %.

That is the light, fixed core the user reported. The weight is now `min(body, kgflatcap)`, with a cap of 0.5:
- **Rim:** weight below 0.5 up to ~38 px, so effectively unchanged (mean 0.04–0.28/255).
- **Core:** +0.13 and 68 %, level with the rim.
- **Rollback:** `kgflatcap = 1` with `kgcorecap ≥ 80` is bit-identical to v0.11. Profile 0 is bit-identical.

### Lockscreen v0.16: the v0.11 material with a capped core blur (2026-10-16)

**Decision.** The user approved the look of lib v0.11 with the v0.12 app; v0.13–v0.15 are withdrawn. The lib v0.12 dome never ran on the device.

**Restored from `aa6fd27`:**
- `sfhook/refract.h`, `oulg_sf.c`, `field_check.c` and `refract_check.c`;
- `GlassDrawable` and `BackgroundBlurBridge`.

The app is now identical to `431cf07`: the first-frame fix (profile 1 on bar state 1), the `OPTICS` logs and the overflow pill.

**Only visual change.** Profile 1's body radius is `min(80·bw, kgcorecap)`, where `bw` is the v0.11 ramp and the cap defaults to 62 px. The material weights its colour terms by `bw` instead of radius/core.
- Every pixel whose v0.11 radius is below the cap samples identically.
- Host masks against v0.11: the rim (0–45 px) has a maximum difference of 0 on three backdrops. `kgcorecap ≥ 80` is bit-identical overall.
- `render_check`: core structure 0.87 → 1.36, grain 0.51 → 0.40, same cost. Profile 0 is bit-identical.

### Lockscreen v0.15: neutral body, surface sheen (2026-10-16)

**v0.14 device screenshot.** The rim is single (0.52 contour, a smooth 0.29–0.32 core). The face area of the body reads amber: R − B is 0.10 at the median and 0.27 at p95.

**Cause.** The 80 px disc spreads skin and leather into wide patches. Saturation 1.12 and v0.14's transmission gain (up to 1.8×) raised their chroma; on the host, body chroma was up 58 % from v0.13.

**v0.15, profile 1 only:**
- saturation 1.0;
- gain capped at 1.35×;
- chroma cut by `kgwarm` (0.6) where red exceeds blue, while cool hues keep theirs;
- cool tint 0.7;
- a surface sheen `kgsheen` (5 % at the top, 0.5 % at the bottom, fading over bright backdrops): the environment reflected by the glass face, which a pure blur lacks.

**Host,** over a crop of the device's own wallpaper under the card: R − B p99 goes 0.12 → 0.02 and chroma p95 0.19 → 0.16. Profile 0 is bit-identical.

### Lockscreen v0.14: one rim, no app edge, no veil (2026-10-16)

The v0.13 device screenshot, measured down the card: contour 0.59, trough 0.33, **a second ring of 0.49 about 19 px in**, then a 0.32–0.34 core; at the bottom an inner ring of 0.42 and a contour of 0.41. That reads as two drops.

The ring is the compositor's specular crescent (t = 0.62 ≈ 20 px). Stacked with it at the edge were:
- the compositor's Fresnel and contour;
- the module's hairline (half gain, three-line colour fringe centred ~6 px inside, a second contour);
- the refracted band between them.

The core was milky from the region's 8 % white colour and from additive shadow lift.

**Fix:**
- **Compositor, profile 1 only:** grazing reflectance and contour form one weight toward the environment, plus a glint decaying 5 px from the outline on the lit side. No inner ring.
- **Transmission:** a gain (0.6×–1.8×) toward 0.48, with scattered light only for what a gain cannot reach.
- **Module:** on compositor-lit cards (profile 1 tagged, `sf.optics ≥ 9`), `GlassDrawable` skips the edge shader and `BackgroundBlurBridge` sends no region colour.
- Heads-up keeps both, and profile 0 is bit-identical. The shade is untouched.

### Lockscreen v0.13: edge lens on a clean core (2026-10-16)

The device screenshot of v0.12 has this luma profile across the card: contour 0.51, a trough of 0.17 about 28 px in, a flat 0.29 plateau, then 0.24 and contour 0.40. The eye reads that shading as relief, i.e. a padded slab. It has three causes:
- **Late core blur.** The core blur reached full radius only at 0.85·r, so it was 14 px at 28 px depth and the dark backdrop showed almost raw.
- **Depth-weighted transmission.** Flattening was weighted by depth, and highlights were pulled as hard as shadows. The core came out uniform and darkened (smoked) next to a raw rim.
- **Whole-card lens.** The v0.12 dome made the whole card a magnifier, which reads as inflated.

**v0.13, profile 1 only:**
- Edge lens over `b = r/2`, shift `A·b·t³`, `A = min(0.5k, 0.25)`. Jacobian `1 − 3At²`: monotone, 2.5× at the outline at k = 0.40, C2 at the band end.
- Body blur `80·smoothstep(0.02, 0.38, depth/r)`.
- Transmission uniform over the card: shadows move 0.55 of the way to 0.44 by added scattered light; highlights move half that, as a gain.
- Saturation 1.12.
- Fresnel tilt `kgtilt·t²`; specular a narrow Gaussian at t = 0.62.
- Profile 0 is bit-identical (`render_check` max 0); profile 2 is untouched.

### Lockscreen v0.12: a dome instead of a bevelled slab; born in the final profile (2026-10-16)

Device report on v0.11: the card read as a speed bump (abrupt rise, flat top, abrupt fall), and a notification arrived in one look and switched to the right one about 2 s later.

**Speed bump, from the field.** Profile 1 shifted the sample by `A·bevel·G(t)`, `G = t²(3 − 2t)`, over a band of `0.42·r` (45 px on a 108 px half height). Its radial Jacobian `1 − 6At(1 − t)` is smallest mid-band, so magnification went 1.05× at the outline, **2.46× at 20–25 px**, back to 1.00× at 45 px, and then a flat core. That gives a ring of peak magnification (the shoulder) and a plateau, with a luminance step on top: the transmission flattening ran from weight 0 at the rim to 1 in the core.

**Dome.** Profile 1 now spans the whole corner radius:
- `t = 1 − depth/r` (on a pill the lens reaches the centre line), shift `A·r·t²`, `A = min(0.6k, 0.35)`.
- Radial Jacobian `1 − 2At`: monotone, strongest at the outline (1.91× at k = 0.40), 1 at the centre, C1 where it ends, never folds (`field_check`; 3.3× at the cap).
- The body blur is `core·smoothstep(0.04, ramp, depth/r)` with ramp 0.85: refraction and filtering share one coordinate.
- Transmission flattening weight `0.35 + 0.65·body`.
- Fresnel tilt `kgtilt·t` (the slope of a `t²` height).
- Specular: a Gaussian crescent at t = 0.74.
- Profile 0 is bit-identical (`render_check` max 0) and profile 2 is untouched.

**Late arrival.** `Eligibility.keyguardOptics` denied profile 1 to any row in its heads-up state. A notification that arrives on the lockscreen enters the stack as a heads-up, so it carried the heads-up material until that state ended. On bar state 1 every row now takes profile 1. On state 2 and over apps a heads-up keeps profile 0.

This is inferred from the code, not traced. The new `OPTICS` / `OPTICS_FINAL` timeline logs every change of path, tag, heads-up, pinned, region fade, ancestor scale and fallback for lockscreen cards, with the time since the card was created and whether the first frame was already final.

### Overflow pill on the lockscreen (2026-10-16)

The pill with the icons of notifications that do not fit is assumed to be the notification shelf's `NotificationBackgroundView`. The draw hook now accepts a background under a `NotificationShelf` as well as under a row. On the keyguard state with the drawable path it gets light glass: an untagged blur of `debug.oulg.pillblur` px (40), whole-pixel corners so no tag band is hit by accident, the module's veil and edge, no lens and no compositor material; elsewhere it stays native. `PILL_GLASS`, `BACKGROUND_HOSTLESS` and the trace-only `OVERFLOW` census confirm or refute the assumption on the device.

## Expanded notification header gap (2026-10-11)

A layout-only refinement, independent of the glass: in expanded notifications the title sat too close to the header row (`Chrome · 3 hours left · 15:52` over the file name). `HeaderGapHooks` adds `debug.oulg.headergap` dp (default 4, 0 turns it off, at most 8) between the two. The policy, which the host tests cover, lives in `HeaderGap`.

- **Where.** The hook runs before `NotificationContentView.onMeasure`, on `mExpandedChild` only. Within that child it finds the header (`android:id/notification_header`) and the title (`android:id/title`). The view that moves is the highest ancestor of the title that does not contain the header. On the AOSP big templates that is `notification_action_list_margin_target`: a FrameLayout child that the header overlays, offset by its top margin. Its top margin grows by the gap. The title, text, progress bar and actions move down together, and the card measures that much taller. A `right_icon` in the same FrameLayout moves with it only when it starts at or below the title. The title's top comes from the margins and paddings on the way down. An icon aligned with the header row stays where it is. The target must be a top-gravity FrameLayout child, or a vertical LinearLayout child that comes after the header; any other parent is logged as `HEADER_GAP_UNSUPPORTED` and left alone. **These names are AOSP's. On S938BXXUCZZIC they are unverified until the device log shows `HEADER_GAP_PASS outcome=applied`.**
- **Not touched.** The contracted and heads-up children are other view instances, so the hook never sees them. An expanded child that is also one of them is skipped. Rows in the heads-up state (`isHeadsUpState`, pinned, or animating away) keep the native spacing, even when the pop-up is expanded. So do rows whose state cannot be read. Templates without both views are skipped too: conversations, fully custom views, and headerless layouts.
- **Lifecycle.** A per-view ledger keeps the native margin and the value the module wrote. Repeated measures write nothing. If a RemoteViews reapply (a progress update, say) writes the native margin back, the gap is added once more, in the same measure. Resolved views are cached weakly per expanded-child instance, and a reinflation brings a new instance. On every pass the policy picks the target again with a walk up from the header and the title, with no tree search. If an update moved, replaced or dropped a cached view, the margins written to the old views are restored and the instance is resolved again. A layout that had no header or title when it was first resolved stays cached as unsupported, which avoids a `findViewById` per measure; a RemoteViews reapply keeps the layout's views, so that state does not change for the same instance. The write uses `requestLayout()`. It stops at the first ancestor already flagged, which is the case on inflation, reapply and margin resets, so no traversal is added there. Only an unrequested measure (a parent spec change, or a measure outside a traversal) schedules one more traversal, which measures the same values. The draw path does no work.
- **Room.** If the expanded child then measures `MEASURED_STATE_TOO_SMALL`, its content was cut by the cap, and the gap is taken back. A FrameLayout or LinearLayout root reports that state itself, so reaching the cap exactly is not counted. Only other roots fall back to `measured ≥ mNotificationMaxHeight`, and only when `mExpandedSmartReplyView` is null: AOSP adds the smart reply limit to the cap whenever that view exists, visible or not. The child is measured again in the same pass, and that instance keeps the native spacing. A card that has no room for the gap is never clipped by it.
- **Glass.** The background view follows the row height, which comes from this measure, so the glass, its blur region and the compositor tag cover the taller card as they cover any other height. No optical code changed.
- **First device run (S938BXXUCZZIC, 2026-10-11).**
  - **Names confirmed.** `NotificationHeaderView#notification_header`, `android:id/title`, and `RemeasuringLinearLayout#notification_action_list_margin_target` (margin 0) are direct children of `FrameLayout#status_bar_latest_event_content`. `notification_main_column` sits inside at margin 58. Other templates put `notification_main_column` directly under the root, and the hook applies there too. Media and headerless layouts resolve to `structure` and are left alone.
  - **The gap moves the title by exactly 12 px** at density 3.0.
  - **Bug 1: the gap switched itself off.** The title had to be VISIBLE. SystemUI hides the title of the expanded child that is not on screen with INVISIBLE, and going back to VISIBLE requests no layout. So once the notification collapsed, the next measure dropped the gap and nothing put it back. The title and the header now only need to take layout space (not GONE).
  - **Bug 2: the measured gap was negative.** The header view is 180 px tall (it spans the icon), so its bottom sits below the title. `HEADER_GAP_MEASURE` now measures from the bottom of `notification_top_line`.
- **Diagnostics.** With `debug.oulg.trace=1`, each newly resolved expanded child logs `HEADER_GAP_TREE`, one line per view with class, id, visibility, top, height, top margin and top padding. Use it when the outcome is `structure`, `noHeader` or `noTitle`. Each outcome change also logs `HEADER_GAP_MEASURE` after the next layout: the bottom of the header and the top of the title in window coordinates, `gapPx` between them, and the title's line count.

## LSPosed-only mode (2026-10-24)

**Context.** The user's device now runs without the KernelSU module and without any metamodule: integrity and Wallet are kept, and `/system` stays untouched. With the APK alone the cards flickered, changed colour at random, and the content behind a pop-up trailed motion (the clock on the launcher lagged a home-screen swipe, then caught up).

**What the APK did without the module** (`sfrefract` stays 0, read from the code):

| Surface | Path | Defect it can produce |
|---|---|---|
| Pop-up over an app or the launcher | `HybridBackdrop`: Samsung blur on the view for the body, a 60 Hz capture refracted in the rim band | The rim shows a capture one or more frames old: the trailing clock. Secure layers back capture off for 3 s and the card drops to the plain blur, a colour jump. The Samsung region is applied after the traversal that requested it, so the first frame has no blur |
| Lockscreen | `WallpaperBackdrop`, or a 5 Hz capture over a live wallpaper (the user's case per the 2026-10-08 log) | Lag and source switches; `kgblurpath` was gated on `sfrefract`, so the drawable path that fixed the lockscreen never applied |
| Shade, unlocked | Drawable path (`shadeblurpath`, not gated) | None new: the approved configuration |
| Heads-up row in the open shade | Samsung path (heads-up excluded from the drawable path) | When the heads-up state ends, the card swaps to the drawable path: release, new bridge, visible material change |

These causes are inferred from the code and from earlier device findings on the same paths. They have not been traced on the device in this configuration.

**Change.** `Tuning.live` is true when `sfrefract` is 0, `debug.oulg.liveblur` is 1 (default) and `backdrop` is `auto`. Then:
- `drawablePath()` is true on every surface. Every card, the pop-up included, draws its `BackgroundBlurDrawable` with the glass, so the region takes the card's bounds and fade in the frame that draws it.
- `sampledRow()` is false. No hybrid rim, no wallpaper refraction, no per-frame capture. `ShadeTone` still takes its one capture per shade opening.
- All cards share one radius, `debug.oulg.liveradius` (-1 = `semradius`, 180). A pop-up that becomes a shade card, or a lockscreen card under the pulled-down shade, does not jump. `kgblurradius`, when set, still applies to lockscreen cards (`Tuning.livePx`).
- The idle-lockscreen panel-blur ramps (radius ≤ 4) are dropped as with `sfrefract`, the configuration confirmed on the device.
- Nothing is tagged (`tagProfile` returns -1 without `sfrefract`), and the edge shader keeps its own lighting (`sf.optics` is unset).

The pre-change `auto` stays available as `debug.oulg.backdrop=hybrid`. With the KernelSU module (`sfrefract=1`) nothing changes.

**Fallback.** The drawable path had never run on the pop-up. If `BackgroundBlurBridge.create` throws, the card keeps the Samsung blur for its lifetime (never per frame) and `BLUR_DRAWABLE_FALLBACK` is logged. A missing factory or setter (`ReflectiveOperationException`) switches the whole process to the Samsung path. The lockscreen pill has no Samsung variant and stays native. Before this, the exception reached the draw hook, which marked the card failed and left it native.

**Shade veil ramp.** `ShadeTone` lands a few frames into an opening, while the card shows the previous opening's veil. The new veil now ramps over 180 ms (`VeilRamp.ends`) when both veils have the same colour or one of them is none; a dark-to-light change still applies at once. The measurement itself is unchanged: when capture is unavailable, `SHADE_TONE capture=unavailable` is logged and the card keeps the previous veil.

**Mode selection is a property, not a probe.** `live` follows `sfrefract`, which the KernelSU module sets at boot. A leftover `debug.oulg.sfrefract=1` without the library turns `live` off. `sf.optics` alone cannot suppress the edge shader in this mode: `setCompositorLit` also needs a profile-1 tag, which `tagProfile` never sends without `sfrefract`.

**Not covered:** live refraction. It needs the content of another window in the frame the card is drawn, which only the compositor has. `docs/sf-sem-overlay.md` is a research note on putting the shader rewrite into SurfaceFlinger without OverlayFS; it is not part of this mode.

**Validation:** compiled with `javac` against `android.jar` (SDK 36) and the Xposed API; 62 JUnit tests pass, including `LiveModeTest` and `VeilRampTest`. Gradle lint and the APK build did not run in this session (Maven Central refused the requests); the release workflow runs both. Not tested on the device.

### LSPosed-only material, second pass (2026-10-25)

**Device report on the first live build** (video and log, 24/10): stable, Wallet PASS in VD Google at 19:51:57, but every card read as a uniformly frosted plate, and in the shade the cards merged with the blurred background. Log: `source=compositor optics=edge_shader`, every `debug.oulg.*` empty, `SHADE_TONE luma=0.01–0.11 veil=0` over a dark wallpaper.

**Causes read from the code:**
- the default radius was 180 px on every surface, so the backdrop kept no structure behind the card;
- in the dark theme the region colour (8 % white) and fill (4 % black) left a shade card at the tone of the shade's own blur;
- the edge was the module path's model (hairline round the whole contour), tuned to sit beside the compositor's lens.

**Changes, LSPosed-only mode only** (`Tuning.live` and the drawable path; the module path is unchanged):
- **Radius per surface:** pop-ups and lockscreen cards 80 px (`liveradius`), shade cards 120 px (`liveshaderadius`). `BackgroundBlurBridge.applyRadius` ramps a built card to a new radius over 200 ms, so a pop-up that opens the shade does not jump in blur.
- **Dark theme:** region colour 11 % white for pop-ups and lockscreen cards, 14 % for shade cards, no dark fill. The light theme is unchanged.
- **Edge:** `LiquidGlassShader.liveEdge()`, selected by the `live` uniform. Its terms:
  - an outline whose gain goes from 0.06 opposite the light to 0.34 facing it;
  - a Fresnel lift over 60 % of the bevel;
  - a glint at the corner facing the light (`facing^20`) and a fainter internal reflection at the opposite corner;
  - a soft absorption ring (alpha) on the far side;
  - a top sheen (`livesheen`, 2 %).

  `liveoptics` scales all of it. These terms shade the card; they do not displace backdrop pixels.
- **Diagnostics:**
  - `LIVE_STATE`: per card, logged when the surface, radius, tint, fill, veil or capability changes. It records what was requested from the compositor, not what reached the screen.
  - `BLUR_CAPABILITY_CHANGED` is logged once per change instead of once per card listener.

**Host preview:** `docs/img/live-edge-preview.jpg`. From top: light theme old edge, new edge; dark theme old, new. The shader source is converted to GLSL and rendered in WebGL over a synthetic blurred backdrop. Limits of this preview:
- the same blur is shown inside and outside the card, so the radius change does not show;
- the AGSL compiler on the device was not run.

**Validation:** `javac` and 62 JUnit tests pass. Gradle lint and the APK build were blocked again by Maven Central. Not tested on the device.

### Gated lens band on LSPosed-only pop-ups (2026-10-25)

**Device test:** `backdrop=hybrid` (captured rim over the Samsung body) gave the refracted rim the user wants, but the launcher behind the pop-up trailed a home-screen swipe again. The rim draws a capture that lands one or more frames late, while the body blur is live. A faster capture rate shortens the interval between captures, not the delay of each one.

**Change:** LSPosed-only pop-ups keep the live drawable body and the light-only edge, and add the hybrid's refracted rim band (`GlassDrawable.drawLens`, `rimOnly`, no edge lighting of its own) at a weight that `BackdropMotion.block` gates.

**Signal:**
- Touches from a hook on `InputEventReceiver.dispatchInputEvent` (observation only).
- Only receivers that are not a SystemUI window's own (`ViewRootImpl$WindowInputEventReceiver`) count at all.
- The signal is proven by a touch down from such a receiver in the middle of the screen with the shade closed, which only a display-wide monitor (gesture navigation) delivers.
- The proof lasts as long as that receiver: a hook on `InputEventReceiver.dispose` revokes it.
- No proof means no band and no captures. That covers button navigation, and the case where the disposal hook is missing.

**Blocked band:**
- A touch outside the pop-up blocks it, until `lenssettle` ms (600) after the finger lifts.
- A blocked band is not drawn at all from the first blocked frame, and a touch down redraws every card that has one. There is no fade-out: fading would keep the stale capture on screen for the frames of the fade.
- It returns over 180 ms, only from a capture requested after the backdrop settled and at most `lensmaxage` ms (50) old at draw time.
- Touches on the pop-up itself do not block it.

**No captures while blocked:** the band's capture client asks `CaptureHub` for 0 Hz while `BackdropMotion.wantsFrames` is false. The hub then requests nothing and polls every 33 ms, so capture resumes promptly once the backdrop settles.

**Failure isolation:** `drawLens` runs in its own try. A failure logs `LENS_BAND_FAILED`, restores the canvas, drops the band and turns bands off until SystemUI restarts. The live body and the edge keep drawing.

**Not covered:** motion without touch (video, an app's own animation). The age limit is the only guard there.

**Telemetry:**
- `LENS_STATS`: draws that put captured pixels on screen, blocked draws by reason, and `requestToDraw` P50/P95/max.
- `requestToDraw` runs from the capture request to the draw that records it; the pixels reach the display one or two vsyncs later.
- `TOUCH_SIGNAL` logs the hooks, the proof (with the receiver class) and its revocation. `LENS_BAND` logs each attach.

**Validation:** `javac` and 73 JUnit tests pass, including `BackdropMotionTest`. It covers:
- the gate;
- window receivers, button navigation, proof and revocation by disposal;
- one full gesture, with capture suspension and the settle;
- the immediate cut.

The draw path, the hub and the hooks need an Android runtime and are not covered by these tests. Gradle is blocked here. Not tested on the device.

### DZIF round 36: first-frame tone, pop-up contrast, capped veil, glint, directional gate (2026-10-25)

Source: the device handoff of 25/10 (props `lenssettle=100`, `lensmaxage=50`, `rimhz=80`; log `oulg35_on_log.txt`; a pop-up over a white page; a video of the shade opened over the beach wallpaper and then over a white page). Five changes, each reversible by a prop.

**Shade tone from the first frame (P0-A).**
- Cause: each card measured its veil in `onPreDraw` only once `v.isShown()`, and `ShadeTone.onFrame` checked `isShown()` again. The previous opening's veil (`measuredVeil` persists) stayed on screen until then: 100 ms and 491 ms after `SHADE expanded=true` in the log. The video shows the beach-time veil on the first frames over the white page, then a jump.
- Now the first frame of an opening (`setExpandedHeightInternal`, `expandedHeight > 0.5`) starts a generation (`shadeOpens`). `ShadeTone.prefetch` asks `CaptureHub` for one capture of the whole backdrop behind the shade window at once. The panel view anchors it; it need not be shown yet (`Client.evenHidden`). A frame requested before the prefetch registered (a pop-up band's crop) is skipped.
- A worker reduces the frame to 32 horizontal bands, mean and 90th-percentile luma (`ToneGrid`). Each card reads the bands under its current screen rows, weighted by overlap, every pre-draw. Sliding over a band boundary changes the luma continuously; steps below 4/255 alpha are ignored, steps up to 24/255 apply directly, larger ones and source changes go through `applyMeasuredVeil` (ramp on a visible card, at once on a hidden one or one whose accumulated alpha is below 0.5).
- A result for an older generation or one that lands after the shade closed is dropped (`SHADE_PREFETCH state=cancel`).
- Before it lands: an estimate from the status-bar appearance the app set (hook on `LightBarController[Impl].onStatusBarAppearanceChanged`, `APPEARANCE_LIGHT_STATUS_BARS` of the top region, recorded only with the shade closed over an app): luma 0.92 for dark icons, 0.08 otherwise. Without the hook or a value, the card keeps the veil it had, as before.
- Limit: the capture still lands a few frames after the first frame of the gesture; the estimate covers those frames and can be wrong (a light header over a dark page).

**Pop-up contrast (P0-B).** LSPosed-only pop-ups over an app (`popupSurface()`) take a veil from the same functions: the estimate on the first frame (also applied when the drawable bridge is built, before its first draw), then `ShadeTone.measure` under the card every 1000 ms while shown. The veil has its own slot in `BackgroundBlurBridge` (`setPopupVeil`), so a shade veil never reaches a lens card and a pop-up veil never reaches a shade or lockscreen card. `debug.oulg.popuptone=0` turns it off.

**Capped veil and text halo (P1-C).**
- Cause: with `shadecap=0.55`, a white app (luma 0.95) asked for 0.42 black, which replaced the region tint: lead grey, darker than the shade around the cards.
- The veil alpha is capped at `debug.oulg.veilmax` (0.35; 0.75 restores the earlier cap). The shortfall (`need - cap`, when above 0.02) becomes a halo alpha of 0.30 + 1.6 x shortfall, at most 0.70 (`VeilMath`).
- `TextHalo` gives that halo as a 2.5 dp shadow behind text of the theme's text tone only (light greys in the dark theme, dark greys otherwise), never accent text or text that already has a shadow, and not into a group's child rows. It changes the inflated views, not the RemoteViews; removal restores each view's own shadow. Views are walked again every 500 ms while the halo is on (re-inflation). `debug.oulg.texthalo=0` turns it off. The tone (veil and halo) is applied only while `Eligibility.textTone` holds: module enabled, material held and not failed, card eligible this frame (checked before any text change). `State.release` removes the halo on every path to the native background (hidden, ineligible, failed draw or compose, detach), in the same frame; a removal whose walk throws is retried.
- Trade-off: below the earlier cap, legibility over a white app depends on the halo. Its look is a device judgement.

**Pop-up glint (P1-D).** `liveEdge()` multiplies `glint` (the arc ~0.22 bevel inside the lit corner, `pow(facing, 20)`) by a new `glintGain` uniform: `debug.oulg.liveglint` (0) on pop-ups, 1 elsewhere. Contour, Fresnel, inner reflection and the lens band are unchanged. Whether the glint alone made the "second arc" is unproven; colour at the corner over a colourful page can also come from the band's dispersion.

**Directional lens gate (P1-E).** `debug.oulg.lensgate=directional` (default `conservative`, unchanged). `BackdropMotion` classifies each gesture once the pointer moves 16 px from the down point (below the launcher's 8 dp touch slop): `HORIZONTAL` if |dx| >= |dy|, else `VERTICAL`; a second pointer counts as horizontal. `blockDirectional` cuts the band while a horizontal gesture is under way and for `lenssettle` after the lift of a tap or horizontal gesture; a finger at rest and a vertical scroll keep it. The lift of a plain tap still settles (an app may react to it), so the band can drop for `lenssettle` after a tap; to revisit after the device test. The finger's direction is a proxy: a carousel under a fixed header is still cut, and a vertical scroll still draws a capture one or two frames old along the band. `LENS_STATS` reports the gate and the gestures per kind.

**Defaults:** `lenssettle` 100 ms (was 600) and `rimhz` 80 Hz (was 60), the values chosen on the device, so the profile survives a reboot.

**Telemetry:** `STATUSBAR_TONE hooked=|lightTop=`, `SHADE_OPEN_BEGIN generation= adaptive= lightTop=`, `SHADE_PREFETCH state=sample|cancel|unavailable tFromOpen= earlyDraws=`, `SHADE_TONE_APPLY source=estimate|cached|grid luma= lumaP90= veil= halo= alpha= tFromOpen=`, `SHADE_OPEN_END cards= earlyDraws= sampled=`, `POPUP_CONTRAST source=estimate|none|sample`, `TEXT_HALO` (trace only). `earlyDraws` counts visible card draws before the capture landed.

**Validation:** Gradle `testDebugUnitTest` (87 JUnit tests, new: `VeilMathTest`, `ToneGridTest`, `TextHaloTest`, directional cases in `BackdropMotionTest`), `lintDebug` (no new warning categories) and `assembleDebug` pass. AGSL is compiled on the device only; the hooks, the capture and the halo need the device. Not tested on the device.

### Round 37: material style instead of a grey veil, no glyph halo, pre-open capture (2026-10-25)

Source: the device handoff after PR #36 (log `01_conservative_log.txt`, capture `82192.png`, videos of the shade and of a pop-up over a white page). Round 36 was installed and ran; its look was rejected.

**What the device showed.**
- `SHADE_TONE_APPLY source=grid luma=0.98 veil=59000000 halo=0.45`: the module itself put 35 % black over a white app. Black at 25 % over white is #BFBFBF, grey under white text at ~1.8:1, so no `veilmax` value is both light and legible. The glyph halo (`TEXT_HALO views=29`) read as a dirty double outline.
- `STATUSBAR_TONE lightTop=false` over a white page (Chrome's dark toolbar in the dark theme): the first-frame estimate was wrong, and the cards turned from light to grey when the capture landed.
- The first shade capture of one opening landed after 616 ms: `CaptureHub` sleeps 500 ms between ticks while no client is shown, and a new client waited for that tick.
- Pop-up: the first sample (alpha 0, the card still above its final place) read 0.30-0.32, the right one (0.91) came with the 1 s timer. The lens band kept the plain tint around a darkened body: a light ring around a grey core.

**Material style (`VeilMath.material`, `ShadeTone.materialFor`).** On the theme's own side (dark backdrop under light text, or the reverse) the card keeps its native look with at most a small veil capped at `veilmax` (now 0.25). On the far side it changes style: over a light backdrop in the dark theme, light glass (`lightglass`, white at 0.25, Samsung's own light-theme veil) with dark text; over a dark backdrop in the light theme, dark glass (0x1cffffff) with light text. The backdrop counts as light from `stylehi` (0.62) and as dark again from `stylelo` (0.48), per card, so a mixed backdrop near a threshold does not flip. `textswap=0` keeps the veil-only behaviour.

**Foreground swap (`Foreground`, replaces `TextHalo`).** Only views of the standard templates are candidates: text views whose id belongs to the framework (`android`, the notification templates) or to SystemUI, and an icon only when its id names the expand control; a custom RemoteViews layout carries the app's ids (or none) and is never touched. Among them, views of the theme's text tone (near-neutral greys) get the mirrored grey (255 - value, alpha kept), so the primary/secondary hierarchy survives; icons only when tinted through `imageTintList` (the expand chevron); accent text, coloured spans, app icons and actions are untouched, as are a group's child rows. A view whose content set a new colour since is taken as the new original (swapped again if it is of the theme tone, released otherwise); restoring skips a view whose colour the content changed. It is undone on every path to the native background (`release`, ineligible, module disabled), as the halo was.

**First frame.**
- No estimate: until a capture lands, cards show the theme's native look, set at once (a veil left from the previous opening or surface is dropped without a ramp). Over a white app in the dark theme that is a light card whose text turns dark when the capture lands, instead of a light card turning grey.
- Pre-open capture: a touch down in the top 6 % of the screen with the shade closed over an app asks for a capture at once (`preOpen`, one per gesture). The capture is bound to that gesture (`MotionEvent.getDownTime`): an opening adopts it only while the same gesture is the current one and still down, or lifted at most 250 ms before (a fling), and the capture is at most 1.5 s old (`Eligibility.preOpenValid`; `SHADE_OPEN_BEGIN preValid=`, `SHADE_PREFETCH state=preopen age=`). A tap at the top followed by another gesture, or a cancelled pull followed by a new one, gets a fresh capture. Openings without a touch at the top (a launcher gesture) still capture on their first frame.
- `CaptureHub.register` now runs a tick at once.

**Pop-up.** Native on its first frame; a capture under the card every 100 ms while it enters (alpha below 0.99 or moved more than 8 px since the last measurement), then every 1 s. The lens band takes the body's region colour (`BackgroundBlurBridge.requestedTint`), so rim and body read as one glass.

**Glint and gate.** `liveglint` defaults to 0.10 (approved on the pop-up); shade cards get `shadeglint` (0.10); lockscreen stays 1. `lensgate` defaults to `directional`, the policy preferred on the device.

**Telemetry.** `MATERIAL_STYLE from= to= source= alpha= tFromOpen=`, `FIRST_VISIBLE_MATERIAL source= style= tFromOpen= at=preDraw` (the material the card's next draw is built with, recorded at pre-draw: not proof of the frame that reached the display), `SHADE_PREFETCH state=preopen|sample age=`, `SHADE_OPEN_BEGIN preAge=`, `POPUP_CONTRAST source=native|sample style= age= measuredAlpha=`, `TEXT_TONE` (trace). `STATUSBAR_TONE` and `TEXT_HALO` are gone.

**Not covered.** Text drawn by custom RemoteViews in their own colours stays as the app set it, which can be light text on light glass (it was equally unreadable on the grey veil). Icons tinted by colour filter rather than `imageTintList` keep their tone. The 710 ms between capture and application in round 36's slow opening is not explained by those logs; `FIRST_VISIBLE_MATERIAL` now records what each card first showed.

**Validation:** Gradle `testDebugUnitTest` (96 tests; new `ForegroundTest` including template scope, `EligibilityTest` pre-open binding, `VeilMathTest` style cases, `ToneGridTest` pre-open adoption), `lintDebug` (no new warning categories), `assembleDebug`. Not tested on the device.

### Round 38: rebind repair, no restore between surfaces, light frost (2026-10-25)

Source: device round of PR #37 (`OULG37_20261025_165840`, `04_transicoes.txt`; video 17:08:01 over a white page). Dark text on light glass was approved; the flicker and the too-white light glass were not.

**Flicker, from the log (row `f959dce`, the pop-up that became a shade card):**
- `17:03:08.946 TEXT_TONE swap=false views=43`, then `08.996 swap=true views=43`: between surfaces (heads-up animating away, shade not yet expanded) `tone()` fell to "any other surface" and restored the text, then the shade applied it again: a ~50 ms white flash.
- `17:03:09.189 BACKGROUND_UPDATE` (SystemUI binds the row again and sets its own colours), `09.693 TEXT_TONE swap=true views=41`: 504 ms later, the `SWAP_WALK_MS` backstop. The video's ~450 ms of white text on the top card matches.

**Changes.**
- `Foreground.drift`: the views carrying the swapped tone are tracked; every pre-draw of a swapped card reads their colour (no allocation) and walks again at once when one left the window (`detached`) or shows a colour other than the one it was given (`rebind`). The 500 ms walk stays as a backstop for new views. `TEXT_REAPPLY reason=rebind|detached|style|restore|walk sinceLastWalk= surface= style= generation=` (rebind/detached always logged, the rest with trace).
- Between surfaces on the unlocked shade bar state the material is held (no restore); restoring stays for the lockscreen and every path to the native background (`release`). A held pop-up veil reaches no shade card (own slot).
- A card measured less than 1.5 s before a shade opening (the pop-up it was, a reopened shade) keeps that reading until the opening's capture lands (`source=held`) instead of going native and back.
- Pop-ups entering are poked to redraw every 100 ms (1 s once still): the entrance animates RenderNode properties without drawing, and a measurement waited 1.2 s for an unrelated draw.
- Light glass: region colour `lighttone` (default `b8c2ce`, a cool light grey) at `lightglass` (0.32), ~#E8ECEF over white (luma ~0.92), instead of white at 0.25; the thickness shadow on light glass is scaled by `lightedge` (2). `lighttone ffffff`, `lightglass 0.25`, `lightedge 1` restore round 37.

**Not covered.** Openings with no pre-open capture and no recent reading can still draw the first frames native (`FIRST_VISIBLE_MATERIAL source=native`, prefetch landing 5-10 ms after the first pre-draw in gens 52/54). The tone of light glass is a device judgement.

**Validation:** Gradle `testDebugUnitTest` (99 tests; new drift and light-tone cases), `lintDebug`, `assembleDebug`. Not tested on the device.

### Round 39: colour writes intercepted, one scene per stack, ramps from the native tint, 24/10 pop-up look (2026-10-25)

Source: device round of PR #38 (`OULG38_20261025_174738`, video 17:51:47, screenshots of the pop-up on 24/10 23:40 and 25/10 18:06, E0-E4).

**From the logs.**
- Row `c9ee378` (a group row: two backgrounds, radii 108 and 0) logged `TEXT_REAPPLY reason=rebind views=1` every 16-19 ms: SystemUI writes one text colour on every frame and the module repaired it on the next pre-draw. Whichever write came last was on screen. The "Minimised" group title fits (white or out of step), but its identity is not in the log.
- `LIVE_STATE ... tint=1b8c2ce`: `VeilRamp` stood the native look in by the other veil at alpha 1, so a ramp from native to light glass started from a nearly clear region instead of the native tint.
- `luma=0.52` read native in generation 12 and light in generation 14: the per-card hysteresis carried over openings, and cards of one stack disagreed.
- The 24/10 screenshot predates the lens band (PR #35, 25/10 03:55 UTC) and, by its time (02:40 UTC), most likely the dark-theme lift (PR #34, 01:41 UTC): region 0x14ffffff with a 4 % black fill, versus 0x1cffffff and no fill since. `liveoptics` was 1 then; 0.5 now halves the outer line with the rest.

**Changes.**
- `Foreground` colour guard: `TextView.setTextColor` and `ImageView.setImageTintList` are hooked (before) in SystemUI. A write on a view the module swapped is redirected in the same call: a colour of the theme's tone becomes the new original and the mirrored one is set; an accent releases the view. Other views pass untouched (the map is empty while nothing is swapped). The module's own writes bypass it. `SHADE_OPEN_END intercepted=` counts them.
- Drift repair stays as a backstop, with a contest guard: more than 6 repairs of one card within 500 ms stop the per-frame walks (`TEXT_CONTESTED`); `TEXT_TONE_DRIFT` logs once per view class and id (never text). With trace, a light scene logs theme-tone text views outside the rows (`TEXT_OUTSIDE`), where a label like "Minimised" lives if it is not in a row.
- Scene classification (`VeilMath.classify`): luma >= `stylehi` is light and <= `stylelo` is dark for any card; between them a card follows its scene. The shade's scene is decided once per capture from the whole backdrop (`SHADE_PREFETCH scene= sceneLuma=`). A pop-up's scene is its previous reading in the same pop-up. No memory crosses openings or pop-ups.
- `VeilRamp.ends(from, to, nativeTint)`: a veil of 0 is the card's native tint, and colours of different RGB ramp too (light glass and the native tint are both light). The text swap waits for the ramp's midpoint (`pendingSwap`). A card going native or released drops a pending swap.
- Pop-ups and lockscreen cards in the dark theme: region `livedarkalpha` (20, i.e. 0x14ffffff) and fill `livedarkfill` (10, i.e. 4 % black; none on light glass). The outer contour has its own gain `liverim` (2 on pop-up and lockscreen, 1 on shade cards).

**Not covered.** Whether the lens band adds to the milky look is not separated from the region colour; `livelens 0` vs `1` on the same wallpaper answers it. A label outside the rows is found by `TEXT_OUTSIDE`, not yet recoloured.

**Validation:** Gradle `testDebugUnitTest` (103 tests; new redirect, scene and ramp cases), `lintDebug`, `assembleDebug`. Not tested on the device.

### Round 40: system gestures and app transitions cut the band, faster pop-up re-measure, lockscreen restored, "+N" text (2026-10-25)

Source: device video of PR #39 (18:45:08) and a lockscreen screenshot (18:46). The log sent was empty, so the shade's first-frame flash and the minimised group are only partly addressed.

**From the video.**
- White app to the home screen with a pop-up shown: the home swipe starts in the bottom strip and is vertical, so `directional` kept the band, which drew the stale white capture of the closing app as a white ring around a body that had already turned blue.
- Launcher to Chrome: after the tap's 100 ms settle the band captured the launch animation, a dark ring around a white body; the text stayed white on white for ~270 ms because a still pop-up was measured once a second.
- Lockscreen: a second arc inside the outline again. `liverim 2` and the darker region of round 39 were applied to every lens surface, the lockscreen included.
- Shade over white: the collapsed group's "2 more notifications" text stays white on light glass.

**Changes.**
- `BackdropMotion`: a touch down in the bottom 4.5 % or the side 4 % of the screen (system gestures: home, recents, back) counts as horizontal from its down. A tap or a system gesture keeps the band cut for `lenstransition` (350 ms) beyond `lenssettle` after the lift (lastMotion in the future), for the app transition it may start; a plain horizontal swipe settles as before.
- Pop-ups are measured every 100 ms for 1.5 s after any touch, not only while entering.
- `livedarkalpha`, `livedarkfill` and `liverim` apply to pop-ups only; lockscreen cards are back to their look before round 39 (`liveDarkColor`, no fill, rim 1).
- `Foreground`: a text view without id qualifies when it was inflated with SystemUI's own context (the row's package), which covers a group's "+N" overflow text; views of a RemoteViews layout are inflated with the app's context and still never qualify.

**Not covered.** The shade's first-frame flash on every backdrop: without the log it is not attributed (candidates: the material rebuilt at each opening, the radius ramp from pop-up to shade, the first frames before the capture). If the minimised group's label is not the overflow text, `TEXT_TONE_DRIFT` / `TEXT_OUTSIDE` (trace) name it.

**Validation:** Gradle `testDebugUnitTest` (106 tests; new system-gesture, transition-settle and no-id cases), `lintDebug`, `assembleDebug`. Not tested on the device.

### Round 42: low-resolution tone prefetch, minimised groups, header tone (2026-10-25)

- Flash at the opening: the tone prefetch landed 71-108 ms after the opening, after the cards' first visible frame (42-58 ms), so their text turned from white to dark on screen (device log, 25/10). The capture itself took 5-8 ms; the software copy at the band's 0.5 scale was the cost. CaptureHub now captures at the largest scale its shown clients ask for (`Client.scale()`); the prefetch asks for 0.125 and samples every pixel of it. A lens frame never comes from a prefetch-only capture (`CaptureBackdrop` skips frames below `CaptureHub.SCALE`).
- Minimised group: a child row drawn inside its group's card (no glass of its own) is now walked with the group's tone; child rows with their own glass keep theirs (`Foreground.ownsTone`). TEXT_ROW showed `app_name_text ffffffff swapped=false` on the group's light glass.
- Header: `header_clock`, `header_date` and `shade_carrier_text` follow the backdrop band under them at each shade capture and are restored on close (`HEADER_TONE`, off with `headertone 0`). `clear_all` and `noti_setting` sit on their own pills and are left.
- Light glass (shade and pop-up) is the light theme's own card: white (`lighttone ffffff`) at `lightglass` 0.75, with the 1x thickness shadow (`lightedge` 1). The grey `b8c2ce` at 0.32 sat darker than the white app behind it (luma ~220 over ~235); the light theme's card sits above it (~250 over ~236), which is what reads as a card floating over the app (device screenshots, light vs dark theme, 26/10). Its end is marked by a brighter edge, light bent round the outer contour (`lightoutline` 0.8, shader `outline`: a bright contour all round plus a stronger Fresnel lift), on light glass and on the light theme's own shade and pop-up cards; the lockscreen is unchanged. `lighttone b8c2ce; lightglass 0.32; lightedge 2; lightoutline 0` restore round 41.

### Round 43: one luminosity per stack, minimised group child, header from the scene (2026-10-26)

- Shade cards take their material from the whole screen's mean (`ToneGrid.whole()`), not their own band: over a colourful wallpaper the card under a white cloud was veiled darker than the others (device frame, 26/10). Each card's colour still comes from the live blur behind it. `shadelocal 1` measures each card's band again.
- A shade card's native-side legibility veil is laid over its native tint (`VeilMath.over`) instead of replacing it: the replacement dropped the native frost, so a 2-7 % black veil made one card read much darker than its neighbours with none (oulg42: generations 7, 9, 10, 12, only the card under the wallpaper's bright patch, `lumaP90` 0.73-0.83, got `veil` 0x02-0x12). Glass styles still replace the tint; the pop-up is unchanged.
- The header follows the same scene reading: the band under it read 0.34 over a white app while the header animated in.
- In the open shade a child row owns its tone only if its own card took this opening's tone (`toneOpen == shadeOpens`): a minimised group's child kept its glass, its background was not drawn, so its tone never ran and `app_name_text` stayed white (oulg42).
- The shade window's blur is darkened by `paneldim` (0.07 black over `mBackgroundBlurColor`) when the scene is light in the dark theme: over a white app the light theme's panel sits at ~235 and its cards at ~250, so the cards stand out; the dark theme's panel sat at ~250 under cards of ~249 and the cards dissolved into it (device screenshots, 26/10). Set on every panel blur SystemUI issues while on, and once more when the scene is decided; turned off by the closing shade's own blur calls. `paneldim 0` disables it.
- The grey shade in oulg42 was the device's own props: `lightglass 0.32`, `lighttone b8c2ce`, `lightedge 2` were set explicitly (the round-41 restore command) and override the new defaults; the shade cards showed `veil=52b8c2ce`.

### Round 44: shade light glass at the light theme's alpha, orphan rows, panel dim 0.12 (2026-10-26)

- The shade's light glass is white at `shadelightglass` 0.27, the light theme's own shade card; the pop-up keeps `lightglass` 0.75. A card's blur samples the app itself, not the panel's blur: over a white app it reads ~251 at either alpha, but at 0.75 a colour under the card at ~140 read 226 where the light theme reads 171, so the dark theme's cards looked like opaque white panels (device screenshots of the same notifications in both themes, 26/10). Round 42's 0.75 came from comparing the card with the dimmed panel, which the card's blur never sees.
- Shown rows whose own card took no tone this opening take the scene's text tone (`TEXT_ORPHAN`), at the capture and 300 ms and 1 s after; restored when the shade closes. oulg43: a minimised group's two rows had no card drawn (radii 0) and `app_name_text ffffffff swapped=false`; round 43's child-row walk did not reach them.
- `paneldim` defaults to 0.12: with it the dark theme's panel over a white app matched the light theme's in the screenshots (~221 in both).

### Round 45: region gate for the pop-up band, experimental (2026-10-26)

`debug.oulg.lensgate=region` (default stays `directional`). Not tested on the device.

- A touch cuts the band only if it is a system gesture (bottom 4.5 %, sides 4 %) or went down within 160 px above or below the card; those follow the directional rules. Any other touch (a carousel or the keyboard mid-screen, a tap on an app) no longer cuts it, and captures continue at `rimhz` through it.
- Instead, every capture shown to a band is read back on its own worker (`oulg-region`) and reduced to mean luminance per 16 px screen cell (`RegionDiff`). Two consecutive captures that differ by more than `lensregiondiff` (default 2/255) in any cell under the card set `changedAt`; the band returns only from a capture requested `lenssettle` after it (`regionMotion`). A capture whose verdict lags more than `lensmaxage` behind it is not drawn (`regionPending`). A failed readback falls back to the directional gate for that band (`REGION_READBACK_FAILED`).
- Motion without touch (video, an app's animation, a launch) now cuts the band too, which the touch gate never saw.
- Cost and limit: the change is seen only after a capture shows it, so the first moving capture behind the card is still drawn (onset trail of about one capture plus the readback). The readback goes through the RenderThread; a 0.5 shade capture took 55-100 ms on the shade worker (25/10). If that holds here, most draws log `regionPending` and the band rarely shows. `REGION_STATS` logs readback P50/P95/max, skipped captures and still/changed/unknown verdicts every 240 readbacks.

## Future work

- **Separate blur for the notification center and the control center.** Theme Park and HomeUp set a single blur amount for both panels. The user runs 12 %: lower leaves the control center unreadable, higher over-blurs the notification list. A split needs its own investigation: find where SystemUI applies the panel blur, whether the two panels are separate blur regions or one window, and whether the module can own one of them.
- **Tuning the compositor lens without a restart.** Lens strength, bevel width and highlight are constants in `refract.h`, so each change needs a surfaceflinger restart and a cleared cache. The radius fraction already carries the tag and could also carry the strength, so the module could tune it live.
