#!/usr/bin/env python3
"""Heads-up Clear Glass body kernel report (sfhook/refract.h, hckernel; docs/clear-glass/HU_KERNEL.md).

usage:
  python3 -I hu_kernel_report.py                       numpy twin of the generated GLSL
  python3 -I hu_kernel_report.py --glsl <refract_check> <dump>
                                                        the generated GLSL itself, rendered in WebGL2
                                                        (node + playwright; hu_kernel_render.mjs)
Needs numpy and Pillow (DejaVu Sans for the text scenes). Not a CI gate: the gates are in
clear_check.c (expected kernel: extrema, quadrature) and clear_shader_check.py (text, reads, glslang).

The twin reproduces oulg_disc0 / oulg_body_hu tap for tap: area-uniform Vogel taps rotated by the
golden angle from a per-pixel interleaved-gradient-noise angle, Gaussian weights exp(-2 rr^2), the
compositor input as a 4x downscale + 1-texel Gaussian, read bilinearly. Against the WebGL render of
the GLSL it agrees to ~0.7 LSB mean (float32/mediump, the noise angle's precision and SwiftShader
filtering account for the rest). Only the card body is measured (the band's field moves samples).

Metrics, luma (BT.709), averaged over three text scenes (white on black, black on white, white on
blue; 40 px text, 65 px line pitch, as in the device capture HU_30):
  text   RMS(box5 - box61): structure left at 5-60 px (letters, words, lines);
  grain  RMS(Y - box3): pixel-scale noise (undersampling);
  halo   a 60 px near-white square on saturated blue: share of its excess luma outside the square
         (energy), distance where the excess falls below 10 % / 3 % of the peak, mean saturation lost
         by the blue 8-60 px from it, with and without hclift 0.3;
  ESF    10-90 % width of a black/white step (px);
  photo  share of a photographic backdrop's 6-60 px and 20-120 px structure kept (the "watery" look).
"""
import os
import subprocess
import sys
import tempfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont

H, W = 480, 960
W709 = np.array([0.2126, 0.7152, 0.0722])
GOLD = np.array([[-0.7373688, -0.6754903], [0.6754903, -0.7373688]])  # rot * dir, as mat2(...) in GLSL
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'


# ---- the compositor input and the twin ------------------------------------------------------------
def make_tex(img, down=4, blur=1.0):
    h, w, c = img.shape
    t = img.reshape(h // down, down, w // down, down, c).mean(axis=(1, 3))
    r = int(np.ceil(3 * blur)); k = np.exp(-0.5 * (np.arange(-r, r + 1) / blur) ** 2); k /= k.sum()
    p = np.pad(t, ((r, r), (0, 0), (0, 0)), mode='edge'); t = sum(k[i] * p[i:i + t.shape[0]] for i in range(2 * r + 1))
    p = np.pad(t, ((0, 0), (r, r), (0, 0)), mode='edge'); t = sum(k[i] * p[:, i:i + t.shape[1]] for i in range(2 * r + 1))
    return np.round(np.clip(t, 0, 1) * 255) / 255           # an 8-bit texture, as uploaded


def bilinear(tex, sx, sy, down=4):
    th, tw, _ = tex.shape
    u = np.clip(sx / down - 0.5, 0, tw - 1); v = np.clip(sy / down - 0.5, 0, th - 1)
    x0 = np.floor(u).astype(int); y0 = np.floor(v).astype(int)
    x1 = np.minimum(x0 + 1, tw - 1); y1 = np.minimum(y0 + 1, th - 1)
    fx = (u - x0)[..., None]; fy = (v - y0)[..., None]
    return tex[y0, x0] * (1 - fx) * (1 - fy) + tex[y0, x1] * fx * (1 - fy) + tex[y1, x0] * (1 - fx) * fy + tex[y1, x1] * fx * fy


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t)


