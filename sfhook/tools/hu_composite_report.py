#!/usr/bin/env python3
"""Heads-up Clear Glass: full-composition residual report (docs/clear-glass/HU_RESIDUAL.md).

hu_kernel_report.py measures the body kernel alone. This tool measures what reaches the screen when
that body is composed with the rest of the pipeline, and separates the hypotheses of the R2 report
(sharp backdrop under a mist) by their signature, never by their look:

  baseline     O = K(T(B))                     the rewritten body over the compositor's blurred input
  H1 alpha     O = (1-a) B + a K(T(B))          a partly transparent region (positive control)
  crossfade    O = (1-m) B + m K(T(B))          RenderEngine's small-radius mix, m = radius / 10,
                                               with only the blurred child rewritten
  H3 route     O = T(B)                         a region drawn untagged (native blur only)
  H2 kernel    A alone / B alone / adaptive mix  the body's own frequencies (kernels 0-3)

B is the backdrop, T the compositor input (hu_kernel_report.make_tex: 1/4 resolution + 1 texel),
K the generated body (hu_kernel_report.body, the numpy twin of oulg_body_hu). Units are device px.

Per configuration and synthetic scene (no private data) it reports
  transfer   regression of the output's band-pass onto the input's (DoG bands in device px):
             a coherent copy of B shows as a band-independent floor at fine scales; the body
             alone transmits ~0 below its own scale and grows with it;
  floor      mean transfer over the 1.5-6 px bands (the sharp-copy estimate);
  MTF        amplitude kept by sinusoid gratings, 8-256 px periods;
  ESF        10-90 % width and the weight of a narrow (sigma <= 4 px) component fitted to the step;
  PSF        share of an isolated bright dot's excess energy within 4 px of it;
  temporal   RMS frame-to-frame change of the body for a 1 px/frame scroll, relative to the
             backdrop contrast (shimmer of the stochastic taps; a coherent copy moves rigidly).

usage:
  python3 -I hu_composite_report.py                     the model table (twin; ~3 min on 4 cores)
  python3 -I hu_composite_report.py --pair REF TEST X0 Y0 X1 Y1 [SCALE]
        transfer of the same content between a capture without the pop-up (REF) and one with it
        (TEST), over the ROI in TEST pixels; SCALE = device px per capture px (default 1).
        Captures stay local: this tool writes nothing.
Needs numpy and Pillow (DejaVu Sans). Not a CI gate.
"""
import importlib.util
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('hu_kernel_report', os.path.join(HERE, 'hu_kernel_report.py'))
hk = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(hk)
H, W, W709 = hk.H, hk.W, hk.W709
FONT, BOLD = hk.FONT, hk.FONT.replace('DejaVuSans.ttf', 'DejaVuSans-Bold.ttf')
BANDS = ((0.7, 1.5), (1.5, 3.0), (3.0, 6.0), (6.0, 12.0), (12.0, 24.0), (24.0, 48.0))
FLOOR = (1, 2)                      # the bands averaged as the sharp-copy floor (1.5-6 px)


# ---- filters ---------------------------------------------------------------------------------------
def gauss(Y, s):
    """Gaussian blur (sigma s px, reflect boundary) through the FFT; works on 2-D or 3-D arrays."""
    if s <= 0: return Y
    p = int(np.ceil(4 * s)); pad = ((p, p), (p, p)) + ((0, 0),) * (Y.ndim - 2)
    Z = np.pad(Y, pad, mode='reflect'); fy = np.fft.fftfreq(Z.shape[0])[:, None]; fx = np.fft.rfftfreq(Z.shape[1])[None, :]
    k = np.exp(-2 * (np.pi * s) ** 2 * (fx * fx + fy * fy))
    if Y.ndim == 3: k = k[..., None]
    out = np.fft.irfft2(np.fft.rfft2(Z, axes=(0, 1)) * k, s=Z.shape[:2], axes=(0, 1))
    return out[p:-p, p:-p]


def band(Y, lo, hi): return gauss(Y, lo) - gauss(Y, hi)


M = 90                              # margin: the body's reach and the twin's clamped borders


def transfer(out, inp, lo, hi, mask=None):
    """Regression slope and correlation of out's band onto inp's band (luma)."""
    o, i = band(out @ W709, lo, hi)[M:-M, M:-M], band(inp @ W709, lo, hi)[M:-M, M:-M]
    if mask is not None: o, i = o[mask[M:-M, M:-M]], i[mask[M:-M, M:-M]]
    o, i = o.ravel(), i.ravel(); d = i @ i
    if d < 1e-9: return 0.0, 0.0
    return float(o @ i / d), float(np.corrcoef(o, i)[0, 1])


