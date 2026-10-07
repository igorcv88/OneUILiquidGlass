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

From this data, heads-up rows and lockscreen rows get backdrop kind `CAPTURE`. Expanded-shade rows keep `SHARED`, because the shade draws its own scrim and blur below them, and sampling the unblurred app there would bypass that scrim.

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
- frosts the sample with a 9-tap ring;
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
| `blur` | 3 | Frost radius, screen px |
| `sat` | 1.2 | Saturation |
| `disp` | 0.10 | Relative red/blue shift; physical glass is about 0.01 |
| `hz` | 15 | Heads-up capture rate |
| `hzkg` | 5 | Lockscreen capture rate |
| `tint` | -1 | Veil alpha override, 0–255 |

Not measured yet: SurfaceFlinger GPU time per capture and battery cost. Protected (DRM) layers capture black, and no flag reports them.

