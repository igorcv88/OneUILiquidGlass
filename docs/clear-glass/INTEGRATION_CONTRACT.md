# Clear Glass — compositor integration contract (optics 12)

This is the contract between the compositor change on `feat/clear-glass-compositor-optics` (`sfhook/`) and the Android integration that follows it. It describes what the compositor implements, how the module selects it, what it reports back, and what stays the module's job. Nothing here has been validated on the S25 Ultra yet; every visual statement below comes from host tests (C twin, glslang, WebGL/SwiftShader) and is listed as pending physical validation at the end.

Source of the optics: `ClearGlass_Handoff/OPTICS_SPEC.md`, proposal A. Code: `sfhook/refract.h` (generator, `struct oulg_clear`, `oulg_fix_clear`, `oulg_clear_acap`), `sfhook/oulg_sf.c` (`clear_params`, `announce_clear`).

## 1. What exists

Clear Glass is an experimental variant of two profiles only:

| Profile | Surface | Clear variant changes | Clear variant keeps |
|---|---|---|---|
| 0 | heads-up (HU) | lens: the optics-11 quadratic edge lens + 6 % dome is replaced by the continuous slab field below; blur: one continuous curve edge → core | the HU material (identity in the compositor), `oulg_glow`, edge light, premultiplication, P3/HDR path |
| 1 | lockscreen (KG) | lens: same slab field over a configurable band; blur: one continuous curve; material: uniform body (saturation, knee, additive veil toward a level, multiplicative dim, cool tint) replacing the core/rim split and luminance levelling | Fresnel, contour and specular block copied verbatim from optics 11, lighting coordinates, `oulg_glow`, edge light |

Profiles 2 (shade) and 3 (QS) and every untagged surface are untouched. There is no fifth profile.

### Field (both profiles)

```
band = clamp(beta * r, bevel, r)          bevel = clamp(0.42 r, 16, 56)
t    = 1 - depth / band,  G(t) = t^2 (3 - 2t)
u    = q / hs                              (per axis)
s    = -R u (l0 + l2 u^2) - n A band G(t)  (the second term only for 0 < depth < band)
F    = rho * s                             rho = hurefract / kgrefract
A    = min(k [mode 1 only], amp, amax)
amax = ((1 - 0.25) / rho - l0 - 3 l2) / 1.5, >= 0, rounded down to 4 decimals
blur = (edge + (core - edge) * S5(depth / band)^blurexp) * hublur / kgblur
```

With these bounds the sampling map has 1 ≥ σ ≥ 0.25 everywhere: no fold, at most 4× magnification, and the Jacobian determinant stays positive.

## 2. Selection: modes

Each profile has its own mode, read when SurfaceFlinger compiles a fragment program:

| Property | Values | Effect |
|---|---|---|
| `debug.oulg.sf.huclear` | 0, 1, 2 | profile 0 |
| `debug.oulg.sf.kgclear` | 0, 1, 2 | profile 1 |

- **0** (default): no clear code is emitted. The generated GLSL is byte for byte the optics-11 output of `db20a8f` (verified for debug 0, 1, 2, with and without other clear parameters set).
- **1**: every card of the profile takes the clear variant. In mode 1 the tag's strength `k` still caps the edge amplitude (`A = min(k, amp, amax)`).
- **2**: both variants are compiled. A card takes the clear variant when its tag strength decodes to `k >= 1.035`; otherwise it renders as optics 11. In mode 2 the amplitude is `min(amp, amax)` (the strength slice is spent on selection).

Any other value is treated as 0. Modes for HU and KG are independent.

## 3. Per-card selection (mode 2) and tag encoding

The module already encodes profile and strength in the fractional part of the corner radius (`SemBlurBridge`). Mode 2 reuses that encoding with no new channel:

| Profile | Window of `fract(r)` | strength `s` → `k = 0.1 + 1.1 s` |
|---|---|---|
| 0 | [0.55, 0.70] | `fract = 0.55 + 0.15 s` |
| 1 | [0.30, 0.45] | `fract = 0.30 + 0.15 s` |

Contract for the module:

- To select clear for a card: send `s = 11/12` (k ≈ 1.108).
- When a profile is in mode 2, baseline cards of that profile must be sent with `k ≤ 0.85` (s ≤ 0.6818). This leaves a guard band against fp16 decoding in the clip program (`uradiusPlusHalf` may be mediump): host tests over 232 radii found the first fp16 misread at k = 0.880.
- Radii tested: 42 to 127.5 px. Outside that range the fp16 margin is unverified.
- Only profiles 0 and 1 have a clear slice. Do not send `s = 11/12` for profiles 2 or 3; they ignore it (and the shade/QS paths must not change).
- Switching a card between clear and baseline in mode 2 is live (no recompile): only the tag changes.

## 4. Parameters

All parameters are read at shader compilation and need a recompilation to take effect (in practice a SurfaceFlinger restart, which the module must not do on its own). Out-of-range values fall back to the default for that parameter (`oulg_fix_clear`).

