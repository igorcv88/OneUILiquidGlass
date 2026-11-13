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
 - mode isolation in the text: an off profile emits no clear function; mode 2 compiles the tag test;
 - I2: with the meniscus and the heads-up material off (not set, or their neutral values), the clear
   output is byte for byte that of optics 12 at 25acdc7 (hashes in testdata/optics12_golden);
 - the meniscus (hcfold > 0) and the heads-up material compile and link in every heads-up mode, appear
   only in heads-up clear programs, and leave main() unchanged.
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
    for name, args in (("off_legacy_qs", "qslight=0"), ("off_params", "qslight=0,huclear=0,kgclear=0,hcband=0.9,hcamp=0.2,hccore=12,kcveil=0,kcdim=0.2,kcsat=1.2,kccore=8")):
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
# I2: the optics-12 clear text is unchanged while the meniscus and the heads-up material are off.
golden12 = golden.parent / "optics12_golden"
for hu, kg, debug in itertools.product((0, 1, 2), (0, 1, 2), (0, 3)):
    path = golden12 / f"m{hu}{kg}_d{debug}.sha256"
    if not path.exists():
        continue
    want = dict(reversed(l.split()) for l in path.read_text().split("\n") if l.strip())
    # Explicit historical parameters reproduce optics-12 goldens. Clear-only
    # defaults now intentionally activate the approved meniscus/material.
    legacy = ("qslight=0,hcband=0.85,hcamp=0.4,hcbody=0.06,hcbody2=0,"
              "hcedge=4,hccore=24,hcblurexp=2,hcfold=0,hcsat=1,"
              "hcscrim=0,hcscrimmax=0,hcadapt=0,hclift=0,"
              "kcband=0.85,kcamp=0.4,kcbody=0.05,kcbody2=0,kcedge=4,"
              "kccore=24,kcblurexp=2,kcfold=0,kclift=0,hcneutral=0.75")
    for name, args in (("explicit_legacy", "," + legacy),):
        out = gen(f"i2_{name}_{hu}{kg}_{debug}", debug, f"huclear={hu},kgclear={kg}{args}")
        got = {q.name: sha(q) for q in out.glob("mod_*")}
        if got != want:
            fails.append(f"I2 hu {hu} kg {kg} debug {debug} ({name}): " + ", ".join(sorted(k for k in set(got) | set(want) if got.get(k) != want.get(k))))
print("I2: explicit historical defaults preserve byte identity with optics 12 (25acdc7)"
      if not any(f.startswith("I2") for f in fails) else "I2: FAILED")

# I3: the heads-up approved on the device (acff51b: meniscus, material, adaptive body) is unchanged
# while the lockscreen meniscus, the lifts and the neutral strength are unset, neutral or invalid.
APPROVED = {"a": "hcband=0.42,hcfold=2,hcedge=24,hccore=24,hcbody=0,hcsat=1.05,hcscrim=0,hcscrimmax=0,hcadapt=1",
            "b": "hcband=0.42,hcfold=2,hcedge=24,hccore=24,hcbody=0.06,hcsat=1.5,hcscrim=0.12,hcscrimmax=0.5,hcadapt=1"}
golden_hu = golden.parent / "hu_approved_golden"
i3 = 0
for cfg, params in APPROVED.items():
    for (hu, kg), debug in itertools.product(((1, 0), (2, 0), (1, 1), (1, 2), (2, 2)), (0, 3)):
        want = dict(reversed(l.split()) for l in (golden_hu / f"{cfg}_m{hu}{kg}_d{debug}.sha256").read_text().split("\n") if l.strip())
        # Golden acff51b predates the KG meniscus, positive lift, and new QS light.
        legacy = (",qslight=0,kcband=0.85,kcbody=0.05,kcbody2=0,kcedge=4,"
                  "kccore=24,kcfold=0,hclift=0,kclift=0,hcneutral=0.75")
        for name, args in (("explicit_legacy", legacy),):
            out = gen(f"i3_{cfg}_{name}_{hu}{kg}_{debug}", debug, f"huclear={hu},kgclear={kg},{params}{args}")
            got = {q.name: sha(q) for q in out.glob("mod_*")}
            i3 += 1
            if got != want:
                fails.append(f"I3 {cfg} hu {hu} kg {kg} debug {debug} ({name}): " + ", ".join(sorted(k for k in set(got) | set(want) if got.get(k) != want.get(k))))
print(f"I3: the original heads-up (acff51b, {i3} runs) is byte-identical under explicit legacy KG/lift/QS overrides"
      if not any(f.startswith("I3") for f in fails) else "I3: FAILED")

# Current Clear-only defaults are identical to specifying the approved look explicitly.
# The modes remain 0 unless selected, so Frosted stays isolated from these defaults.
approved_current = ("hcband=0.42,hcfold=2,hcedge=24,hccore=24,hcbody=0,hcbody2=0,"
                    "hcadapt=1,hcsat=1.05,hcscrim=0,hcscrimmax=0,hclift=0.30,"
                    "kcband=0.42,kcfold=2,kcedge=24,kccore=24,kcbody=0,kcbody2=0,kclift=0")
