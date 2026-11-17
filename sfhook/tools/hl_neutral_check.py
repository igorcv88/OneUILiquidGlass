#!/usr/bin/env python3
"""Highlight neutrality (sf.hchlneutral; docs/clear-glass/HU_HIGHLIGHT_NEUTRALITY.md), generated GLSL:
 - unset, 0, negative, above 1 and NaN emit the same text as before the control existed;
 - with the adaptive blur off it emits nothing (its selection reads the body's luma deviation);
 - above 0 the text adds exactly the oulg_sd global, the neutrality block before the lift and the
   oulg_sd assignment in oulg_body_hu, for kernels 0-3; the program compiles and links;
 - the strength is the literal emitted (linear control).
usage: python3 -I hl_neutral_check.py <refract_check> <dump>"""
import difflib, subprocess, sys, tempfile
from pathlib import Path
rc, dump = sys.argv[1], sys.argv[2]
BASE = "huclear=1,hcsat=1.03,hclift=0.42,hcneutral=0.85"
fails = []


def gen(args, debug=0):
    out = Path(tempfile.mkdtemp())
    subprocess.run([rc, dump, str(out), str(debug), args], check=True, capture_output=True)
    return out, {p.name: p.read_text() for p in sorted(out.glob("mod_*"))}


for k in (0, 1, 2, 3):
    _, ref = gen(f"{BASE},hckernel={k}")
    for v in ("0", "-1", "1.5", "nan"):
        _, got = gen(f"{BASE},hckernel={k},hchlneutral={v}")
        if got != ref: fails.append(f"kernel {k} hchlneutral={v}: text differs from unset")
    _, off_ref = gen(f"{BASE},hckernel={k},hcadapt=0")
    _, off = gen(f"{BASE},hckernel={k},hcadapt=0,hchlneutral=0.6")
    if off != off_ref: fails.append(f"kernel {k}: emitted with the adaptive blur off")
    out, on = gen(f"{BASE},hckernel={k},hchlneutral=0.6")
    for name in ref:
        if not name.endswith(".frag") or "oulg_body_hu" not in ref[name]: continue
        added = [l for l in difflib.ndiff(ref[name].splitlines(), on[name].splitlines()) if l.startswith("+ ")]
        removed = [l for l in difflib.ndiff(ref[name].splitlines(), on[name].splitlines()) if l.startswith("- ")]
        want = ["+ highp float oulg_sd = 0.0;", "+   oulg_sd = sd;"]
        if removed or not all(w in added for w in want) or len(added) != 8 or not any("0.600 * smoothstep(0.25, 0.70, yh)" in l for l in added):
            fails.append(f"kernel {k} {name}: unexpected diff {added[:3]}... removed {removed[:2]}")
        lift_at = on[name].find("rgb = 1.0 - pow(1.0 - clamp(rgb"); hl_at = on[name].find("smoothstep(0.10, 0.22, oulg_sd)")
        if not (0 < hl_at < lift_at): fails.append(f"kernel {k} {name}: neutrality is not before the lift")
        p = out / name
        if subprocess.run(["glslangValidator", "-S", "frag", str(p)], capture_output=True).returncode: fails.append(f"kernel {k} {name}: does not compile")
        if "voulg_tag" in on[name] and subprocess.run(["glslangValidator", "-l", str(out / "mod_001.vert"), str(p)], capture_output=True).returncode:
            fails.append(f"kernel {k} {name}: does not link")
if fails: print("FAIL: " + "; ".join(fails)); sys.exit(1)
print("highlight neutrality: identical text at 0/invalid and without adaptive blur; above 0 only the oulg_sd global, the block before the lift and the body's oulg_sd, kernels 0-3; compiles and links")
