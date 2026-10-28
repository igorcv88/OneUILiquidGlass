# PR 81: DZIF review follow-up

The supplied screenshots are **before PR 81**. They are not validation of this patch. The reference image specifies the material, not a request to rearrange Samsung's tile layout. Do not merge on CI alone.

## Implemented behavior

- Full Compose rim is the default for every capsule. The association border ↔ background is the BoxWithOutline restart scope: both elements of one box are built inside it, and it is stable across recompositions. Only a box whose own background node reported an AGSL `drawn` result gets the attenuated rim (40 %), by recomposing that scope alone (`QsLitRims`). A later failure of that box (`no-hardware-canvas`, `shader-or-size`, `geometry`, `drawContent-not-reached`) restores its full rim and is not retried in the same panel session. Shader availability is never evidence. A process-wide shader failure clears every box. `QS_OPTICS_COVERAGE` reports `rimAttenuated` and `rimWithoutOptics`; the latter must be 0 at `stabilized`. `QS_RIM_OWNER` (trace gate) logs each box's state change.
- Slider drag: the AGSL path now honours the same suppression as the compositor path. Non-slider capsules skip the optics while suppressed and their nodes are draw-invalidated at drag start, so cached display lists drop the light. The skip is not a failure and does not change the rim state. Slider containers keep their optics: there is no per-slider identity for the dragged bar, so the other slider (faded by Samsung's layer alpha) still draws AGSL inside its faded layer.
- Background elements have process-local tokens and roles (`slider`, `container`, `media-active`, `media-empty`, `unknown`). Selection, registration, binding and actual AGSL result are distinct. Repeated updates of the same element retain ownership.
- AGSL runs at the exact background scope's `LayoutNodeDrawScope.drawContent` boundary, after background and before descendants. An unavailable boundary leaves the full rim. Compositor failure no longer disables APK optics.
- Zero fade, hidden host, unreadable expansion and detach release compositor drawables. Draw refuses recreation at zero fade. Reopening invalidates background nodes. Delayed snapshots check host generation and session.
- Slider installation requires track, fill, thumb and caller. Missing track is an incompatibility, not `READY track=native`. Status records executed primitives and progress buckets. Slider-scoped interaction sources handle press/drag/release/cancel; the existing length heuristic remains a fallback. The firmware must prove that those sources are captured.
- Known QS/header contrast decisions apply immediately, without a 120 ms unsafe intermediate grey. Capture cadence and weak-reading hysteresis remain unchanged.
- Text evidence comes after a successful Canvas text primitive and includes node/family, paint before/after and Canvas owner. A header node is explicitly `clockVerified=false`: neither its class nor the event alone identifies the visible clock.
- Active/empty media parent content gets the same centered 6 dp minimum inset. Artwork-only scaling and its duplicate solid stroke are removed. Drawing is scaled, native hitboxes remain unchanged; verify each control near its edges.
- New native profile-3 default gives AGSL responsibility for light and SF responsibility for refraction (`qslight` default zero). Explicit existing `qslight` overrides still apply and are reported in native diagnostics; the new native library must be installed for this default to change. Other profiles are unchanged.

## Evidence and limitations

`QS_OPTICS_COVERAGE` is bounded session candidate accounting, not a screenshot-visible capsule census. `evicted>0` invalidates completeness. Recomposition can select replacement candidates. `container` does not distinguish a tile from a group. `QS_SLIDER_STATUS role=other` deliberately does not guess brightness versus volume from geometry.

Detailed optical paths, actual AGSL draws, text paths and capture/commit timestamps use the existing `debug.oulg.trace` diagnostic gate. Coverage and transition summaries remain enabled. No notification text or artwork is logged. The first representative per role emits path transitions; the native hook logs at most eight profile capability compilations.

`QS_SF_PROFILE3 scope=shader-capability` counts recognized rewritten **programs containing profile 3**, not runtime tag selections or regions. It reports PID/start ticks/GOT/compile result. `drawn=unmeasured` is intentional: glShaderSource cannot observe uniform tag values or compositor draw calls. Correlate with `/data/misc/surfaceflinger/oulg_health`, Java region events and an A/B capture; do not restart SurfaceFlinger or clear shader caches automatically.

Local checks: distribution contracts, live helper, property parser/profile isolation, native field math and health tests. Android tests/lint/APK require CI because this workspace cannot download Gradle through the JVM. CI results must be checked on the final commit.

## Device acceptance (pending)

1. Verify APK commit and native bundle, DZIF firmware, no new AndroidRuntime crash.
2. Dark, white and colored backgrounds: all selected capsule roles must draw or expose fallback; `rimWithoutOptics=0`; a capsule with full rim must correspond to a fallback in its `QS_OPTICS_PATH`; inspect directional contour and transparency against a notification.
3. Brightness/volume at 0, >0, 50 and 100%, then RTL and drag/hold/cancel: all three stages, no milky track, centered margins, native interaction/accessibility.
4. Hold a slider over a white backdrop: no light or outline ghost on the faded capsules; release restores them. Close/partial close/reopen/root replacement: `QS_EDGE_EXIT regionsAfter=0 alphaPositiveAfter=0`, no launcher ghost. Confirm interaction-source hook execution, not just installation.
5. Compose notification header and QS header separately: final pixels dark over white, normal icons/discs preserved. Identify the visible clock in the composition tree if `clockVerified=false` remains unresolved.
6. Active media with/without artwork and empty media: parent inset and single outer border; check play/pause/skip hitboxes.
7. Record 60/120 Hz backdrop switches with timestamps; inspect capture→commit→paint latency and measure frame cost and node/region counts. No claim of 1 ms latency or visual approval is made by this patch.
