---
name: oulg-optics
description: Use when implementing, reviewing, or validating OneUILiquidGlass compositor optics, generated GLSL ES, refraction fields, material transmission, blur, or edge lighting.
---

# OULG optics

Use for OULG optical work, including future materials beyond Clear Glass. This skill does not authorize implementation, deployment, device changes, or broader baseline fixes by itself.

## Establish the actual baseline

Read [the optics procedure](references/optics-procedure.md) for architecture, evidence and tests. Resolve the handoff before designing Clear Glass: read `ClearGlass_Handoff/OPTICS_SPEC.md`, `LAUDO_TECNICO.md`, `PROMPT_02_OPUS_5_5.md`, `PROMPT_03_GPT_6_1_SOL.md`, and inspect `diagnostics/optics_spec_check.py` plus the visual references under that directory. All these filenames are inside `ClearGlass_Handoff`; resolve that directory from the repository root. If absent in an isolated worktree, read the original checkout at `C:/Users/igorv/OneUILiquidGlass/ClearGlass_Handoff/` without writing to it. For another machine, locate the supplied handoff; stop Clear Glass design if unavailable. Do not substitute this summary for the specification.

Fable studied `b5d7426` after PR #180. Record the current base SHA and reconcile differences against real source before trusting line numbers or defaults. Treat the math specification as the Clear Glass authority; older prompts, Kyant, SimpMusic and LiquidGlassSkill are references, not overrides. For unrelated future optics work, use the current task's specification and record which Fable conclusions still apply.

## Work and evidence

1. Trace the requested surface from radius tagging to shader rewrite and output composition. Identify the owner of refraction, structural edge and dynamic light separately.
2. Separate code observations, mathematical derivations, Python simulations and device hypotheses in every decision. Never turn numeric simulation or generated-shader success into physical acceptance.
3. Keep experiments isolated from existing profiles and knobs. For Clear Glass, mode 0 must generate byte-identical baseline GLSL; profiles 2/3 remain unchanged. Proposed flags, version numbers and tuning values in Fable are not an implemented API or approved production defaults.
4. Validate generated source, field invariants, rendering and cost using the procedure. Report exact commands, baseline/new results, unsupported paths and remaining physical checks.

Stop on missing source/specification, ambiguous layer ownership, unsafe tag decoding, unexpected baseline differences, invalid Jacobian bounds, insufficient shader-buffer headroom, or unverified capability requirements. Diagnose and report; do not hide failures with fallback screenshots or fix unrelated baseline behavior.

Never modify root, boot, AVB, KernelSU, integrity configuration or device security policy. Never restart SurfaceFlinger automatically or deploy a payload as part of an optics check. Physical installation/recompilation requires the user's explicit scope and recovery plan.
