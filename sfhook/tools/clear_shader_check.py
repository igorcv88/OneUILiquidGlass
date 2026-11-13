#!/usr/bin/env python3
"""Generated-GLSL checks of the Clear Glass variant (OPTICS_SPEC.md 9.3, 13).

usage: clear_shader_check.py <refract_check binary> <dump> <work dir>

 - I1: with Clear Glass off (no arguments, or modes 0 with clear parameters set), refract_check's
   output for debug 0, 1 and 2 is byte for byte the optics-11 output of db20a8f (hashes in
   testdata/optics11_golden, generated from that commit);
 - every clear mode pair (HU, KG in {0, 1, 2}) x debug 0-4 rewrites the same programs, and each
   fragment rewrite compiles and links with glslangValidator (vertex rewrite and its zero fallback);
 - the main() block is unchanged by Clear Glass (byte-equal suffix from "void main() {"), and its
   size keeps >= 25 % headroom in the fixed 2048-byte buffer;
 - mode isolation in the text: an off profile emits no clear function; mode 2 compiles the tag test.
"""
import hashlib
import itertools
import re
import subprocess
import sys
from pathlib import Path

rc, dump, work = sys.argv[1], sys.argv[2], Path(sys.argv[3])
golden = Path(__file__).resolve().parent / "testdata" / "optics11_golden"
fails = []


def gen(name, debug, args=""):
    out = work / name
    out.mkdir(parents=True, exist_ok=True)
    cmd = [rc, dump, str(out), str(debug)] + ([args] if args else [])
    line = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()
    if line != "total=7 vertex=1 fragment=5":
        fails.append(f"{name}: {line}")
    return out


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


# I1: byte identity with optics 11.
for debug in (0, 1, 2):
    want = dict(reversed(l.split()) for l in (golden / f"debug_{debug}.sha256").read_text().split("\n") if l.strip())
    for name, args in (("off", ""), ("off_params", "huclear=0,kgclear=0,hcband=0.9,hcamp=0.2,hccore=12,kcveil=0,kcdim=0.2,kcsat=1.2,kccore=8")):
        out = gen(f"i1_{name}_{debug}", debug, args)
        got = {p.name: sha(p) for p in out.glob("mod_*")}
        if got != want:
            fails.append(f"I1 debug {debug} ({name}): " + ", ".join(sorted(k for k in set(got) | set(want) if got.get(k) != want.get(k))))
print("I1: Clear Glass off is byte-identical to optics 11 (db20a8f) in debug 0, 1, 2, with and without clear parameters set"
      if not any(f.startswith("I1") for f in fails) else "I1: FAILED")


def suffix(text):
    return text[text.index("void main() {"):]


def block(text):
    """The main() block the rewrite inserts (fill: after 'void main() {'; clip: after sk_FragCoord)."""
    end = text.index("oulg_eo = ", text.index("void main() {"))
    end = text.index("}\n", end) + 2
    start = text.index("void main() {\n") + len("void main() {\n")
    if "oulg_rph" in text:
        fc = text.index("sk_FragCoord = vec4(")
        start = text.index("\n", fc) + 1
    return text[start:end]


ref = {d: gen(f"ref_{d}", d) for d in (0, 1)}
largest_block = 0
compiled = 0
for hu, kg in itertools.product((0, 1, 2), repeat=2):
    if hu == kg == 0:
        continue
    for debug in range(5):
        out = gen(f"m{hu}{kg}_d{debug}", debug, f"huclear={hu},kgclear={kg}")
        base = ref[1 if debug == 1 else 0]
        for frag in sorted(out.glob("mod_*.frag")):
            text = frag.read_text()
            if suffix(text) != suffix((base / frag.name).read_text()):
                fails.append(f"main() changed: hu {hu} kg {kg} debug {debug} {frag.name}")
            external = "samplerExternalOES" in text
            if ("oulg_clear0(" in text) != bool(hu) or ("oulg_clear1(" in text) != bool(kg):
                fails.append(f"clear field emitted for an off profile: hu {hu} kg {kg} {frag.name}")
            if ("oulg_material_clear" in text) != bool(kg and not external):
                fails.append(f"clear material presence wrong: hu {hu} kg {kg} {frag.name}")
            if ("k >= 1.035" in text) != (2 in (hu, kg)):
                fails.append(f"mode-2 tag test presence wrong: hu {hu} kg {kg} {frag.name}")
            r = subprocess.run(["glslangValidator", "-S", "frag", str(frag)], capture_output=True, text=True)
            if r.returncode:
                fails.append(f"compile {frag}: {r.stdout[-400:]}")
            if "voulg_tag" in text:
                for vert in ("mod_001.vert", "min_001.vert"):
                    r = subprocess.run(["glslangValidator", "-l", str(out / vert), str(frag)], capture_output=True, text=True)
                    if r.returncode:
                        fails.append(f"link {vert} + {frag}: {r.stdout[-400:]}")
            compiled += 1
for d in (0, 1):
    for frag in ref[d].glob("mod_*.frag"):
        text = frag.read_text()
        if "oulg_eo = " in text:
            largest_block = max(largest_block, len(block(text).encode()))
if largest_block * 4 > 2048 * 3:
    fails.append(f"main() block {largest_block} bytes: less than 25 % headroom in 2048")
print(f"modes: {compiled} clear fragment rewrites (8 mode pairs x debug 0-4) compile and link; main() unchanged; "
      f"largest main() block {largest_block} of 2048 bytes; off profiles emit no clear code")
if fails:
    print("\n".join("FAIL: " + f for f in fails[:40]))
    sys.exit(1)