# ---- scenes (synthetic, known backdrops) -------------------------------------------------------------
def canvas(bg): im = Image.new('RGB', (W, H), bg); return im, ImageDraw.Draw(im)


def arr(im): return np.asarray(im, float) / 255


def text_block(bg, fg, size, pitch, bold=False):
    im, d = canvas(bg); f = ImageFont.truetype(BOLD if bold else FONT, size); y = 20
    while y < H - size:
        d.text((20, y), "Compare blur, refraction, contrast and edges 0123456789", fill=fg, font=f); y += pitch
    return arr(im)


def clock():
    """Wallpaper-like blue gradient with large white digits (the 16:59 home case)."""
    yy, xx = np.mgrid[0:H, 0:W] / np.array([H, W])[:, None, None]
    base = np.stack([0.08 + 0.10 * xx, 0.12 + 0.12 * yy, 0.45 + 0.25 * xx], -1)
    im = Image.fromarray(np.uint8(np.clip(base, 0, 1) * 255)); d = ImageDraw.Draw(im)
    d.text((150, 60), "16:59", fill=(240, 244, 255), font=ImageFont.truetype(FONT, 300))
    return arr(im)


def preview():
    """A light notification preview: bold title, body lines and buttons over a dark app page."""
    im, d = canvas((18, 24, 32)); d.rounded_rectangle((30, 40, W - 30, H - 40), 36, fill=(246, 247, 248))
    d.text((70, 70), "Test notification", fill=(28, 30, 34), font=ImageFont.truetype(BOLD, 52))
    for k, line in enumerate(("Expanded glass test - expand to compare.", "Compare blur, edges, and contrast.")):
        d.text((70, 150 + 60 * k), line, fill=(90, 94, 100), font=ImageFont.truetype(FONT, 40))
    d.text((70, 330), "Open settings      Dismiss", fill=(10, 110, 130), font=ImageFont.truetype(BOLD, 40))
    return arr(im)


def face():
    """A schematic face on grey (skin, eyes, brows, beard): intermediate-scale structure, 4-40 px."""
    im, d = canvas((128, 128, 132)); cx, cy = W // 2, H // 2
    d.ellipse((cx - 150, cy - 200, cx + 150, cy + 200), fill=(214, 172, 146))
    for s in (-1, 1):
        d.ellipse((cx + s * 70 - 28, cy - 70, cx + s * 70 + 28, cy - 44), fill=(250, 250, 250))
        d.ellipse((cx + s * 70 - 11, cy - 68, cx + s * 70 + 11, cy - 46), fill=(40, 28, 24))
        d.rectangle((cx + s * 70 - 40, cy - 100, cx + s * 70 + 40, cy - 90), fill=(70, 50, 40))
    d.chord((cx - 120, cy + 20, cx + 120, cy + 230), 0, 180, fill=(70, 52, 44))
    d.rectangle((cx - 50, cy + 80, cx + 50, cy + 92), fill=(150, 70, 70))
    return arr(im)