def body(tex, R, kernel, taps=48, adapt=1.0):
    """oulg_body_hu: kernel 0 (A + B at 2.5 R, B's own taps) or 1-3 (nested Gaussian, B at 2.0 R)."""
    y, x = np.mgrid[0:H, 0:W].astype(np.float64) + 0.5
    f = np.mod(x * 0.06711056 + (H - y) * 0.00583715, 1.0)          # gl_FragCoord is bottom-up
    a0 = 2 * np.pi * np.mod(52.9829189 * f, 1.0)
    dx, dy = np.cos(a0), np.sin(a0)
    rot = lambda dx, dy: (GOLD[0, 0] * dx + GOLD[0, 1] * dy, GOLD[1, 0] * dx + GOLD[1, 1] * dy)
    acc = np.zeros((H, W, 3)); ws = 0.0
    accb = np.zeros((H, W, 3)); wb = 0.0; m2 = np.zeros((H, W))
    wide, lo, hi = (2.5, 0.06, 0.22) if kernel == 0 else (2.0,) + {1: (0.035, 0.14), 2: (0.04, 0.16), 3: (0.03, 0.12)}[kernel]
    # Disc A (both kernels; kernel 1-3 also feed its taps to B).
    for i in range(taps):
        rr = np.sqrt((i + 0.5) / taps); t = bilinear(tex, x + dx * rr * R, y + dy * rr * R)
        w = np.exp(-2 * rr * rr); acc += w * t; ws += w
        if kernel:
            v = np.exp(-2 * rr * rr / (wide * wide)); yv = t @ W709; accb += v * t; wb += v; m2 += v * yv * yv
        dx, dy = rot(dx, dy)
    if kernel == 0:                                                  # oulg_body_hu restarts the angle
        dx, dy = np.cos(a0), np.sin(a0)
    for j in range(taps):
        if kernel == 0:
            rb = np.sqrt((j + 0.5) / taps); v = np.exp(-2 * rb * rb)
        else:
            q2 = 1 / wide ** 2; rb = np.sqrt(q2 + (1 - q2) * (j + 0.5) / taps); v = (wide * wide - 1) * np.exp(-2 * rb * rb)
        t = bilinear(tex, x + dx * rb * R * wide, y + dy * rb * R * wide); yv = t @ W709
        accb += v * t; wb += v; m2 += v * yv * yv
        dx, dy = rot(dx, dy)
    a = acc / ws; b = accb / wb
    sd = np.sqrt(np.maximum(m2 / wb - (b @ W709) ** 2, 0))
    return a + (b - a) * (adapt * smoothstep(lo, hi, sd))[..., None]


def lift(c, l=0.30):
    return 1 - (1 - np.clip(c, 0, 1)) ** (1 + l)


# ---- scenes ---------------------------------------------------------------------------------------
LINES = ["A System UI esta tendo inumeros crashes, morrendo", "inumeras vezes. Eu nao sei se e porque eu estava",
         "usando varios aplicativos que sao bem pesados na", "memoria do aparelho e recebendo notificacoes",
         "Swipe the pop-up, expand the notification and", "Compare blur, refraction, contrast and edges on"]


def text_scene(bg, fg):
    im = Image.new('RGB', (W, H), bg); d = ImageDraw.Draw(im); f = ImageFont.truetype(FONT, 40); y = 30
    for line in LINES * 3:
        if y > H - 40: break
        d.text((20, y), line, fill=fg, font=f); y += 65
    return np.asarray(im, float) / 255


