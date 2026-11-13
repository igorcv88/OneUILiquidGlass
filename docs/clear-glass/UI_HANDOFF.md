# Clear Glass — handoff for the controls UI

Audience: the agent that builds the granular controls in the app. This file lists what the compositor accepts today for the heads-up (HU, profile 0) and the lockscreen (KG, profile 1) Clear Glass variants, what each control depends on, and how values are applied and rolled back. The optics are described in `INTEGRATION_CONTRACT.md`; nothing here needs a change in `sfhook/`.

State of the code: the properties below are read by `sfhook/oulg_sf.c` (`clear_params`), validated by `oulg_fix_clear` (`sfhook/refract.h`), registered in `docs/PROPERTY_CATALOG_2026-10-26.md` and the generated `SettingsRegistry` (204 specs), and accepted by `oulg-live.sh setprops`. When this file was written they were `Tier.INTERNAL` read-only rows outside `SfLive.WRITABLE`. The app now exposes them: the two selectors through the Home "Glass material" choice and the other 29 as controls shown for the matching material, all in `SfLive.WRITABLE` (see `MATERIAL_UI.md` and `MATERIAL_MATRIX.md`).

## 1. How a value takes effect

- Every property is `debug.oulg.sf.<key>` and is read when SurfaceFlinger compiles a fragment program. None of them updates live. A written value is pending until the programs are recompiled, which in practice is the helper's `restart rewrite` (a userspace restart). The only live channel is the per-card tag slice of mode 2 (contract section 3).
- Write path: `oulg-live.sh setprops key=value ...`. All arguments are checked before anything is written; a key outside the allowlist or a non-numeric value rejects the whole call.
- Reported state: `debug.oulg.sf.clear` (`hu:<m>,kg:<m>`) and the `CLEAR_OPTICS` log line, which prints the effective values after validation, including `hcmat=...,lift,neutral` and `kcmen=fold,cap,lift`. A reported mode is what a program was compiled with, not proof that a card was drawn clear.
- Rollback, per parameter: `setprops key=` (empty value) clears the property, and the library uses its default at the next compilation. Rollback of the variant: `huclear=0` / `kgclear=0`; the generated GLSL is then byte for byte the optics-11 text. Out-of-range or non-numeric values that reach the library fall back per parameter to the default in the table; the UI must still validate before writing.
- The UI should show: desired value, pending or applied (compare with the `CLEAR_OPTICS` line or the mode marker), and a single "apply" action that performs the restart, since every change costs one.

## 2. Selection

| Key | Profile | Type | Default | Values | Notes |
|---|---|---|---|---|---|
| `huclear` | HU | int | 0 | 0, 1, 2 | 0 off; 1 every card; 2 per card by tag (needs the `SemBlurBridge` work of contract section 6) |
| `kgclear` | KG | int | 0 | 0, 1, 2 | same; independent of `huclear` |

Every other row below is ignored while its profile's mode is 0.

## 3. Geometry and refraction

| Key (HU / KG) | Default (HU / KG) | Range | Neutral | Meaning |
|---|---|---|---|---|
| `hcband` / `kcband` | 0.42 / 0.42 | 0.42–1 | — | optical band as a share of the corner radius; never below the bevel `clamp(0.42 r, 16, 56)` px |
| `hcfold` / `kcfold` | 2 / 2 | 0–3 | 0 | 0: slab (optics 12). Above 0: meniscus, amplitude over the band. Same field text for both profiles |
| `hcamp` / `kcamp` | 0.40 / 0.40 | 0–0.5 | 0 | slab edge amplitude. Used only while the profile's fold is 0 |
| `hcbody` / `kcbody` | 0.06 / 0.05 | 0–0.15 | 0 | dome, linear term (uniform magnification of the body) |
| `hcbody2` / `kcbody2` | 0 / 0 | 0–0.05 | 0 | dome, cubic term. `body + 3 body2 > 0.45` reverts both to their defaults |
| `hurefract` / `kgrefract` | 1 / 1 | 0–1.2 | — | existing, already live-writable keys: global scale of the whole shift, slab or meniscus |

Limits the library applies on its own (not controls): the slab amplitude is capped at `amax` so that the map never folds (σ ≥ 0.25); the meniscus amplitude is capped at `cap · min(half sizes)` with `cap = 1 / max(refract, 1) − body − body2`, so every sample stays inside the card. A small card therefore gets less than `fold · band`. The effective caps are in the `CLEAR_OPTICS` line.

Dependencies: `hcamp`/`kcamp` are inert when the fold is above 0; in mode 1 the tag strength caps the slab amplitude but not the meniscus.

## 4. Blur

| Key (HU / KG) | Default | Range | Meaning |
|---|---|---|---|
| `hcedge` / `kcedge` | 24 / 24 | 0–48 px | blur radius at the outline. A value above the core is lowered to the core |
| `hccore` / `kccore` | 24 / 24 | 0–48 px | blur radius past the band |
| `hcblurexp` / `kcblurexp` | 2 / 2 | 1–4 | shape of the rise from edge to core |
| `hublur` / `kgblur` | 1 / 1 | existing | multiplier of the radius above |
| `taps` / `kgtaps` | 48 / 48 | 4–48 | existing: samples of the disc; cost is linear in it |
| `hcadapt` | 1 | 0–1 | HU only. Share of a second disc of 2.5 times the radius, taken where the luma deviation inside it shows structure the disc cannot average out. Doubles the samples of the HU body while above 0 |

A uniform blur is edge = core. The baseline keys `core`, `edgeblur`, `ramp`, `kgcore`, `kgedgeblur`, `kgcorecap`, `kgramp`, `kgblurexp` do not act on a clear card.

## 5. Material