def dark_highlights():
    """Gmail-like dark page: small near-white marks (2-16 px) and coloured chips on dark grey."""
    im, d = canvas((32, 33, 36)); rng = np.random.default_rng(7)
    for _ in range(140):
        x, y, s = rng.integers(20, W - 40), rng.integers(20, H - 40), int(rng.choice((2, 4, 8, 16)))
        d.rectangle((x, y, x + s, y + s // 2 + 1), fill=(236, 238, 240))
    for k, col in enumerate(((40, 170, 200), (60, 180, 110), (200, 80, 80))):
        d.rounded_rectangle((60 + 300 * k, 380, 240 + 300 * k, 430), 20, fill=col)
    return arr(im)


def scenes():
    return {'small_text': text_block((250, 250, 250), (25, 25, 25), 18, 30),
            'large_text': text_block((250, 250, 250), (25, 25, 25), 40, 65),
            'white_on_blue': text_block((12, 24, 120), (240, 240, 240), 40, 65, bold=True),
            'clock': clock(), 'preview': preview(), 'face': face(), 'dark_highlights': dark_highlights()}


# ---- models ----------------------------------------------------------------------------------------
def models(R, kernel):
    """name -> fn(B, K) giving the screen. K = the body over T(B) at this R and kernel."""
    m = {'baseline': lambda B, K: K}
    for a in (0.98, 0.95, 0.90, 0.80):
        m[f'H1 alpha {a:.2f}'] = (lambda a: lambda B, K: (1 - a) * B + a * K)(a)
    for r in (9.8, 9.0, 7.0):
        m[f'crossfade r={r:g}'] = (lambda q: lambda B, K: (1 - q) * B + q * K)(min(r / 10, 1))
    m['H3 untagged (T only)'] = lambda B, K: upsample(B)
    return m


def upsample(B):
    """T(B) seen on screen without the body: the 1/4 texture, read bilinearly at every pixel."""
    y, x = np.mgrid[0:H, 0:W].astype(np.float64) + 0.5
    return hk.bilinear(hk.make_tex(B), x, y)


def mtf(render, periods=(8, 16, 32, 48, 64, 96, 128, 256)):
    out = []
    for P in periods:
        x = np.arange(W) + 0.5; g = 0.5 + 0.35 * np.sin(2 * np.pi * x / P)
        B = np.repeat(np.repeat(g[None, :, None], H, 0), 3, 2); O = render(B) @ W709
        c = O[M:-M, M:-M] - O[M:-M, M:-M].mean(); s = (g - 0.5)[None, M:-M]
        out.append(float((c * s).sum() / (s * s).sum() / O[M:-M, M:-M].shape[0] * 1.0))
    return out


def esf(render):
    B = np.zeros((H, W, 3)); B[:, W // 2:] = 1.0
    Y = (render(B) @ W709)[M:-M].mean(0); x = np.arange(W) - W // 2 + 0.5
    seg = slice(W // 2 - 160, W // 2 + 160); n = (Y - Y[seg].min()) / (Y[seg].max() - Y[seg].min())
    w1090 = int(np.argmax(n[seg] > 0.9) - np.argmax(n[seg] > 0.1))
    # Narrow + wide fit: n = a Phi(x / sn) + (1 - a) Phi(x / sw), sn <= 4 px, least squares over a grid.
    from math import erf
    Phi = np.vectorize(lambda t: 0.5 * (1 + erf(t / np.sqrt(2))))
    best = (1e9, 0, 0, 0)
    for sn in (1.0, 2.0, 4.0):
        pn = Phi(x[seg] / sn)
        for sw in (8, 12, 16, 20, 24, 30, 36, 44):
            pw = Phi(x[seg] / sw); d = pn - pw
            a = float(np.clip(((n[seg] - pw) @ d) / (d @ d), 0, 1)); e = float(np.mean((a * pn + (1 - a) * pw - n[seg]) ** 2))
            if e < best[0]: best = (e, a, sn, sw)
    return w1090, best[1], best[3]


def psf_core(render):
    B = np.full((H, W, 3), 0.15); B[H // 2 - 1:H // 2 + 1, W // 2 - 1:W // 2 + 1] = 1.0
    Y = render(B) @ W709 - render(np.full((H, W, 3), 0.15)) @ W709
    yy, xx = np.mgrid[0:H, 0:W]; d = np.hypot(yy - H // 2 + 0.5, xx - W // 2 + 0.5)
    tot = np.clip(Y, 0, None)[d < 160].sum()
    return float(np.clip(Y, 0, None)[d <= 4].sum() / max(tot, 1e-9))


def temporal(render, S):
    frames = [render(np.roll(S, k, axis=1)) @ W709 for k in range(3)]
    con = (S @ W709)[M:-M, M:-M].std()
    # Remove the rigid shift (a coherent image moves with the content), keep what changes in place.
    d = [np.roll(frames[k + 1], -1, axis=1) - frames[k] for k in range(2)]
    return float(np.mean([x[M:-M, M + 2:-M - 2].std() for x in d]) / con)


# ---- report ----------------------------------------------------------------------------------------
def body_of(B, R, kernel): return hk.body(hk.make_tex(B), R, kernel)


def main_models():
    S = scenes()
    cfgs = (('K1 36/36 x1.00 (approved ref)', 36.0, 1), ('K1 32/36 x1.15 (R2 desired)', 36.0 * 1.15, 1),
            ('K0 36/36 x1.00 (original)', 36.0, 0))
    print('transfer = band-pass regression slope (device px DoG bands); floor = mean of 1.5-6 px bands')
    hdr = ' '.join(f'{lo:g}-{hi:g}' for lo, hi in BANDS)
    for title, R, k in cfgs:
        print(f'\n== {title}: body R = {R:.1f} px (core), kernel {k}')
        K = {n: body_of(B, R, k) for n, B in S.items()}
        print(f"{'model':24s} {'scene':16s} | {hdr} | floor  corr")
        for mname, f in models(R, k).items():
            fl = []
            for n, B in S.items():
                O = f(B, K[n])
                t = [transfer(O, B, lo, hi) for lo, hi in BANDS]
                floor = np.mean([t[i][0] for i in FLOOR]); fl.append(floor)
                if n in ('preview', 'clock', 'small_text') or mname == 'baseline':
                    print(f"{mname:24s} {n:16s} | " + ' '.join(f'{x[0]*100:6.2f}' for x in t) + f" | {floor*100:5.2f}% {np.mean([t[i][1] for i in FLOOR]):5.2f}")
            print(f"{mname:24s} {'(all scenes)':16s} | floor mean {np.mean(fl)*100:5.2f}%  min {np.min(fl)*100:5.2f}%  max {np.max(fl)*100:5.2f}%")
        rk = lambda B: body_of(B, R, k)
        w, a, sw = esf(rk); print(f"ESF 10-90 {w} px; narrow-component weight {a*100:.1f}% (wide sigma {sw} px)")
        for a1 in (0.98, 0.90):
            w, a, sw = esf(lambda B: (1 - a1) * B + a1 * rk(B))
            print(f"  H1 alpha {a1:.2f}: ESF 10-90 {w} px; narrow-component weight {a*100:.1f}% (wide sigma {sw} px)")
        print('MTF 8/16/32/48/64/96/128/256 px:', ' '.join(f'{v:.4f}' for v in mtf(rk)))
        print('  H1 alpha 0.98         :', ' '.join(f'{v:.4f}' for v in mtf(lambda B: 0.02 * B + 0.98 * rk(B))))
        print(f'PSF share within 4 px: body {psf_core(rk)*100:.2f}%  H1 0.98 {psf_core(lambda B: 0.02 * B + 0.98 * rk(B))*100:.2f}%')
        print(f"temporal shimmer (1 px/frame, relative to contrast): body {temporal(rk, S['large_text']):.4f}  "
              f"H1 0.98 {temporal(lambda B: 0.02 * B + 0.98 * rk(B), S['large_text']):.4f}")
        sys.stdout.flush()


def main_pair(ref, test, x0, y0, x1, y1, scale):
    R = np.asarray(Image.open(ref).convert('RGB'), float) / 255; T = np.asarray(Image.open(test).convert('RGB'), float) / 255
    best = None
    for dy in range(-6, 7):
        for dx in range(-6, 7):
            r = R[y0 + dy:y1 + dy, x0 + dx:x1 + dx] @ W709; t = T[y0:y1, x0:x1] @ W709
            c = np.corrcoef((gauss(r, 1) - gauss(r, 6)).ravel(), (gauss(t, 1) - gauss(t, 6)).ravel())[0, 1]
            if best is None or c > best[0]: best = (c, dy, dx)
    c, dy, dx = best
    r = R[y0 + dy:y1 + dy, x0 + dx:x1 + dx] @ W709; t = T[y0:y1, x0:x1] @ W709
    print(f'registration corr {c:.3f} (dy {dy}, dx {dx}); reference range {r.min():.3f}-{r.max():.3f}, test {t.min():.3f}-{t.max():.3f}')
    for lo, hi in BANDS:
        a, b = gauss(r, lo / scale) - gauss(r, hi / scale), gauss(t, lo / scale) - gauss(t, hi / scale)
        a, b = a[4:-4, 4:-4].ravel(), b[4:-4, 4:-4].ravel()
        if hi / scale < 0.6: continue
        print(f'band {lo:g}-{hi:g} device px: transfer {100 * (a @ b) / (a @ a):6.2f}%  corr {np.corrcoef(a, b)[0, 1]:5.2f}')


if __name__ == '__main__':
    if len(sys.argv) >= 7 and sys.argv[1] == '--pair':
        main_pair(sys.argv[2], sys.argv[3], *map(int, sys.argv[4:8]), float(sys.argv[8]) if len(sys.argv) > 8 else 1.0)
    else:
        main_models()