def scenes():
    s = {'white_on_black': text_scene((0, 0, 0), (235, 235, 235)), 'black_on_white': text_scene((250, 250, 250), (20, 20, 20)),
         'white_on_blue': text_scene((10, 20, 120), (240, 240, 240))}
    st = np.zeros((H, W, 3)); st[:, W // 2:] = 1.0; s['step'] = st
    pb = np.ones((H, W, 3)) * np.array([20, 60, 200]) / 255; pb[H // 2 - 30:H // 2 + 30, W // 2 - 30:W // 2 + 30] = 0.97; s['patch_blue'] = pb
    yy, xx = np.mgrid[0:H, 0:W] / np.array([H, W])[:, None, None]
    ph = np.stack([0.55 + 0.25 * xx, 0.45 + 0.15 * yy, 0.65 - 0.2 * xx], -1); rng = np.random.default_rng(3)
    for _ in range(14):
        cx, cy, r = rng.uniform(0, W), rng.uniform(0, H), rng.uniform(50, 140); col = rng.uniform(-0.18, 0.18, 3)
        Y, X = np.mgrid[0:H, 0:W]; ph += col * np.exp(-(np.hypot(X - cx, Y - cy) / r) ** 2)[..., None]
    ph[300:420, 500:900] = ph[300:420, 500:900] * 0.6 + np.array([0.05, 0.1, 0.45]) * 0.4
    s['photo'] = np.clip(ph, 0, 1)
    return s


# ---- metrics --------------------------------------------------------------------------------------
def box(Y, r):
    p = np.pad(Y, r, mode='edge'); c = np.pad(p.cumsum(0).cumsum(1), ((1, 0), (1, 0))); n = 2 * r + 1; h, w = Y.shape
    return (c[n:n + h, n:n + w] - c[0:h, n:n + w] - c[n:n + h, 0:w] + c[0:h, 0:w]) / (n * n)


M = 100


def text_metrics(out):
    Y = out @ W709; c = (slice(M, -M), slice(M, -M))
    return 100 * np.sqrt(np.mean((box(Y, 2) - box(Y, 30))[c] ** 2)), 100 * np.sqrt(np.mean((Y - box(Y, 1))[c] ** 2))


def halo(out, inp):
    Y, Yi = out @ W709, inp @ W709; bg = np.median(Yi); peak = Yi.max() - bg
    yy, xx = np.mgrid[0:H, 0:W]; d = np.maximum(np.abs(xx - W // 2 + 0.5) - 30, np.abs(yy - H // 2 + 0.5) - 30)
    dY = (Y - bg) / peak; energy = np.clip(dY[d > 2], 0, None).sum() / np.clip(dY, 0, None).sum()
    prof = [np.clip(dY[(d >= k) & (d < k + 2)], 0, None).mean() for k in range(0, 160, 2)]
    reach = [next((2 * i for i, v in enumerate(prof) if v < t), 999) for t in (0.10, 0.03)]
    sat = lambda c: (c.max(-1) - c.min(-1)) / np.maximum(c.max(-1), 1e-3)
    ring = (d > 8) & (d < 60)
    return 100 * energy, reach[0], reach[1], 100 * (1 - sat(out)[ring].mean() / sat(inp)[0, 0])


def esf(out):
    Y = (out @ W709)[200:280].mean(0); seg = Y[W // 2 - 150:W // 2 + 150]; n = (seg - seg.min()) / (seg.max() - seg.min())
    return int(np.argmax(n > 0.9) - np.argmax(n > 0.1))


def photo_kept(out, inp):
    Yo, Yi = out @ W709, inp @ W709; c = (slice(60, -60), slice(60, -60))
    k = lambda Y, a, b: np.sqrt(np.mean((box(Y, a) - box(Y, b))[c] ** 2))
    return 100 * k(Yo, 6, 60) / k(Yi, 6, 60), 100 * k(Yo, 20, 120) / k(Yi, 20, 120)


# ---- renderers ------------------------------------------------------------------------------------
def twin(R, kernel, liftv):
    return lambda img: (lift(body(make_tex(img), R, kernel), liftv) if liftv else body(make_tex(img), R, kernel))


def glsl(rc, dump, work, R, kernel, liftv):
    out = os.path.join(work, f'k{kernel}_r{R}_l{liftv}'); os.makedirs(out, exist_ok=True)
    subprocess.run([rc, dump, out, '0', f'huclear=1,hcedge={R},hccore={R},hcfold=2,hcsat=1,hcneutral=0,hclift={liftv},hulight=0,hckernel={kernel}'],
                   check=True, capture_output=True)
    js = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hu_kernel_render.mjs')

    def render(img):
        t8 = np.round(make_tex(img) * 255).astype(np.uint8)
        tex, res = os.path.join(work, 'tex.rgba'), os.path.join(work, 'out.rgba')
        open(tex, 'wb').write(np.concatenate([t8, np.full(t8.shape[:2] + (1,), 255, np.uint8)], -1).tobytes())
        subprocess.run(['node', js, os.path.join(out, 'mod_002.frag'), tex, str(W // 4), str(H // 4), str(W), str(H), res], check=True)
        return np.frombuffer(open(res, 'rb').read(), np.uint8).reshape(H, W, 4)[::-1, :, :3].astype(float) / 255
    return render


def main():
    use_glsl = len(sys.argv) >= 4 and sys.argv[1] == '--glsl'
    S = scenes(); work = tempfile.mkdtemp()
    print('twin of the generated GLSL' if not use_glsl else 'generated GLSL, WebGL2 (SwiftShader)')
    print(f"{'configuration':18s} {'text':>5s} {'grain':>5s} | {'halo':>5s} {'r10':>4s} {'r3':>4s} {'sat-':>5s} {'+lift':>6s} | {'ESF':>4s} | photo kept")
    for name, R, k in (('kernel 0, 30/30', 30, 0), ('kernel 0, 36/36', 36, 0), ('kernel 1, 30/30', 30, 1),
                       ('kernel 2, 30/30', 30, 2), ('kernel 3, 30/30', 30, 3)):
        mk = (lambda l: glsl(sys.argv[2], sys.argv[3], work, R, k, l)) if use_glsl else (lambda l: twin(R, k, l))
        f, fl = mk(0), mk(0.3)
        tm = np.mean([text_metrics(fl(S[s])) for s in ('white_on_black', 'black_on_white', 'white_on_blue')], axis=0)
        e, r10, r3, sat = halo(f(S['patch_blue']), S['patch_blue'])
        el = halo(fl(S['patch_blue']), lift(S['patch_blue']))[0]
        p1, p2 = photo_kept(f(S['photo']), S['photo'])
        print(f"{name:18s} {tm[0]:5.2f} {tm[1]:5.2f} | {e:4.1f}% {r10:4d} {r3:4d} {sat:4.1f}% {el:5.1f}% | {esf(f(S['step'])):4d} | {p1:4.1f}% / {p2:4.1f}%", flush=True)


if __name__ == '__main__':
    main()