Prefix `debug.oulg.sf.hc*` for profile 0, `debug.oulg.sf.kc*` for profile 1:

| Name | Meaning | Range | Default HU | Default KG |
|---|---|---|---|---|
| `band` | band width β (× r, clamped to [bevel, r]) | 0.42 – 1.0 | 0.85 | 0.85 |
| `amp` | edge amplitude cap Â | 0 – 0.5 | 0.40 | 0.40 |
| `body` | dome λ0 | 0 – 0.15 | 0.06 | 0.05 |
| `body2` | cubic dome λ2 (proposal B; off) | 0 – 0.05 | 0 | 0 |
| `edge` | blur radius at the outline, px | 0 – 48 | 4 | 4 |
| `core` | blur radius past the band, px | 0 – 48 | 24 | 24 |
| `blurexp` | blur ramp exponent | 1 – 4 | 2 | 2 |

Extra rules: `edge > core` is lowered to `core`; `body + 3·body2 > 0.45` reverts both to their defaults.

Profile 1 material only (`debug.oulg.sf.kc*`):

| Name | Meaning | Range | Default |
|---|---|---|---|
| `veil` | additive veil weight toward `level` | 0 – 0.2 | 0.04 |
| `level` | veil target luminance | 0.1 – 0.85 | 0.85 |
| `dim` | multiplicative dim | 0 – 0.3 | 0 |
| `knee` | highlight knee | 0 – 0.6 | 0 |
| `sat` | saturation | 0.9 – 1.3 | 1.0 |
| `cool` | cool tint (baseline meaning) | 0 – 2 | 0.5 |

Shared with optics 11 and still in force on the clear variant: `hurefract`/`kgrefract` (ρ, also lowers `amax`), `hublur`/`kgblur` (blur scale), `hulight`/`kglight` (edge light), the profile's taps (`taps`/`kgtaps`; there is no clear-specific tap count). Optics-11 parameters that the clear variant replaces (`sflens`, `edgeblur`, `core`, `hubodylens` for HU; `kgcore`, `kgedgeblur`, `kgband`, `kgbodylens`, `kgblurexp`, `kgflat`, `kglevel`, `kgsat`, `kgcorecap`, `kgcoredim` and similar for KG) have no effect on clear cards and keep their effect on baseline cards.

External (`samplerExternalOES`) programs get the clear field and blur but no KG material, as with optics 11.

## 5. Handshake and reported state

| Signal | Meaning |
|---|---|
| `debug.oulg.sf.optics = 12` | the library knows Clear Glass. It does **not** mean Clear Glass is compiled or active. |
| `debug.oulg.sf.clear = hu:<m>,kg:<m>` | the modes of the last successfully compiled program. Set on change. SELinux may refuse the write, as for `optics`. |
| log line `CLEAR_OPTICS version=12 hu= kg= published= hc=... kc=... kcmat=...` | the effective (range-checked) parameters, including the effective `amp` after `amax`; `published=` is the property write's return code |
| `oulg-live.sh status` → `sf_clear` | the helper's read of `debug.oulg.sf.clear` |

Rules for the module:

- Treat optics < 12 as "Clear Glass unavailable"; hide or disable the controls.
- Treat a written `huclear`/`kgclear` as **pending** until `debug.oulg.sf.clear` (or the `CLEAR_OPTICS` line) reports it. Before that, the device renders the previous modes.
- A reported mode is what a program was compiled with, not proof that a card was drawn clear. Per-card confirmation needs the debug maps (section 7) or a screenshot.

The live helper (`sfhook/live/oulg-live.sh`) accepts `huclear kgclear hcband hcamp hcbody hcbody2 hcedge hccore hcblurexp kcband kcamp kcbody kcbody2 kcedge kccore kcblurexp kcveil kclevel kcdim kcknee kcsat kccool` in `setprops`. It refuses the bare key `clear` (the reported state is not writable).

## 6. Changes expected on the Android side (not done here)

