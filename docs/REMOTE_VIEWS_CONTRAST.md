# Contrast on custom notification content

The material decision remains in HeadsUpHooks. Foreground receives the committed swap decision, the source theme polarity and the card's existing InkAnimator timeline. It walks notification content in either layout and skips independently owned child cards. Text keeps its existing transparent-ancestor policy. A stateful tint on custom text stays native. The generic control route checks the same glass ancestry before taking a lease.

| Content | Evidence required | Result |
| --- | --- | --- |
| Framework text, glyphs, chevrons and framework progress | Existing template rules | Existing paths, including the framework progress tint policy |
| Custom text | TextView on clear ancestry with a non-stateful text palette | Existing text tone and interpolation |
| Custom progress | Platform ProgressBar, known neutral shape/layers, explicit non-stateful neutral SRC_IN tint | Mirror each eligible channel separately and preserve alpha, mode, value and geometry |
| Custom action | Click-bound platform ImageButton with vector artwork and an explicit neutral, non-stateful SRC_IN tint | Mirror the publisher's monochrome tint, with the card's existing timeline |
| Other images, badges, photos, application and media art | No verified action/foreground channel | Native |
| Arbitrary FrameLayout fills, gradients, bitmap, Canvas and unknown drawable subtypes | Visual role or layer coverage cannot be established | Native |
| Private backdrop, conflicting ownership, unsupported or missing tint | Glass or publisher intent cannot be established | Native |

For the newly supported controls, only the tint list is written. A weak key and weak drawable identity prevent a cached lease from retaining the View via a drawable callback. The slot also records blend mode and the exact original and applied lists. A new drawable, tint or blend mode invalidates the old lease. A publisher write that supplied its own list wins even if it has the same RGB. Detach defers restoration to the next main-queue callback. A same-message reattach keeps the current tint only with the same notification host, root view and window token, a clear glass path and unchanged publisher properties. A different host releases before its next draw. Pending cleanup retains only weak view references and restores only tint lists still owned by the lease. Android OEM builds that copy a tint list instead of returning the supplied instance need validation on hardware.

The normal swapped-card pre-draw checks a fixed number of identities per leased control, with no drawable traversal, bitmap work or reflection added to that check. Classification runs during the existing foreground walk. A notification update marks the walk dirty for the next foreground reconciliation. Drawable inspection is bounded by depth and layer count. Only a changed animation frame writes a new tint. The existing periodic foreground walk remains a backstop for newly inserted controls. Trace emits at most 120 distinct visible-node entries per five-second window. Each entry includes the view identity, resource entry, class, size, drawable type, material, glass-path result, bounded ancestry and preservation reason. It excludes notification text, descriptions, image data and notification identifiers. An equal tint with a changed reference is reported as an observation, which can also mean a publisher rewrite. Robolectric checks the progress and image tint getter/setter identity contract. Samsung's implementation still needs a device check.

## Limits of structural classification

The current structural recognizers cover the exact platform ProgressBar and ImageButton classes. Subclasses may override painting or tint behavior and require evidence of their rendering contract. A live hierarchy can show whether an unclassified node exposes a standard control or contains separate track, fill and marker nodes. The hierarchy alone cannot establish that an arbitrary solid fill, vector or bitmap is semantic progress rather than decoration. Such graphics keep their native appearance unless a reliable signal establishes their visual role.

## Uber evidence and compatibility

The compiled XML in the resource capture contains empty FrameLayout slots for progress in collapsed A (0x7f0b1c3e) and expanded (0x7f0b1c47). Collapsed B has no progress slot. The layouts do not declare a ProgressBar or the dynamic children. The late runtime capture shows the expanded slots only. The filled shape was mirrored, the #727272 unfilled track was preserved and the indicator and endpoint lacked eligible paint. The screenshots establish a contrast discrepancy but do not identify the runtime paint or roles in collapsed A.

The existing expanded adapter remains confined to its verified roles. This retains the expanded behavior while the generic route handles supported controls in any notification. PR #215 is a separate proposal based on the static slot plus an assumption about its dynamic descendants. Extending an ID test or treating solid rectangles as progress would not provide a reliable generic contract. A compositor transfer or broad Drawable/Canvas filter would also modify publisher artwork where shapes and photos share the same pixels. A structural progress adapter can replace the remaining fallback only if a reliable runtime signal for the publisher's progress semantics is found.

## Device acceptance

Install the signed CI APK, activate its SystemUI module and use the standard SystemUI restart procedure. With an active Uber ride, compare collapsed and expanded cards on light and dark backgrounds. Confirm the expanded adaptation still works. If the collapsed progress graphics remain native, capture a read-only runtime tree during the active ride to see whether a typed, eligible role exists. Check a different publisher's native ProgressBar or vector action in shade and heads-up, then exercise rebind and repost. On a Live Update and the shade media card, verify the same media contour, attach/reattach behavior and absence of a flash.

For a before/after performance comparison, use the same wallpaper, notification set and interaction sequence. With tracing disabled, compare SystemUI frame timing. In a separate traced run compare reapplication reasons and PERF pre-draw counters. Look for repeated repair on idle cards and a flash at replace-child or same-root reattach. Hardware measurements and the Uber collapsed outcome require device validation. Roll back by reinstalling the previous signed baseline APK and restarting SystemUI.