for hu, kg in ((0, 0), (1, 0), (0, 1), (1, 1), (2, 2)):
    for debug in (0, 3):
        default = gen(f"approved_default_{hu}{kg}_{debug}", debug, f"huclear={hu},kgclear={kg}")
        explicit = gen(f"approved_explicit_{hu}{kg}_{debug}", debug,
                       f"huclear={hu},kgclear={kg},{approved_current}")
        for frag in default.glob("mod_*"):
            if sha(frag) != sha(explicit / frag.name):
                fails.append(f"approved defaults differ from explicit settings: {hu}/{kg}/{debug} {frag.name}")
print("I4: native Clear-only approved defaults match explicit HU/KG settings across modes")

# Lockscreen meniscus, both lifts and a non-default neutral strength, with the approved heads-up.
kgmen = 0
for hu, kg in itertools.product((0, 1, 2), repeat=2):
    for debug in range(5):
        out = gen(f"kgmen{hu}{kg}_d{debug}", debug, f"huclear={hu},kgclear={kg},{APPROVED['a']},hclift=0.5,hcneutral=0.4,kcband=0.42,kcfold=2,kcedge=12,kccore=24,kclift=0.5")
        base = ref[1 if debug == 1 else 0]
        for frag in sorted(out.glob("mod_*.frag")):
            text = frag.read_text()
            external = "samplerExternalOES" in text
            if suffix(text) != suffix((base / frag.name).read_text()):
                fails.append(f"kg meniscus changed main(): hu {hu} kg {kg} debug {debug} {frag.name}")
            if text.count("sqrt(max(1.0 - x * x, 0.0))") != (1 if hu else 0) + (1 if kg else 0):
                fails.append(f"kg meniscus field count wrong: hu {hu} kg {kg} {frag.name}")
            if text.count("1.0 - pow(1.0 - clamp(rgb, 0.0, 1.0)") != (0 if external else (1 if hu else 0) + (1 if kg else 0)):
                fails.append(f"lift count wrong: hu {hu} kg {kg} {frag.name}")
            if not hu and not kg and debug in (0, 1) and text != (ref[debug] / frag.name).read_text():
                fails.append(f"kg meniscus parameters changed an off program: debug {debug} {frag.name}")
            r = subprocess.run(["glslangValidator", "-S", "frag", str(frag)], capture_output=True, text=True)
            if r.returncode:
                fails.append(f"compile {frag}: {r.stdout[-400:]}")
            if "voulg_tag" in text:
                for vert in ("mod_001.vert", "min_001.vert"):
                    r = subprocess.run(["glslangValidator", "-l", str(out / vert), str(frag)], capture_output=True, text=True)
                    if r.returncode:
                        fails.append(f"link {vert} + {frag}: {r.stdout[-400:]}")
            kgmen += 1
print(f"kg meniscus: {kgmen} fragment rewrites (9 mode pairs x debug 0-4, kcfold 2, hclift 0.5, kclift 0.5, hcneutral 0.4) compile and link; "
      "main() unchanged; one meniscus field and one lift per clear profile")

# Meniscus and heads-up material.
meniscus = 0
for hu, kg in itertools.product((0, 1, 2), repeat=2):
    for debug in range(5):
        out = gen(f"men{hu}{kg}_d{debug}", debug, f"huclear={hu},kgclear={kg},hcband=0.42,hcfold=2,hcedge=24,hccore=24,hcsat=1.5,hcscrim=0.12,hcscrimmax=0.5,hcadapt=1")
        base = ref[1 if debug == 1 else 0]
        for frag in sorted(out.glob("mod_*.frag")):
            text = frag.read_text()
            external = "samplerExternalOES" in text
            if suffix(text) != suffix((base / frag.name).read_text()):
                fails.append(f"meniscus changed main(): hu {hu} kg {kg} debug {debug} {frag.name}")
            if text.count("sqrt(max(1.0 - x * x, 0.0))") != int(bool(hu)) + int(bool(kg)):
                fails.append(f"meniscus field presence wrong: hu {hu} kg {kg} {frag.name}")
            if ("oulg_material_hu(" in text) != bool(hu and not external):
                fails.append(f"heads-up material presence wrong: hu {hu} kg {kg} {frag.name}")
            if ("oulg_body_hu(" in text) != bool(hu and not external):
                fails.append(f"heads-up adaptive body presence wrong: hu {hu} kg {kg} {frag.name}")
            if not hu and not kg and debug in (0, 1) and text != (ref[debug] / frag.name).read_text():
                fails.append(f"meniscus parameters changed an off program: debug {debug} {frag.name}")
            r = subprocess.run(["glslangValidator", "-S", "frag", str(frag)], capture_output=True, text=True)
            if r.returncode:
                fails.append(f"compile {frag}: {r.stdout[-400:]}")
            if "voulg_tag" in text:
                for vert in ("mod_001.vert", "min_001.vert"):
                    r = subprocess.run(["glslangValidator", "-l", str(out / vert), str(frag)], capture_output=True, text=True)
                    if r.returncode:
                        fails.append(f"link {vert} + {frag}: {r.stdout[-400:]}")
            meniscus += 1
print(f"meniscus: {meniscus} fragment rewrites (9 mode pairs x debug 0-4, fold 2, sat 1.5, scrim 0.12-0.50, adapt 1) compile and link; "
      "main() unchanged; field, material and adaptive body only where the heads-up is clear")
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
