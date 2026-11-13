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

The live helper (`sfhook/live/oulg-live.sh`) accepts `huclear kgclear hcband hcamp hcbody hcbody2 hcedge hccore hcblurexp kcband kcamp kcbody kcbody2 kcedge kccore kcblurexp kcveil kclevel kcdim kcknee kcsat kccool hcfold hcsat hcscrim hcscrimmax hcadapt kcfold hclift kclift hcneutral` in `setprops`. It refuses the bare key `clear` (the reported state is not writable).

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

## 12. Heads-up meniscus and clear material (`feat/clear-glass-meniscus`)

Added after the first device captures of optics 12 (S25 Ultra, DZIF, lossless PNG crops, `hcedge 8`, `hccore 24`, `hcblurexp 1`). Measured on those crops: colour boundaries behind the card stay straight within 1.5 px over the whole height (the slab moves a vertical edge only through the 1.8 % dome); the 10–90 % width of a black/white step is 32–33 px in the body and 20–21 px next to the top and bottom outline; per-pixel grain inside the card is 0.3–0.6 of 255 RMS; the body over a white backdrop reads 245 of 255, so white text has about 1.1:1 contrast. The slab's rim is a displaced copy at 1:1, sharper than the body. The reference surface (the SimpMusic mini player, `io.github.kyant0:backdrop`) uses a convex lens whose slope is largest at the outline with amplitude twice the band, a uniform blur, saturation 1.5 and a black scrim of 0.12–0.50 driven by the backdrop's luminance.

Four heads-up properties, all neutral by default. With them unset, neutral or out of range the clear GLSL is byte for byte that of optics 12 at `25acdc7` (`testdata/optics12_golden`):

| Property | Default | Range | Meaning |
|---|---|---|---|
| `debug.oulg.sf.hcfold` | 0 | 0–3 | 0 keeps the slab. Above 0 the heads-up field is the meniscus below; the value is the amplitude over the band |
| `debug.oulg.sf.hcsat` | 1 | 0.9–2 | saturation of the heads-up clear body |
| `debug.oulg.sf.hcscrim` | 0 | 0–0.6 | scrim where the backdrop is black |
| `debug.oulg.sf.hcscrimmax` | 0 | 0–0.8 | scrim where the backdrop is white; raised to `hcscrim` when below it and limited to `hcscrim + (1 − hcscrim) / 2` |

```
x = 1 - depth / band                       band as in section 1 (hcband)
a = min(hcfold * band, cap * min(hs))      cap = max(1 / max(rho, 1) - l0 - l2, 0), rounded down to 4 decimals
s = -R u (l0 + l2 u^2) - n a (1 - sqrt(1 - x^2))      (second term only for 0 < depth < band)
F = rho * s
gain = 1 - (hcscrim + (hcscrimmax - hcscrim) * w)      w = y^2 (3 - 2y), y = Rec.709 luma of the (blurred) backdrop at the pixel, encoded values
sat  = hcsat + (1 - hcsat) * w                         the saturation fades to 1 over highlights
```

