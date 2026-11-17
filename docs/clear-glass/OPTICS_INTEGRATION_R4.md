# Optical integration R4 — physical validation only, 2026-11-17

Integrates #202 (QS geometry), #203 (diagnostic route, default unchanged), #204 (highlight suppression applicability), #205 (entry fade cut, default inherits shade), #206 (composition probe), #207 (highp accumulators, mandatory baseline), #208 (opt-in highlight neutrality) and #210 (live CARD line gain and radius). No change to main, no dispersion algorithm, no global UI refactor (#209).

Integration-specific fixes:
- hubloom stays usable when adaptive blur is off even if a Gaussian ordinal is selected.
- the new-only highlight neutrality material block uses zero legacy strength if legacy material would not previously have run. Existing legacy behavior stays unchanged when saturation, scrim or lift already emit its block.
- version v0.36.0/37, distinct from both independent bundles. Never install an older native bundle over #207.

Validation: install only the signed integrated APK once its full CI succeeds; keep old #207 APK for rollback. Preserve the current kernel and all other settings (30/32, hublur 1.15, hurefract 1.09, hcneutral 1.0, hclift .42, hcsat 1.03 as last recorded). Record actual values first rather than overwrite changed user preferences.

1. Optional quantitative confirmation before changing highlight neutrality: new control 0, composition probe 5, apply native bundle, capture original 1440x3120 PNGs (Home clock and app preview) in ZIP; compare tile centers to R4, alpha deficit should disappear. Preserve logs/dump. Probe 5 is a diagnostic, do not ship it enabled.
2. Disable probe, set new highlight neutrality to .60, keep legacy value and all other settings fixed; apply. Compare against #207 reference. If this is not a controlled same-scene comparison, use a baseline at 0 in a separate application only if needed. Keep new control 0 if not satisfied. No need to restart for Java-only tests below.
3. QS vertical/landscape and rotation, Wi-Fi/Bluetooth, sliders, player. Trace geometry only if duplicate contour persists. Entry: compare inherited fade cut with .25/.10, test exit/gesture/stacking. Live CARD: correlate LIVE_RENDER_DRAW and GLASS_APPLIED with Gradus popup, verify actual owner before attributing results to #210. Check actions, bar progress, capsule and Now Bar.
4. UI: hubloom should remain available for Frosted, Clear Original, and adaptive-off Clear; unavailable for Clear Gaussian + adaptive on. New neutrality needs adaptive on. Legacy availability remains based on original material block. Confirm QuickStar, SDR/P3/HDR, standard notifications, no crashes.

Physical results remain unverified on this integration. The ownership and origin of a second outer contour are not established by the geometry patch; #210 does not promise complete parity for REGIONLESS or P3 mode 9. No merge without authorization.
