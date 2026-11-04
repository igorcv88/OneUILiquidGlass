# Live surface composition after the DZIF render trace

The device trace on 2026-11-03 showed three different failures: CARD switched region ownership on animation geometry changes; TOAST retained a controller wrapper that was no longer the View background; NOWBAR drew its own region inside a CardView that still carried Samsung's blur.

## Changes

- CARD keeps its own region throughout expansion and the settled state. Native ancestor blur drawables are suspended via alpha, with shared leases and restoration on replacement, failure, detach and gate changes. Native radius/curve/shape are not rewritten.
- NOWBAR uses the same suspension for its CardView background. Companions are reconciled at pre-draw, including Samsung rewrites. A dedicated gradient without a readable flag is hidden through reversible View visibility; native flag writes remain allowed in that case.
- Classified hosts reconcile a lost wrapper before drawing. Unknown Samsung blur backgrounds displaced by the wrapper are saved for restoration. Unhooked replacement attempts are bounded to three per attachment, then the host stays native.
- Owned live regions use the existing compositor material configuration with explicit keyguard routing for NOWBAR. Their optics reuse GlassDrawable's existing edge shader and uniforms. No new fill is added, and LiveSurface tint fractions are unchanged. This does not claim that the popup's SemBlurBridge and the compositor path are identical.

The previously validated BlurCurve signature resolution is unchanged. No SurfaceFlinger code or workflows are modified.

## Regression coverage and validation

Regressions cover stable region identity through changing native geometry/radius/visibility, failure restoration, late native regions, toast background replacement and bounded fallback, Now Bar background replacement and gradient restoration, and shared native-region leases.

Local Java syntax parsing and `git diff --check` pass. Android type checking, Robolectric, lint and APK assembly were not run: this environment has no Android SDK/Gradle dependencies, and the dependency endpoint was unreachable through its network proxy. Run the existing manual release workflow against the PR commit before merging. A passing build does not establish physical rendering success; verify expanded CARD, Now Bar and TOAST on the Galaxy DZIF, including exit, re-entry and disabling the effect.
