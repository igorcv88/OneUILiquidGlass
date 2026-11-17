#!/usr/bin/env python3
"""A normalised blur must return alpha 1 from opaque taps, whatever their RGB and the A/B weight
(docs/clear-glass/HU_ALPHA_PROBE.md, R4). Checked on the generated GLSL of every body/disc kernel:

 1. static: in oulg_body_hu and oulg_disc*, each `sum / weight` normalisation divides a sum declared
    at the same precision as its weight (mediump/mediump or highp/highp). A mediump sum over a highp
    weight is what let ~2 % of the sharp backdrop through on the device;
 2. numeric: the weights each kernel emits (taps, wide factor) accumulated as the declarations say,
    with fp16 rounding to nearest and toward zero for mediump, return alpha within 1e-3 of 1 for
    opaque taps, for disc A, disc B and any mix of them. The pre-fix declarations are run as a
    positive control and must fail (1.2 % for kernel 0, 2.0 % for kernels 1-3 toward zero).

usage: python3 -I alpha_precision_check.py <refract_check binary> <dump>
"""
import math
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


def fn(text, name):
    i = text.find(f"mediump vec4 {name}(")
    j = text.find("\n}\n", i)
    return text[i:j + 3] if i >= 0 else None


def decls(body):
    out = {}
    for prec, rest in re.findall(r"(mediump|highp) (?:vec4|float) ([^;]+);", body):
        for part in rest.split(","):
            n = part.split("=")[0].strip()
            if re.fullmatch(r"[a-z0-9_]+", n): out[n] = prec
    return out


def static(body):
    d = decls(body); bad = []
    for num, den in re.findall(r"= ([a-z0-9_]+) / ([a-z0-9_]+);", body):
        if num in d and den in d and d[num] != d[den]: bad.append(f"{num} ({d[num]}) / {den} ({d[den]})")
    return bad


# fp16/fp32 rounding without numpy (the CI runner has none): struct's 'e' and 'f' round to nearest.
def f32(x): return struct.unpack('<f', struct.pack('<f', x))[0]
def rn16(x): return struct.unpack('<e', struct.pack('<e', x))[0]


def rtz16(x):
    """Round toward zero to fp16: the nearest value, stepped one ulp toward zero if it overshot."""
    h = rn16(x)
    if abs(h) > abs(x):
        bits = struct.unpack('<H', struct.pack('<e', h))[0]
        h = struct.unpack('<e', struct.pack('<H', bits - 1))[0]
    return h


def accumulate(ws, prec, mode):
    if prec == "highp":
        a = 0.0
        for w in ws: a = f32(a + f32(w))
        return a
    r = rtz16 if mode == "rtz" else rn16
    a = 0.0
    for w in ws: a = r(a + r(w))
    return a


def weights(kernel, taps, wide):
    if kernel == 0:
        a = [math.exp(-2 * (i + .5) / taps) for i in range(taps)]
        return a, a                                   # disc0 (A) and B with its own taps
    q2 = 1 / wide ** 2
    a = [math.exp(-2 * (i + .5) / taps) for i in range(taps)]
    b = [math.exp(-2 * ((i + .5) / taps) / wide ** 2) for i in range(taps)] + \
        [(wide * wide - 1) * math.exp(-2 * (q2 + (1 - q2) * (j + .5) / taps)) for j in range(taps)]
    return a, b


def numeric(kernel, body, taps, wide):
    d = decls(body); a, b = weights(kernel, taps, wide); worst = 0.0
    names = {0: (None, ("acc", "wsum")), 1: (("acc", "wsum"), ("accb", "wb"))}[min(kernel, 1)]
    for mode in ("rn", "rtz"):
        alphas = []
        if names[0]:
            alphas.append(accumulate(a, d[names[0][0]], mode) / accumulate(a, d[names[0][1]], mode))
        else:                                            # kernel 0's A is oulg_disc0: mediump / mediump
            alphas.append(accumulate(a, "mediump", mode) / accumulate(a, "mediump", mode))
        alphas.append(accumulate(b, d[names[1][0]], mode) / accumulate(b, d[names[1][1]], mode))
        for t in [i / 10 for i in range(11)]:            # any A/B weight
            worst = max(worst, abs(1 - ((1 - t) * alphas[0] + t * alphas[1])))
    return worst


def main():
    rc, dump = sys.argv[1], sys.argv[2]; fails = []; rows = []
    for kernel in (0, 1, 2, 3):
        out = Path(tempfile.mkdtemp())
        subprocess.run([rc, dump, str(out), "0", f"huclear=1,hckernel={kernel},hcadapt=1"], check=True, capture_output=True)
        text = next(p.read_text() for p in sorted(out.glob("mod_*.frag")) if "oulg_body_hu" in p.read_text())
        body = fn(text, "oulg_body_hu")
        wide = float(re.search(r"oulg_br \* ([\d.]+)\)", body).group(1))
        taps = int(re.search(r"i < (\d+);", body).group(1))
        bad = static(body) + [f"{n}: " + ", ".join(static(fn(text, n))) for n in ("oulg_disc0", "oulg_disc1") if fn(text, n) and static(fn(text, n))]
        err = numeric(kernel, body, taps, wide)
        # Positive control: the pre-fix declarations must fail the numeric gate.
        old = body.replace("highp vec4 acc = vec4(0.0);", "mediump vec4 acc = vec4(0.0);") if kernel == 0 else \
              body.replace("highp vec4 accb = vec4(0.0);", "mediump vec4 accb = vec4(0.0);")
        old_err = numeric(kernel, old, taps, wide)
        rows.append(f"kernel {kernel}: static {'ok' if not bad else bad}; worst |1 - alpha| {err:.2e} (pre-fix declarations: {old_err * 100:.2f} %)")
        if bad: fails.append(f"kernel {kernel}: mixed precision {bad}")
        if err > 1e-3: fails.append(f"kernel {kernel}: alpha off by {err:.4f}")
        if old_err < 5e-3: fails.append(f"kernel {kernel}: positive control did not fail ({old_err:.4f})")
    print("\n".join(rows))
    if fails: print("FAIL: " + "; ".join(fails)); sys.exit(1)
    print("alpha precision: opaque taps give alpha 1 within 1e-3 in fp16 (to nearest and toward zero), every kernel and A/B weight")


if __name__ == "__main__":
    main()