- The map folds by design. On a straight edge the source depth `depth + a (1 − sqrt(1 − x²))` falls from `a` at the outline to one minimum at `depth = band (1 − 1 / sqrt(1 + (a / band)²))` and rises after it: outside that depth the rim shows the interior mirrored and compressed, inside it the image is stretched (without bound at the fold line). The σ ≥ 0.25 bound of section 1 does not apply to the meniscus.
- What does hold (`clear_check.c`, M1–M4): every sample stays inside the card and never moves outward (30.8 M points, 108 parameter sets × 10 shapes); the shift and its slope vanish at `depth = band`; the fold depth matches the formula within 0.005 px; the outline samples `a` px inside.
- The tag strength `k` is not used by the meniscus (mode 1 no longer caps the amplitude by `k` when `hcfold > 0`).
- The material is applied to the refracted, blurred backdrop before the edge light, so the light is not dimmed. The limit on `hcscrimmax` keeps the output luma from falling as the backdrop gets brighter (checked over the whole range).
- The blur stays the section-1 curve. A uniform blur is `hcedge = hccore`.
- Starting values for the device, sized on the 1340×374 card (r 108): `hcband 0.42` (45.4 px), `hcfold 2` (a 90.7 px, mirrored rim 25.1 px, stretched zone 20.3 px), `hcedge 24`, `hccore 24`, `hcbody 0`, `hcsat 1.5`, `hcscrim 0.12`, `hcscrimmax 0.5` (backdrop luma 0.25 → 0.205, 0.50 → 0.345, 0.75 → 0.420, white → 0.50). These are arithmetic from the reference's parameters and the measurements above, not device results.
- Registry: the four keys are in the catalogue and `SettingsRegistry` (204 specs, with the keys of sections 13 and 14), `Tier.INTERNAL` read-only rows, in `CLEAR_GLASS_PENDING_INTEGRATION`, not in `SfLive.WRITABLE`.
- Sizes: largest library with meniscus, material and adaptive body 17664 of 32768 bytes; `main()` unchanged.
- Not covered: the lockscreen (profile 1) keeps the slab; `clear_render_check.mjs` (WebGL) has no meniscus case yet, so the GLSL/twin agreement of the meniscus rests on the shared text structure and glslang, not on a rendered comparison.
- Pending on the device: everything visual. In particular the module flips notification text to dark over light backdrops; with a scrim that brings white down to 0.50 the text colour rule may need a different threshold.

## 13. Heads-up adaptive blur and neutral highlights

