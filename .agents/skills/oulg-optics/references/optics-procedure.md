# Architecture and optical validation

All source paths below are relative to the repository root. Inspect them at the actual revision; this map was checked at `b5d7426`.

## Read the responsible layers

- `sfhook/refract.h`: profile defaults/normalization, radius decoder, `OULG_FS_FIELD_FMT`, `oulg_shift`, `oulg_field`, blur/lens, `oulg_material`, `oulg_glow`, light composition, `oulg_lib`, FillRRect and clip rewrites. Read the full file before a Clear Glass implementation; macro format arguments and generated literals are part of the program.
- `sfhook/oulg_sf.c`: `glShaderSource` interception, `layered_blur` property reads, rewrite/fallback, health and `announce_optics`. Properties become GLSL literals when a shader is compiled; cached programs do not update merely because a property was saved.
- `app/src/main/java/io/github/igorcv88/oneuiliquidglass/glass/SemBlurBridge.java`: `sfTag`, `tagProfile`, `reliableTag`, strength and corner tags. Trace callers in `BackgroundBlurBridge`, `HeadsUpHooks` and ownership in `Eligibility`/`GlassDrawable`.

Profiles are 0 HU/pop-up, 1 KG/lockscreen, 2 expanded shade, 3 QS. Do not conflate profile identity with lens strength. At the studied revision, fractional radius windows are respectively 0.55–0.70, 0.30–0.45, 0.05–0.20, 0.80–0.95. Clip decodes radius plus 0.5; FillRRect carries a highp varying and maps device displacement through texture derivatives. Clip uses device pixels and applies `u_skRTFlip` to displacement/light vectors. Test both paths, not just a shared helper.

`mediump` may mean fp16 on hardware: quantify quantization through the actual uniform path, including the half-radius offset, radius limits and common unrelated radii. Fable found no safely available fifth tag window. Its mode-2 slice uses an existing strength channel only for profiles 0/1; do not reinterpret shade polarity or the QS tag.

## Findings to preserve, with evidence limits

Fable's labels are [C] code, [N] numerical Python verification, [H] hypothesis and [P] device-dependent. Cite the specification section and current source when adopting them.

- KG's continuous dome plus smooth edge field supports continuous, fold-free refraction in normal settings [C/N]. This is not a claim of a globally continuous Jacobian: the straight-to-arc junction has a tangential Jacobian jump. Extreme global refraction scales can invalidate the baseline bound.
- KG's frosted appearance is primarily explained by disk blur and luminance leveling [C/N]; actual Samsung-region blur, source resolution and app veils require device evidence [P]. HU's existing SF material is identity, so do not attribute its veil to KG tonemapping.
- HU uses a quadratic bevel lens plus dome [C]; Fable's model folds at some strengths, including the studied `sflens` default [N]. Actual active strength and clip precision remain [P].
- Preferred Clear Glass proposal: carry the continuous field into HU and isolate the material from baseline. Cubic dome is conditional, not a mandatory embellishment. Read spec §§5–6 for amplitude bounds including global refraction, continuity and magnification limits.
- Proposed selection (§7): 0 emits byte-identical baseline; 1 selects clear throughout the profile and needs shader recompilation to change; 2 compiles both variants and permits per-card live selection via the tag only after compatible shaders are confirmed compiled. Changing compilation flags/knobs still requires recompilation. Numerical fp16 slice success is not hardware proof.
- Dynamic `oulg_glow` samples unrefracted background coordinates: the final displacement cancels in the edge offset. Compute that offset after all displacement/scaling. Preserve Fresnel, structural contour and specular independently; do not feed the dynamic light through material/disk blur. Region blur still affects its source and possible flicker.

## Design checks

For each candidate, derive `J = I + dF/dq`, determinant, singular values, magnification bounds, corner/medial-axis behavior and band continuity. Compare formula, CPU twin and actual generated GLSL; state domains and junction exceptions. Check sampling limits, fractional radii, extreme parameters, and all supported orientations.

Separate region blur from SF disk blur, geometric displacement from transmission, and luminance veil from multiplicative dimming. Read spec §§5, 9–10 for experiments and metrics instead of freezing its starting constants. Uniform material weighting must not silently disable structural illumination through `coreOn`. Whole-body refraction must sample the backdrop, not refract notification text or add a second AGSL lens.

Preserve premultiplied alpha, texture types, P3/HDR and color-space behavior. Baseline Rec.709 weights on encoded values are not linear luminance. External OES textures use the restricted sampling path; do not promise disk/material/light parity without implementation and tests.

Measure generated library/main-block lengths in every supported mode/profile/debug combination, including dual mode. Baseline buffers include `lib[16384]` and `block[2048]`; overflow can return the original shader silently. Fable requires at least 25% headroom for Clear Glass; test overflow/error reporting and report worst-case sizes.

## Verification gates

Inspect command interfaces and dependencies before running. From repository root, `sh sfhook/tools/check.sh` builds/runs `field_check.c` and `refract_check.c`, checks properties/material contracts and compiles/links rewritten shaders with `glslangValidator`. Do not install dependencies automatically just to satisfy a check. Read `sfhook/tools/render_check.mjs` for its supported invocation and browser/render requirements; run its FillRRect/clip parity checks (`fillVsClip`, `fillKgVsClipKg`) and experimental equivalents.

Use the supplied `ClearGlass_Handoff/diagnostics/optics_spec_check.py` as a numerical reference only. Run from a temporary output location if a diagnostic writes files. Do not edit Fable artifacts.

For optical changes, cover spec §9 invariants and §13 acceptance: exact generated-GLSL hashes against the pinned base with experimental selection off (including debug 0/1/2), unchanged profiles 2/3, untagged and non-selected profiles; field CPU/GLSL agreement; positive determinant and bounded singular values; band/corner/medial-axis transitions; blur endpoints/monotonicity; tag decoding under fp16; light-locality and material/render metrics. Byte equality is the mode-0 criterion, not merely visually similar screenshots. Benchmark shader compile and rendering costs before increasing taps/work per fragment.

Known baseline alerts, not permission to fix: `sflens` default/comment mismatch and modeled HU folding; `kglevel` accepted range/default mismatch; stale manual `field_check` constants/coverage; amplitude cap ignoring global refraction at extremes; possible duplicate KG contours under `kgsharedoptics`; tag collisions at some densities. Record these separately. A passing stale field port alone cannot prove the actual shader safe.

Physical acceptance needs controlled HU and lockscreen videos/logs with device density/resolution, optical generation, compiled modes, active backend and tuning; test source detail, local dynamic response, flicker, text/custom RemoteViews, P3/HDR and restoration. Logs prove reported state, not pixel quality. Stop acceptance if hardware evidence is absent.
