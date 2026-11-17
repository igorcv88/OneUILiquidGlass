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


def decode(rgb):
    """rgb: H x W x 3 uint8. Returns (data pixels N x 3 as fractions of transmission, id counter)."""
    flat = rgb.reshape(-1, 3).astype(int)
    idmask = flat[:, 2] >= 254
    data = flat[~idmask] / 255.0 / K
    ids = Counter(int(r) << 8 | int(g) for r, g, _ in flat[idmask])
    return data, ids


def report(rgb, out=print):
    data, ids = decode(rgb)
    if len(data) == 0:
        out('no data tiles: is the probe on (sf.debug=5 after a userspace restart) and the region over the glass?')
        return None
    names = ('final alpha', 'paint alpha (outputColor_S0.w)', 'blurred-texture alpha')
    med = np.median(data, axis=0); p95 = np.percentile(data, 95, axis=0); nz = (data > 0.5 / 255 / K).mean(axis=0)
    out(f'{len(data)} data pixels, {sum(ids.values())} id pixels')
    for i, n in enumerate(names):
        out(f'  1 - {n:32s} median {med[i] * 100:6.3f} %   p95 {p95[i] * 100:6.3f} %   non-zero on {nz[i] * 100:5.1f} % of pixels')
    for pid, c in ids.most_common(6):
        out(f'  program id {pid:04x}: {c} px')
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
        assert ids.most_common(1)[0][0] == 0xad8b
    print('selftest: encoding round trip within 0.04 % for opaque, 2 % final/paint and 2 % texture deficits')


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--selftest':
        selftest(); sys.exit(0)
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    im = np.asarray(Image.open(sys.argv[1]).convert('RGB'))
    if len(sys.argv) >= 6:
        x0, y0, x1, y1 = map(int, sys.argv[2:6]); im = im[y0:y1, x0:x1]
    report(im)