After the device round with the meniscus: lens, rim and blur level were accepted. What remained is a scale problem. A fixed disc removes detail smaller than itself and leaves structures wider than itself nearly intact (the launcher's clock behind the pop-up), and white content blurred over a blue wallpaper read as a blue-grey white.

| Property | Default | Range | Meaning |
|---|---|---|---|
| `debug.oulg.sf.hcadapt` | 0 | 0–1 | 0: nothing emitted, the body is the single disc as before. Above 0: the largest share of the wide disc a pixel can take |

```
A  = disc(radius br)             the body as before
B  = disc(radius 2.5 br)         same centre (the refracted position), same tap count
sd = weighted standard deviation of the luma of B's taps
body = mix(A, B, hcadapt * smoothstep(0.06, 0.22, sd))
```

- First version, rejected on the device: the weight was `smoothstep(0.06, 0.30, |luma(A) − luma(B)|)` with B at 3 br. A and B cross on both flanks of every stroke; the weight fell to 0 there and the output alternated between the two blurs, which drew doubled contours on the launcher's clock. The host model reproduces it: 5 extrema across one 40 px stroke instead of 1 (3 at the factor 2.5).
- `sd` is a wide-scale statistic: high all around a structure the disc cannot average out, falling off smoothly away from it. The weight has no fine structure of its own. Where the backdrop is flat or its detail is finer than the disc, A and B agree and the result is A whatever the weight.
- Model on the host (`clear_check.c`, a 24 px disc over patterns varying along one axis, with and without a 7.5 px region blur in front of it; 7.5 px is inferred from the 19.3 px edge width of the E0 capture): strokes of 40 and 80 px, a pair of strokes and a step keep the plain disc's count of extrema; the residual of a 40 px stroke is 0.59 of the plain disc's and of an 80 px stroke 0.87; 8 px stripes, and a 6 px line behind the region blur, stay within 0.008 of the plain disc.
- Limits: a structure wider than the wide disc (60 px radius at br 24) is softened at its flanks only. The model is one-dimensional and uses the continuous disc; it does not model the 48 taps. B spreads them over 6.25 times the area (about 15 px between taps at br 24), which can show as grain on the device.
- Cost: the heads-up clear body takes twice the taps while `hcadapt > 0`.
- Neutral highlights (part of the heads-up material, active whenever it is emitted): pixels with luma above 0.55 and `max − min` below 0.45 are pulled toward their own grey, by up to 75 % at luma ≥ 0.90 and chroma ≤ 0.15. Luma is kept, so the scrim's monotone bound is unchanged. Example: (0.80, 0.85, 1.00) keeps luma 0.85 and its chroma falls from 0.20 to about 0.07. Vivid highlights (chroma above 0.45) are not touched.
- The scrim is unchanged. With the clock handled by the blur, a lower `hcscrimmax` darkens near-white areas less.
- Not validated on the device. The WebGL render check has no case for either.

## 14. Lockscreen meniscus, lift and neutral strength (`feat/clear-glass-kg-meniscus`)

The heads-up of `acff51b` was approved on the device (`hcfold 2`, `hcband 0.42`, `hcsat 1.05`, `hcscrim 0`, `hcscrimmax 0`, `hcadapt 1`). Its generated GLSL is frozen in `sfhook/tools/testdata/hu_approved_golden` and checked byte for byte (I3) while the four keys below are unset, neutral or invalid.

| Property | Profile | Default | Range | Meaning |
|---|---|---|---|---|
| `debug.oulg.sf.kcfold` | KG | 0 | 0–3 | 0 keeps the slab. Above 0: the meniscus of section 12 with the lockscreen's own `kcband`, `kcbody`, `kcbody2`, `kcedge`, `kccore`, `kcblurexp` and `kgrefract` |
| `debug.oulg.sf.hclift` | HU | 0 | 0–2 | `c → 1 − (1 − c)^(1 + lift)` per channel, after the scrim. Nothing emitted at 0 |
| `debug.oulg.sf.kclift` | KG | 0 | 0–2 | same curve, after dim and tint, before the edge block. Nothing emitted at 0 |
| `debug.oulg.sf.hcneutral` | HU | 0.75 | 0–1 | strength of the near-white pull of section 13, which was fixed at 0.75. Acts only while the heads-up material is emitted |

- One field text: `OULG_FS_MENISCUS_FIELD_FMT` takes the profile index; with equal parameters the two functions differ in that index only (`clear_check.c`). The cap is `1 / max(refract, 1) − body − body2` per profile. M1–M4 of section 12 run on the twin over refraction scales 0.8–1.2, three domes and three bands, which covers the lockscreen's ranges.
- What `kcfold` changes on the lockscreen: the shift, and through it `oulg_e` (the edge light's sample offset), as on the heads-up. What it does not change: the material, the Fresnel, contour and specular block (their coordinates come from `oulg_shift_base`), the blur curve, the light cases. The test compares the library with `kcfold` 1.5 and 0: only the field function differs.
- In mode 1 the tag strength no longer caps the lockscreen amplitude when `kcfold > 0`, as on the heads-up.
- Lift: black and white stay fixed, every value between rises, no channel can exceed 1, the curve is increasing (checked for lift 0–2). Mid grey 0.50 becomes 0.75 at lift 1 and 0.875 at 2. It moves colours toward white. The lockscreen's existing `kcveil` (≤ 0.2) toward `kclevel` (≤ 0.85) raises mid grey to 0.57 at most and darkens backdrops above the level, which is why `kclift` exists.
- Not changed: `hcadapt` stays heads-up only; profiles 2 and 3; `main()`; the modes; the optics-11 and optics-12 identities.
- `kgsharedoptics`: unchanged and still undecided for clear cards (section 6). The Java edge is drawn over the card whatever the field is.
- Text colour: the module picks it from the backdrop. Scrim, dim and lift change the body's luma without the module knowing; see `UI_HANDOFF.md` section 7.
- Bundle: `sfhook/live/version.prop` goes to v0.29.0 / 30. It had stayed at v0.28.0 / 29 through the Clear Glass commits, which change the library.
- Controls for the UI agent: `docs/clear-glass/UI_HANDOFF.md`.
- Not validated on the device: the lockscreen meniscus, both lifts and any `hcneutral` other than 0.75.