- `SemBlurBridge`: a clear flag per card that sends `s = 11/12` for profiles 0/1 when that profile is in mode 2, and clamps baseline strengths to `k ≤ 0.85` while mode 2 is active.
- `BackgroundBlurBridge` / region blur: same tag rule wherever a profile 0/1 radius is encoded.
- `Tuning`, `SfLive.WRITABLE` / `SettingsRegistry`: the properties in section 4, with the ranges above. Validate before writing; the compositor's fallback is a safety net, not the UI's validation.
- Already done in this PR (registry only, no UI): the 23 properties are in `docs/PROPERTY_CATALOG_2026-10-26.md` and the generated `SettingsRegistry` (195 specs) with the domains above; `sf.huclear`/`sf.kgclear` are INT 0–2, default 0; `sf.clear` is READONLY with an empty default (absent = unknown). In `Controls` they are `Tier.INTERNAL` rows with `Format.READONLY`, so `Controls.editable` is false and they appear only as read-only rows in the technical catalogue search. `ClearGlassRegistryTest` checks the domains against `oulg_fix_clear`.
- Left for the integration: move the controls out of `Tier.INTERNAL` with real formats, add the keys to `SfLive.WRITABLE`, and delete `CLEAR_GLASS_PENDING_INTEGRATION` in `CompositorValuesTest` (the test then requires them in WRITABLE). Presets currently accept the 22 writable keys like any other INTERNAL key and keep them as desired values; nothing writes them to the device until they are in WRITABLE.
- `CompositorValues`: show clear values as pending until recompiled (section 5).
- Decide edge ownership for clear KG cards under `kgsharedoptics = 1` (spec O6/R5): the Java edge shader then draws over the compositor's contour, and with less veil and blur a double contour is more visible. Clear Glass does not change who draws the edge.
- The compositor reads `hc*` and `kc*` independently; any linking of HU and KG clear values is the module's choice.
- Do not change QS, shade, Now Bar, Live Updates, toasts, custom notifications or text adaptation.

## 7. Instrumentation

`debug.oulg.sf.debug` (via the existing debug property) gains two map modes, for host tests and on-device inspection:

- **3 — field map**: RGBA encodes the shift. x: `s/256 + 0.5` split hi/lo over R, G; y: the same over B, A. Decoding: `sx = ((R + G/255)/255 - 0.5) * 256`.
- **4 — blur map**: R, G = `br/128` hi/lo; B = 255 for a clear card, 0 for baseline; A = 255.

Both apply to profiles 0/1 cards (clear and baseline), so a screenshot of a mode-2 screen shows which cards were drawn clear.

## 8. Fallback and rollback

- Rollback: set `huclear=0` and `kgclear=0`, then recompile. The output is then optics 11 byte for byte.
- In mode 2, rolling back one card is sending its baseline tag; no recompile.
- Invalid parameter values fall back per parameter; an invalid mode falls back to 0.
- If a rewrite cannot be generated (buffer) or does not compile, the hook compiles the original shader, as with optics 11 (`REWRITE_FAILED` in the log).
- Library buffer: 32768 bytes when clear or debug ≥ 3 is compiled (largest clear library measured: 15959 bytes); 16384 otherwise (optics-11 library 11162). The `main()` block is unchanged (largest 1365 of 2048).

## 9. Host validation (this PR)

All in `sfhook/tools/check.sh` (CI) except the WebGL check, which needs Playwright and runs by hand:

- `clear_check.c`: σ bounds over 191 M points (σ_min 0.2500, σ_max 1.0), the field never samples outward (rim margin ≥ 3.6 px), continuity at band and medial axis, blur continuity, fp16 tag decode over 232 radii, the optics-11 `sflens 0.55` fold (positive control), parameter ranges, library sizes.
- `clear_shader_check.py`: optics-11 byte identity with the clear modes off (debug 0/1/2); 200 clear rewrites (8 mode pairs × debug 0–4) compile and link; `main()` unchanged; off profiles emit no clear code.
- `clear_render_check.mjs` (WebGL2/SwiftShader): GLSL field and blur maps match the twin within 0.015 px (fill and clip); pixel-grid σ_min ≥ 0.30 and det > 0; no reversal of the sampled position along rows and columns (the optics-11 `sflens 0.55` map shows 20, as expected); isolation of profiles 2/3, untagged, the off profile and mode-2 baseline cards (33 cases, max difference 0); metamorphic checks.

## 10. Pending on the S25 Ultra

None of these can be decided on the host:

- Visual approval of HU and KG clear (H01–H04, K01–K12 cells), on real wallpapers, light and dark, with text behind the card.
- Readability of notification text over the clear KG body (veil 0 / 0.04 / 0.11).
- GPU cost on the Adreno (SwiftShader timings are relative only).
- fp16 tag decode in the real clip program at the radii the device uses (HU 108 at ~1340×360).
- Whether `debug.oulg.sf.clear` is writable by surfaceflinger under the device's SELinux policy.
- Recompilation path: which action the module may use to make new parameters effective without restarting SurfaceFlinger itself.

## 11. Risks

- R1 mode 2 tag misread: a baseline card sent with k > 0.85 can be read as clear in the fp16 clip path. Mitigation: the module clamps.
- R2 pending state: users see no change after writing properties until recompile. Mitigation: show pending.
- R3 KG text contrast: a clear body lowers the levelling that kept notification text readable on bright wallpapers. Test with veil > 0.
- R4 SwiftShader ≠ Adreno: precision and cost may differ.
- R5 HU blur: core 12 shows much more backdrop structure than optics 11 (structure 9.2 vs 2.0 on the test backdrop); core 24 is about half of that.
- Existing optics-11 issues kept as they were (not fixed here): `sflens 0.55` folds the HU edge (5.6 px at r 92, 6.6 px at r 108); `kglevel` range; the stale `field_check`.
