#!/usr/bin/env python3
"""Highlight neutrality vs the legacy neutral curve (docs/clear-glass/HU_HIGHLIGHT_NEUTRALITY.md).

Twin: the nested body (kernel 1, R 41.4 = 36 x 1.15) of hu_kernel_report, with the luma deviation sd of
disc B, then the heads-up material as generated: saturation (hcsat 1.03), legacy neutral (hcneutral),
highlight neutrality (hchlneutral, refract.h OULG_FS_HU_HL_FMT), lift 0.42. Scenes: a white clock (200 px
bold) over blue, green and orange, each with a uniform pastel area away from it.
Reports, per strength 0 / 0.15 / 0.5 / 0.85 / 1: mean chroma (max - min) of the near-whites around the
digits (luma > 0.5) and of the pastel interior, relative to strength 0, and the largest luma change.
usage: python3 -I hu_neutrality_report.py      (numpy, Pillow; ~1 min)"""
import importlib.util, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont
spec = importlib.util.spec_from_file_location('hk', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hu_kernel_report.py'))
hk = importlib.util.module_from_spec(spec); spec.loader.exec_module(hk)
H, W, W709 = hk.H, hk.W, hk.W709


def ss(a, b, x): t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)


def body_sd(tex, R, taps=48, wide=2.0):
    y, x = np.mgrid[0:H, 0:W].astype(float) + .5
    a0 = 2 * np.pi * np.mod(52.9829189 * np.mod(x * 0.06711056 + (H - y) * 0.00583715, 1), 1)
    dx, dy = np.cos(a0), np.sin(a0); G = hk.GOLD
    rot = lambda dx, dy: (G[0, 0] * dx + G[0, 1] * dy, G[1, 0] * dx + G[1, 1] * dy)
    acc = np.zeros((H, W, 3)); ws = 0; accb = np.zeros((H, W, 3)); wb = 0; m2 = np.zeros((H, W))
    for i in range(taps):
        rr = np.sqrt((i + .5) / taps); t = hk.bilinear(tex, x + dx * rr * R, y + dy * rr * R); w = np.exp(-2 * rr * rr)
        acc += w * t; ws += w; v = np.exp(-2 * rr * rr / wide ** 2); yv = t @ W709; accb += v * t; wb += v; m2 += v * yv * yv
        dx, dy = rot(dx, dy)
    q2 = 1 / wide ** 2
    for j in range(taps):
        rb = np.sqrt(q2 + (1 - q2) * (j + .5) / taps); v = (wide * wide - 1) * np.exp(-2 * rb * rb)
        t = hk.bilinear(tex, x + dx * rb * R * wide, y + dy * rb * R * wide); yv = t @ W709; accb += v * t; wb += v; m2 += v * yv * yv
        dx, dy = rot(dx, dy)
    a = acc / ws; b = accb / wb; sd = np.sqrt(np.maximum(m2 / wb - (b @ W709) ** 2, 0))
    return a + (b - a) * ss(.035, .14, sd)[..., None], sd


def material(rgb, sd, legacy=0.0, hl=0.0, hsat=1.03, lift=0.42):
    y = np.clip(rgb @ W709, 0, 1); w = y * y * (3 - 2 * y); ch = rgb.max(-1) - rgb.min(-1)
    n = legacy * ss(.55, .9, y) * (1 - ss(.15, .45, ch))
    out = y[..., None] + (rgb - y[..., None]) * (((1 - w) * hsat + w) * (1 - n))[..., None]
    if hl > 0:
        yh = out @ W709; mh = out.max(-1); sh = (mh - out.min(-1)) / np.maximum(mh, 1e-3)
        e = hl * ss(.25, .70, yh) * (1 - ss(.35, .95, sh)) * ss(.10, .22, sd)
        out = out + (yh[..., None] - out) * e[..., None]
    return 1 - (1 - np.clip(out, 0, 1)) ** (1 + lift)


def scene(bg, pastel):
    im = Image.new('RGB', (W, H), bg); d = ImageDraw.Draw(im)
    d.rectangle((560, 60, 900, 420), fill=pastel)
    d.text((40, 90), "16:59", fill=(245, 245, 245), font=ImageFont.truetype(hk.FONT.replace('DejaVuSans.ttf', 'DejaVuSans-Bold.ttf'), 200))
    return np.asarray(im, float) / 255


def main():
    scenes = {'blue / pale blue': scene((15, 30, 120), (150, 190, 240)), 'green / mint': scene((20, 120, 60), (180, 230, 190)),
              'orange / peach': scene((210, 110, 30), (245, 200, 160))}
    yy, xx = np.mgrid[0:H, 0:W]
    past = (xx > 620) & (xx < 840) & (yy > 120) & (yy < 360); halo = (xx > 40) & (xx < 520) & (yy > 80) & (yy < 320)
    levels = (0, .15, .5, .85, 1)
    for name, B in scenes.items():
        rgb, sd = body_sd(hk.make_tex(B), 41.4)
        base = material(rgb, sd); bl = base @ W709; m = halo & (bl > .5); bc = base.max(-1) - base.min(-1)
        for label, f in (('legacy hcneutral', lambda v: material(rgb, sd, legacy=v)), ('hchlneutral', lambda v: material(rgb, sd, hl=v))):
            cells = []
            for v in levels:
                o = f(v); c = o.max(-1) - o.min(-1)
                cells.append(f"{v:.2f}: {100 * c[m].mean() / bc[m].mean():5.1f}/{100 * c[past].mean() / bc[past].mean():5.1f} dY {np.abs(o @ W709 - bl).max():.3f}")
            print(f"{name:16s} {label:17s} whites/pastel % " + ' | '.join(cells), flush=True)


if __name__ == '__main__':
    main()
