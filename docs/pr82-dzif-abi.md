# PR 82: DZIF Compose draw ABI and follow-ups

The source is the device collection `OULG_PR81_20261028_184910` (S938BXXUCDZIF, SystemUI pid 1563, 28/10 18:49). The screenshots in it come from the build merged in #81. **Nothing in this PR has been run on the device yet.**

## Causes and fixes

| Device evidence | Cause | Fix |
| --- | --- | --- |
| `QS_EDGE_INSTALL_FAILED … BackgroundNode.draw candidates=[void(androidx.compose.ui.node.LayoutNodeDrawScope)]`; `QS_OPTICS_COVERAGE drawn=0`; `QS_EDGE_STATUS ready=false nodesActive=0` | R8 narrowed `draw(ContentDrawScope)` to `draw(LayoutNodeDrawScope)`, its only implementation. `QsNodeAbi` accepted only the scope itself or a supertype of it. The whole edge/optics install aborted, so the AGSL was never drawn. | `QsNodeAbi.drawScope` also accepts a concrete class implementing the scope. Priority: exact scope, then the narrowed scope, then a supertype; a bridge method loses a tie. `Object` and unrelated types are still rejected. |
| `QS_TEXT_INCOMPATIBLE` for `TextStringSimpleNode` and `TextAnnotatedStringNode`; `QS_TEXT_STATUS ready=false families=0` | Same narrowed `draw`. | Same resolver. |
| `QS_HEADER_ICONS_INCOMPATIBLE … PainterNode.draw` | Same narrowed `draw`. | Same resolver. |
| `BACKDROP_EVENTS_MISSING error=NoSuchMethodException` | `TaskStackChangeListeners.getInstance()` or `registerTaskStackListener` is not present under that name. The log did not say which. | Lookup now also tries a static no-argument method or a static field of the registry's own type, and a single `register*` method taking the listener. A failure logs the message and the candidates. If the registry exists only through Dagger with no static handle, this path still reports missing and sampling stays periodic. |
| `LayoutNodeDrawScope.drawContent` (optics boundary) | Its presence is unproven on DZIF. | Preferred: optical draw between Samsung's background and its content. When the boundary is absent, rejects a hook, or is not reached in a frame, draw the AGSL **after** `BackgroundNode.draw`, clipped to the inside bevel ring so Samsung cannot cover it and the late draw cannot wash over central text/artwork. `QS_HOOK_STATUS family=optics step=drawContent|after-node-rim` and `QS_OPTICS_DRAWN layer=…` distinguish the paths. The media inset still needs the boundary. |
| `QS_SLIDER_STATUS fill=0.78`: the fill sits about 11 % of the width away from each edge | Default `qs.fill` was 0.78. | Default is now **0.85**: about 7.5 % margin each side, as requested. A stored override is still respected. |
| `progress=0.0` still showed a disc | `sliderPrimitive` grew the body to at least one diameter. | The body is exactly the native interval: empty at 0 %, and shorter than one diameter below that, with a rounded end (radius = half the shorter side). The thumb cap is never longer than the body and is empty with an empty body. The empty body is still recorded so the thumb does not fall back to the native centre. Value, touch and accessibility stay native. |
| The visible notification-shade clock could not be identified (`clockVerified=false`) | The class of a node does not say which one is the clock. | A header text node whose Canvas text run has the shape of a time of day (`18:44`, `6:44 PM`) is marked `role=header-clock clockVerified=true` in `QS_HEADER_TEXT_DRAWN`. Only the shape is tested; no text is kept or logged. |
| Pop-up colour transition reported as abrupt; no `POPUP_CONTRAST` for that moment in the collection | Not proven. One concrete change since #79: `flushPendingSwap` moved to the ramp start (#80) and took the glass light style (outline, thickness shadow) with it. The light style then jumped ahead of the 180 ms veil ramp. | The text still changes at the ramp start; on later decisions it is animated by `InkAnimator`. The light style is back at the ramp midpoint, or applied at once when there is no visible ramp. New `POPUP_TRANSITION first= veilRamp= rampMs= text=immediate|animated|unchanged lightStyle=now|midpoint` logs every pop-up tone change. |

## Not changed

- Shader parameters, intensities and SF profile 3: nothing can be judged until the optics are actually drawn.
- Drag suppression logic: kept. The collection had `QS_EDGE_SUPPRESS nodes=0`, which proves nothing, because there were no nodes.
- `METHOD_MISSING` / `CLASS_MISSING` for heads-up probes (`setHeadsUp`, `BaseHeadsUpManager`, …): these are optional compatibility probes. The collection shows no regression from them.

## Local validation

`./gradlew cleanTestDebugUnitTest testDebugUnitTest lintDebug assembleDebug`. The tests use fixtures with the exact DZIF shape (`draw(LayoutNodeDrawScope)`, where `LayoutNodeDrawScope implements ContentDrawScope`) for the four failing families. They are not the device's DEX.

## Device acceptance (pending)

1. Install: `QS_EDGE_READY`, `QS_HOOK_STATUS family=optics step=drawContent|after-node-rim`, `QS_TEXT_STATUS ready=true families=2`, `QS_HEADER_ICONS_READY`, `BACKDROP_EVENTS_READY` or a `BACKDROP_EVENTS_MISSING message=…` that names the candidates.
2. QS open, then stabilized: `QS_OPTICS_COVERAGE … drawn>0 rimWithoutOptics=0`, with every role present. Compare the capsule material with a notification on the same backdrop.
3. QS over white: labels dark; tile icons and discs native; header clock and actions dark.
4. Notification shade over white: `QS_HEADER_TEXT_DRAWN role=header-clock clockVerified=true ink=dark`, and the clock pixels dark.
5. Brightness/volume at 0, 1, 50 and 100 %: nothing at 0 %, no residual disc, `QS_SLIDER_STATUS fill=0.85` when there is no profile override, margins about 7.5 %.
6. Hold a slider over white, colour and black with the optics active: `QS_EDGE_SUPPRESS released>0`, no ghost, and `QS_EDGE_RESTORE` on release.
7. Heads-up switching dark ↔ light apps: `POPUP_TRANSITION` lines; a later change shows `first=false veilRamp=true text=animated lightStyle=midpoint`.
