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

`BackgroundBlurDrawable` requests compositor blur, but does not give AGSL a texture. `RenderEffect` on a notification subtree blurs that subtree, not the app underneath. Capturing the SystemUI tree has the same process/window boundary. SurfaceControl/HardwareBuffer capture APIs need careful inspection of permissions, secure surfaces, source selection, fences and frame cost on this firmware before adoption. No capture API is invoked by this build.

A future sampled-backdrop provider should expose texture, coordinate transform, valid region, timestamp and ownership/lifetime. Refraction/dispersion must be gated on that provider actually yielding the lower app content, and must preserve secure/protected surface handling. A compositor-only material remains an independent tier.

## Known pending work

Physical hook verification, shader compilation on the device, Samsung brief popup mapping, animated corner calibration, QS behavior with separate Samsung panels, lockscreen transition tests, keyboard window composition, contrast over varied app content, frame-time measurements and sampleable backdrop investigation. Existing JVM compilation and policy tests do not establish any of these behaviors.

## Backdrop selection (2026-10-07)

Device evidence (SM-S938B, SDK 37, One UI 9.0): `ro.surface_flinger.supports_background_blur` is unset, so AOSP cross-window blur and `BackgroundBlurDrawable` are unavailable. The material therefore uses a `Backdrop`:

- `COMPOSITOR` (`BackgroundBlurBridge`): preferred when `isCrossWindowBlurEnabled()` is true. Drawn inside the material's clip.
- `SAMSUNG` (`SemBlurBridge`): `View.semSetBlurInfo(SemBlurInfo)` with `BLUR_MODE_WINDOW`, set on the native background view and cleared with `null` on every release. One UI renders it under the view's content, so the material draws only the edge shader. Builder members (`setRadius`, `setBackgroundColor`, `setBackgroundCornerRadius`, `build`) are resolved reflectively. Required: `View.semSetBlurInfo`, the int constant `BLUR_MODE_WINDOW`, a `Builder(int)` constructor or `Builder()` plus `setBlurMode(int)`, `setRadius(int)` or `setBlurRadius(int)`, and `build()`; the color and corner setters are optional. `SEM_BLUR_BRIDGE` reports the variant in use. Read-only recon on `S938BXXUCZZIC` (dex strings of `framework.jar`) confirmed `SemBlurInfo`, `SemBlurInfo$Builder`, `BLUR_MODE_WINDOW`, `semSetBlurInfo`, `setBackgroundColor` and `setBackgroundCornerRadius`, and that the color and corner setters are accepted only in `BLUR_MODE_WINDOW`; the builder constructor and radius setter signatures were not resolvable from strings. Updates are posted after the current traversal because they originate in the hooked `onDraw`.
- No backdrop: `DECISION reason=blur=unavailable`, native rendering.

Known limits of the Samsung path, pending device validation: it takes one corner radius (non-uniform corners stay native); it does not follow the native drawable's alpha during appear/disappear; the API signature is inferred and logged (`SEM_BLUR_FIELD/METHOD/CTOR`), not documented by Samsung.
