#!/usr/bin/env python3
"""Decode a screenshot taken with the composition probe (debug.oulg.sf.debug=5; HU_ALPHA_PROBE.md).

Tagged glass draws an opaque code in 8 px tiles (device pixels, so use the original full-resolution
screenshot, not a resized or re-encoded copy):
  data tiles  R = (1 - final alpha) x 12.75, G = (1 - paint alpha) x 12.75,
              B = (1 - blurred-texture alpha) x 12.75     (0 = opaque; 1/255 = 0.031 %)
  id tiles    R = id >> 8, G = id & 255, B = 255          (the program; oulg_sf logs PROBE shader=.. id=..)
usage: python3 -I hu_probe_decode.py SHOT.png [X0 Y0 X1 Y1]
Reports, over the region (the whole image if omitted), the transmission each factor implies and the
programs seen. A display colour transform can shift codes by a level or two: read 0 against non-zero,
and magnitudes to about +-0.1 %.
"""
import sys
from collections import Counter
import numpy as np
from PIL import Image

K = 12.75


def tiles(rgb, x0=0, y0=0):
    """Split pixels by tile geometry, not by colour (a display transform moves the codes: ids read as
    B ~245 on DZIF). Tiles are 8 x 8 device px; the phase is found as the parity class with the
    higher mean blue (id tiles carry B = 255 before any transform). Pixels within 1 px of a tile edge
    are dropped (resampling and antialiasing). Returns (data pixels, id pixels) as N x 3 ints."""
    h, w, _ = rgb.shape
    yy, xx = np.mgrid[0:h, 0:w]; X, Y = xx + x0, yy + y0
    par = ((X // 8 + Y // 8) % 2).astype(bool)
    inner = ((X % 8) >= 1) & ((X % 8) <= 6) & ((Y % 8) >= 1) & ((Y % 8) <= 6)
    b = rgb[..., 2].astype(float)
    idpar = b[par & inner].mean() > b[~par & inner].mean() if (par & inner).any() and (~par & inner).any() else True
    ids = inner & (par if idpar else ~par); data = inner & (~par if idpar else par)
    return rgb[data].astype(int), rgb[ids].astype(int)


def decode(rgb, x0=0, y0=0, scale=1.0):
    """Data pixels as fractions of transmission (final, paint, texture) and the id tiles' RGB.
    scale: the display transform's gain on a known code, estimated from the id tiles' blue
    (255 encoded); 1.0 leaves codes as read."""
    d, i = tiles(rgb, x0, y0)
    return d / 255.0 / K / scale, Counter(tuple(int(v) for v in px) for px in i)


def report(rgb, out=print, x0=0, y0=0):
    _, idpx = tiles(rgb, x0, y0)
    gain = float(np.median(idpx[:, 2])) / 255.0 if len(idpx) else 1.0
    data, ids = decode(rgb, x0, y0)
    if len(data) == 0:
        out('no data tiles: is the probe on (sf.debug=5 after a userspace restart) and the region over the glass?')
        return None
    names = ('final alpha', 'paint alpha (outputColor_S0.w)', 'blurred-texture alpha')
    med = np.median(data, axis=0); p95 = np.percentile(data, 95, axis=0); nz = (data > 0.5 / 255 / K).mean(axis=0)
    out(f'{len(data)} data pixels, {sum(ids.values())} id pixels')
    for i, n in enumerate(names):
        out(f'  1 - {n:32s} median {med[i] * 100:6.3f} %   p95 {p95[i] * 100:6.3f} %   non-zero on {nz[i] * 100:5.1f} % of pixels')
    if gain < 0.995:
        out(f'  id tiles read B = {gain * 255:.0f} for 255 encoded: a display transform of gain ~{gain:.3f} is applied after'
            f' the code; corrected final deficit {med[0] / gain * 100:.3f} %')
    for rgbv, c in ids.most_common(4):
        out(f'  id tile RGB {rgbv} ({c} px); as encoded ~ id {round(rgbv[0] / gain) << 8 | round(rgbv[1] / gain):04x} (exact id: the PROBE log)')
    return med


def selftest():
    """Round trip of the encoding (the GLSL positive control is probe_render_check.mjs)."""
    rng = np.random.default_rng(1)
    for fa, pa, ta in ((1.0, 1.0, 1.0), (0.98, 0.98, 1.0), (0.98, 1.0, 0.98), (0.95, 0.97, 0.98)):
        px = np.zeros((64, 64, 3), np.uint8)
        yy, xx = np.mgrid[0:64, 0:64]; data = ((xx // 8 + yy // 8) % 2) == 0
        enc = np.clip(np.round(np.array([1 - fa, 1 - pa, 1 - ta]) * K * 255 + rng.integers(-1, 2, 3) * 0), 0, 255)
        px[data] = enc; px[~data] = (0xad, 0x8b, 255)
        data_px, ids = decode(px); med = np.median(data_px, axis=0)
        assert np.allclose(med, [1 - fa, 1 - pa, 1 - ta], atol=0.0004), (fa, pa, ta, med)
        assert ids.most_common(1)[0][0] == (0xad, 0x8b, 255)
        # A display transform (ids read 156,36,245 on DZIF): geometry still separates the tiles.
        dim = (px * 0.96).astype(np.uint8); d2, i2 = tiles(dim)
        assert len(d2) == len(data_px) and np.median(i2[:, 2]) >= 244
    print('selftest: round trip within 0.04 % (opaque, 2 % final/paint, 2 % texture); tiles split by geometry under a 0.96 transform')


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--selftest':
        selftest(); sys.exit(0)
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    im = np.asarray(Image.open(sys.argv[1]).convert('RGB'))
    if len(sys.argv) >= 6:
        x0, y0, x1, y1 = map(int, sys.argv[2:6]); report(im[y0:y1, x0:x1], x0=x0, y0=y0)
    else:
        report(im)
