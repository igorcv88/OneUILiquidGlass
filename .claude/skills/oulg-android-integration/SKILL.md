---
name: oulg-android-integration
description: Use when integrating OneUILiquidGlass Android materials, notification hooks, preferences, compositor capabilities, applied-state reporting, fallback, or restoration behavior.
---

# OULG Android integration

Use for Android integration and regression review, including future optical features. Do not redesign the compositor field without evidence of a defect and a separately agreed scope.

Read [the integration procedure](references/integration-procedure.md) for code ownership, state handling and validation. For Clear Glass, first read `ClearGlass_Handoff/OPTICS_SPEC.md`, `LAUDO_TECNICO.md`, both `PROMPT_02_OPUS_5_5.md` and `PROMPT_03_GPT_6_1_SOL.md`, and inspect the numerical diagnostic and visual references. All these filenames are inside `ClearGlass_Handoff`, resolved from the repository root; if absent in a separate worktree, read `C:/Users/igorv/OneUILiquidGlass/ClearGlass_Handoff/` without writing to it. On another machine locate the supplied package; stop Clear Glass integration if missing.

Read the actual compositor diff and `docs/clear-glass/INTEGRATION_CONTRACT.md` supplied by the compositor implementer. That contract is a future prerequisite, not a file guaranteed to exist at the studied base `b5d7426`. If missing, stop integration and report the contract blocker; do not invent properties, version support or compiled-state acknowledgments. Work on the agreed compositor commit for a stacked integration PR when requested.

## Non-negotiable behavior

- Never announce a preference change as applied to the compositor before real confirmation. Saved, property-written, compiled, effective and pending-recompilation states are distinct. Unknown confirmation stays unknown/pending.
- Keep pop-up and lockscreen independently opt-in, baseline by default; preserve existing materials when off. Unsupported or absent compositor capabilities must keep the existing safe backend and native restoration paths.
- Establish one owner each for refraction and illumination per surface. Do not layer an AGSL capture lens or duplicate structural edge over confirmed compositor ownership.
- Preserve ordinary and custom notification content, color/contrast handling, transitions, P3/HDR and existing unrelated surfaces.

Stop on missing/mismatched contract, uncertain capability, false applied-state reporting, ambiguous ownership, restoration failure or regression. Report exact evidence and leave unsupported activation unavailable instead of pretending success.

Do not modify root, boot, AVB, KernelSU, integrity settings or device security policies. Never restart SurfaceFlinger automatically. Device installs, payload delivery and any controlled recompilation/restart require explicit user scope and a recovery plan; this skill grants none.