Heads-up (the HU has no material in optics 11; this block is emitted only when at least one of `hcsat ≠ 1`, `hcscrimmax > 0`, `hclift > 0` holds):

| Key | Default | Range | Neutral | Meaning |
|---|---|---|---|---|
| `hcsat` | 1.05 | 0.9–2 | 1 | saturation; fades to 1 over highlights |
| `hcscrim` | 0 | 0–0.6 | 0 | darkening where the backdrop is black |
| `hcscrimmax` | 0 | 0–0.8 | 0 | darkening where it is white. Raised to `hcscrim`, capped at `hcscrim + (1 − hcscrim) / 2` |
| `hclift` | 0.30 | 0–2 | 0 | brightening: `c → 1 − (1 − c)^(1 + lift)` per channel, after the scrim |
| `hcneutral` | 0.75 | 0–1 | 0 | strength of the pull of near-white, low-chroma pixels toward their own grey |

`hcneutral` acts only while the block is emitted. To use it alone, the UI must also keep one of the three conditions true (for example `hcsat 1.01`). The configuration approved on the device is `hcsat 1.05`, `hcscrim 0`, `hcscrimmax 0`, `hcadapt 1`, `hclift` unset, `hcneutral` unset.

Lockscreen (replaces the optics-11 body material on a clear card):

| Key | Default | Range | Neutral | Meaning |
|---|---|---|---|---|
| `kcsat` | 1 | 0.9–1.3 | 1 | saturation |
| `kcknee` | 0 | 0–0.6 | 0 | compression of luma above 0.65 |
| `kcveil` | 0.04 | 0–0.2 | 0 | share of the move of luma toward `kclevel` |
| `kclevel` | 0.85 | 0.1–0.85 | — | the level the veil moves to |
| `kcdim` | 0 | 0–0.3 | 0 | uniform darkening |
| `kccool` | 0.50 | 0–2 | 0 | cool tint, luma-preserving |
| `kclift` | 0 | 0–2 | 0 | same curve as `hclift`, after dim and tint, before the edge block |

`kgsat`, `kgtone`, `kgflat`, `kglevel`, `kgcool`, `kgflatcap`, `kgcoredim` do not act on a clear lockscreen card.

Brightness, in words for the UI copy: `scrim` and `dim` darken, `lift` brightens, `veil` pulls toward a fixed grey (it brightens dark backdrops and darkens bright ones). Lift keeps black and white fixed and cannot clip: mid grey 0.50 becomes 0.65 at lift 0.5, 0.75 at 1 and 0.875 at 2. It moves colours toward white, so it lowers saturation as it rises. It does not restore detail removed by the blur. With `kcveil 0.2` and `kclevel 0.85` the lockscreen could already raise mid grey to 0.57 only; `kclift` covers the rest.

## 6. Edge light and structural edge

These are existing keys, already in `SfLive.WRITABLE`, and they keep acting on clear cards:

| Key | Profile | Meaning |
|---|---|---|
| `hulight` | HU | light entering from the outline, by the backdrop there |
| `hubloom` | HU | bright-sample suppression of the disc |
| `kglight`, `kgdepth`, `kgglow` | KG | light from the outline, its reach and glow width |
| `kgrim`, `kgspec`, `kgline`, `kgtilt`, `kgfixedrim` | KG | Fresnel, specular, contour and light tilt. The clear material copies this block verbatim from the baseline text, and neither `kcfold` nor `kclift` touches it (checked in `clear_check.c`) |

## 7. Interactions the UI must account for

- `kgsharedoptics` (module side, default 1): the Java edge shader keeps drawing over the lockscreen card, on top of the compositor's contour. With a clear body there is less veil under it and, with `kcfold > 0`, the rim shows mirrored backdrop. Whether the Java edge should step back for clear cards is an Android-side decision that has not been taken (contract section 6); the compositor does not know about it.
- Text colour: the module chooses notification text colour from the backdrop, not from the compositor's output. `hcscrim`/`hcscrimmax`/`kcdim` make the body darker than the module assumes, `hclift`/`kclift` brighter. At lift 1 a mid-grey backdrop (0.50) is drawn at 0.75. If these controls are exposed, the text rule needs the effective body luma, or the UI must warn; this is not handled in `sfhook/`.
- `hcadapt` and `taps`: the cost of the HU body is `taps × (1 + [hcadapt > 0])`.
- The lockscreen has no adaptive blur and no scrim pair; the heads-up has no veil, level, dim, knee or tint. They are independent parameters by design; nothing forces equal values.

## 8. Constants that stay in the code

Not exposed, because the bounds or the stability of the image depend on them: the bevel `clamp(0.42 r, 16, 56)`; the slab bound σ ≥ 0.25 and the meniscus cap; the tag slice of mode 2 (`k ≥ 1.035`, baseline `k ≤ 0.85`); the adaptive detector's width factor 2.5 and thresholds 0.06–0.22; the neutral pull's windows (luma 0.55–0.90, chroma 0.15–0.45); the scrim weight `y² (3 − 2y)` and its monotone cap; the knee's start at 0.65; the disc's sample pattern and weights.

## 9. Validation the UI can rely on

`sfhook/tools/check.sh` proves, for every commit: optics-11 byte identity with Clear Glass off; optics-12 byte identity with the meniscus and material off; byte identity of the heads-up approved on the device (commit `acff51b`, two parameter sets, five mode pairs, debug 0 and 3) with `kcfold`, `hclift`, `kclift`, `hcneutral` unset, at their neutral values or invalid; compilation and linking of every mode pair with all parameters set. `ClearGlassRegistryTest` checks that each registry range equals the library's.

None of the lockscreen meniscus or lift values has been seen on the device.
