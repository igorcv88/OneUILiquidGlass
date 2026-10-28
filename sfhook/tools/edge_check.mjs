// Renders the edge light and the Quick Settings refraction (refract.h OULG_FS_GLOW, OULG_FS_EDGE_FMT,
// v0.27) in WebGL2 (headless Chromium, SwiftShader) and checks what they are for:
//   node edge_check.mjs <work dir> [reference dir] [png prefix]
// It builds refract_check and writes the synthetic dump's rewrites (no debug tint) with several
// parameter sets into <work dir>; it draws mod_002.frag (FillRRect) and mod_004.frag (clip, flipped).
// A reference dir holds an older refract.h and its refract_check.c (e.g. v0.26:
// git show <rev>:sfhook/refract.h, git show <rev>:sfhook/tools/refract_check.c); profiles 0-2 with
// their edge light at 0 must then draw bit for bit what the reference draws.
//
// Scenes: the compositor's input is drawn at full resolution, then downscaled 4x and lightly blurred
// as RenderEngine does. Card 720 x 300 px, corner radius 145 (a One UI 9 Wi-Fi pill).
// Checks, for both programs:
//  1. light alone (qslens 0): black adds nothing; every side and corner lit alike over grey (no lit
//     corner); the depth at which the light falls to 10 % of its outline value grows with the edge's
//     luma (dark: none, mid-grey: short, white: deep); along inward normals it only decreases (no
//     inner line, no band); a white bar crossing the bottom rim lights that stretch only, and the
//     light moves with the bar;
//  2. refraction alone (qslight 0), over a vertical luma ramp: the backdrop seen at depth d along the
//     bottom side comes from d + A bevel G(1 - d / bevel) (the slab shift), strictly ordered (no fold,
//     no duplicate), drawn at body (1 - S(d / bevel)) opacity, and nothing past the bevel;
//  3. profiles 0-2: their edge light changes the outer band only (none past 4 lambda) and nothing
//     over black; with the gains at 0, output equal to the reference;
//  4. FillRRect and clip agree.
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
const { chromium } = createRequire(import.meta.url)('playwright');

const here = path.dirname(fileURLToPath(import.meta.url));
const [work, refDir, png] = process.argv.slice(2);
const W = 800, H = 520, CARD = { x: 40, y: 110, w: 720, h: 300, r: 145 };
// The refract.h defaults this check is written against.
const QS = { depth: 18.0, lens: 0.30 };
const EDGE_DEPTH = 12.0;

// Rewrites of the synthetic dump for one parameter set, built from a refract.h + refract_check.c.
const exes = {};
function build(name, overrides, srcDir = null) {
  const dir = path.join(work, name);
  fs.mkdirSync(dir, { recursive: true });
  const key = srcDir || 'cur';
  if (!exes[key]) {
    const exe = path.join(work, srcDir ? 'rc_ref' : 'rc');
    const c = srcDir ? path.join(srcDir, 'refract_check.c') : path.join(here, 'refract_check.c');
    execFileSync('cc', ['-std=c99', '-I', srcDir || path.join(here, '..'), '-o', exe, c]);
    exes[key] = exe;
  }
  execFileSync(exes[key], [path.join(here, 'testdata/synthetic_dump.txt'), dir, '0', overrides || 'none=0']);
  const webgl = s => s.replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
  return { fill: webgl(fs.readFileSync(`${dir}/mod_002.frag`, 'utf8')), clip: webgl(fs.readFileSync(`${dir}/mod_004.frag`, 'utf8')) };
}
const sets = {
  cur: build('cur'),
  light: build('light', 'qslens=0'),
  // Opacity 1 at the outline: the source row is read from rgb / alpha, 8-bit premultiplied.
  lens: build('lens', 'qslight=0,qsbody=1'),
  dark: build('dark', 'hulight=0,kglight=0,shadelight=0'),
};
if (refDir) sets.ref = build('ref', null, path.resolve(refDir));

// Tag per profile: a radius fraction inside the profile's band (strength k = 0.1 + 1.1 (f - base) / 0.15).
const BANDS = [0.55, 0.30, 0.05, 0.80], FRAC = [0.60, 0.35, 0.10, 0.875];
const tag = pf => {
  const r = Math.floor(CARD.r) - 1 + FRAC[pf];
  return { r, kp: 2 * pf + 0.1 + 1.1 * (FRAC[pf] - BANDS[pf]) / 0.15 };
};

function harness(jobs, W, H, CARD) {
  const c = document.getElementById('c'); c.width = W; c.height = H;
  const scene = kind => {
    const src = document.createElement('canvas'); src.width = W; src.height = H;
    const g = src.getContext('2d');
    const grey = /^grey(\d+)$/.exec(kind);
    const gv = grey ? Math.round(+grey[1] * 2.55) : 0;
    g.fillStyle = kind === 'white' ? '#fff' : `rgb(${gv},${gv},${gv})`;
    g.fillRect(0, 0, W, H);
    const bar = /^bar(\d+)$/.exec(kind);
    if (bar) { g.fillStyle = '#fff'; g.fillRect(+bar[1], CARD.y + CARD.h - 30, 120, 60); }
    if (kind === 'ramp') for (let y = 0; y < H; y++) {
      const v = Math.round(255 * Math.min(1, Math.max(0, 0.1 + 0.8 * (y - CARD.y) / CARD.h)));
      g.fillStyle = `rgb(${v},${v},${v})`; g.fillRect(0, y, W, 1);
    }
    const tw = W / 4, th = H / 4, q = document.createElement('canvas'); q.width = tw; q.height = th;
    const qg = q.getContext('2d'); qg.filter = kind === 'ramp' ? 'none' : 'blur(1px)'; qg.drawImage(src, 0, 0, tw, th);
    return qg.getImageData(0, 0, tw, th).data;
  };
  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  const hs = [CARD.w / 2, CARD.h / 2];
  const vsFill = (kp, r) => `#version 300 es
in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${CARD.x + hs[0]}.0, ${CARD.y + hs[1]}.0) + p * vec2(${hs[0]}.0, ${hs[1]}.0);
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(${kp}, ${r}, ${hs[0]}.0, ${hs[1]}.0); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const vsClip = `#version 300 es
in vec2 p; out highp vec2 vTransformedCoords_2_S0;
void main() { vec2 dev = (p * 0.5 + 0.5) * vec2(${W}.0, ${H}.0);
  vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0); gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const program = (vs, fs) => {
    const pr = gl.createProgram();
    for (const [type, s] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
      const sh = gl.createShader(type); gl.shaderSource(sh, s); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
      gl.attachShader(pr, sh);
    }
    gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr);
    if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
    return pr;
  };
  const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const out = {}, scenes = {};
  for (const job of jobs) {
    if (!scenes[job.scene]) scenes[job.scene] = new Uint8Array(scene(job.scene));
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, W / 4, H / 4, 0, gl.RGBA, gl.UNSIGNED_BYTE, scenes[job.scene]);
    const pr = program(job.clip ? vsClip : vsFill(job.kp, job.r), job.fs); gl.useProgram(pr);
    gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
    if (job.clip) {
      gl.uniform2f(gl.getUniformLocation(pr, 'u_skRTFlip'), H, -1);
      gl.uniform4f(gl.getUniformLocation(pr, 'uinnerRect_S2'), CARD.x + job.r, CARD.y + job.r, CARD.x + CARD.w - job.r, CARD.y + CARD.h - job.r);
      gl.uniform2f(gl.getUniformLocation(pr, 'uradiusPlusHalf_S2'), job.r + 0.5, 1 / (job.r + 0.5));
    }
    gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    let s = ''; for (let i = 0; i < px.length; i += 0x8000) s += String.fromCharCode.apply(null, px.subarray(i, i + 0x8000));
    out[job.key] = { px: btoa(s), png: job.png ? c.toDataURL('image/png') : null };
  }
  return out;
}

const jobs = [];
const job = (set, prog, pf, scene, pngOut = false) => {
  const { r, kp } = tag(pf), key = `${set}/${prog}/p${pf}/${scene}`;
  jobs.push({ key, fs: sets[set][prog], clip: prog === 'clip', r, kp, scene, png: pngOut });
};
const PROGS = ['fill', 'clip'];
for (const prog of PROGS) {
  for (const s of ['black', 'grey25', 'grey50', 'grey75', 'white', 'bar300', 'bar500']) job('light', prog, 3, s);
  job('lens', prog, 3, 'ramp');
  for (const s of ['bar300', 'ramp']) job('cur', prog, 3, s, !!png);
  for (const pf of [0, 1, 2]) for (const s of ['black', 'grey50']) {
    job('cur', prog, pf, s); job('dark', prog, pf, s);
    if (sets.ref) job('ref', prog, pf, s);
  }
}

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage();
await page.setContent('<canvas id="c"></canvas>');
const res = await page.evaluate(`(${harness.toString()})(${JSON.stringify(jobs)}, ${W}, ${H}, ${JSON.stringify(CARD)})`);
await browser.close();
const img = Object.fromEntries(Object.entries(res).map(([k, v]) => [k, Uint8Array.from(Buffer.from(v.px, 'base64'))]));
if (png) for (const [k, v] of Object.entries(res)) if (v.png) fs.writeFileSync(`${png}${k.replace(/\//g, '_')}.png`, Buffer.from(v.png.split(',')[1], 'base64'));

let failures = 0;
const check = (ok, msg) => { if (!ok) { failures++; console.log('FAIL: ' + msg); } };
// readPixels rows are bottom-up; x, y here are top-down device pixels.
const at = (im, x, y, ch) => im[((H - 1 - y) * W + x) * 4 + ch] / 255;
const R3 = tag(3).r, BEVEL = Math.min(Math.max(0.42 * R3, 16), 56);
// Depth of a pixel centre from the outline (> 0 inside), the field's own formula, and its region.
const geo = (x, y, r = R3) => {
  const qx = x + 0.5 - (CARD.x + CARD.w / 2), qy = y + 0.5 - (CARD.y + CARD.h / 2);
  const wx = Math.abs(qx) - (CARD.w / 2 - r), wy = Math.abs(qy) - (CARD.h / 2 - r);
  const d = r - Math.hypot(Math.max(wx, 0), Math.max(wy, 0)) - Math.min(Math.max(wx, wy), 0);
  const key = wx > 0 && wy > 0 ? (qy < 0 ? 't' : 'b') + (qx < 0 ? 'l' : 'r') : wy > wx ? (qy < 0 ? 'top' : 'bottom') : (qx < 0 ? 'left' : 'right');
  return { d, key };
};
const cardPixels = [];
for (let y = CARD.y; y < CARD.y + CARD.h; y++) for (let x = CARD.x; x < CARD.x + CARD.w; x++) cardPixels.push([x, y, geo(x, y)]);
const mean = (im, pts, ch = 3) => pts.reduce((s, [x, y]) => s + at(im, x, y, ch), 0) / Math.max(pts.length, 1);
// Light band 1-3 px deep, by region (the four sides and four corner arcs).
const band = {};
for (const [x, y, g] of cardPixels) if (g.d >= 1 && g.d <= 3) (band[g.key] = band[g.key] || []).push([x, y]);
// Bottom side column at x: index d holds the pixel whose centre is d + 0.5 px deep.
const column = (im, x, ch = 3) => {
  const v = [];
  for (let d = 0; d < 90; d++) v.push(at(im, x, CARD.y + CARD.h - 1 - d, ch));
  return v;
};
const fallDepth = col => {
  if (col[0] < 2 / 255) return 0;
  for (let d = 0; d < col.length; d++) if (col[d] < 0.1 * col[0]) return d + 0.5;
  return col.length;
};

for (const prog of PROGS) {
  const L = s => img[`light/${prog}/p3/${s}`];
  // 1. Light alone (the region is transparent but for it: alpha is the light).
  let blackMax = 0;
  for (const [x, y] of cardPixels) blackMax = Math.max(blackMax, at(L('black'), x, y, 3));
  check(blackMax <= 1 / 255, `${prog}: black backdrop adds light (${blackMax.toFixed(3)})`);
  const g = Object.fromEntries(Object.entries(band).map(([k, p]) => [k, mean(L('grey50'), p)]));
  const gm = Object.values(g).reduce((a, b) => a + b, 0) / 8;
  check(gm > 0.15, `${prog}: grey rim too dark (${gm.toFixed(3)})`);
  for (const [k, v] of Object.entries(g)) check(Math.abs(v - gm) <= 0.10 * gm, `${prog}: grey ${k} ${v.toFixed(3)} vs mean ${gm.toFixed(3)}`);
  const depths = {};
  for (const s of ['grey25', 'grey50', 'grey75', 'white']) depths[s] = fallDepth(column(L(s), 400));
  check(depths.grey25 < depths.grey50 && depths.grey50 < depths.grey75 && depths.grey75 < depths.white,
    `${prog}: penetration does not grow with luma (${JSON.stringify(depths)})`);
  check(depths.white >= 2.5 * depths.grey50, `${prog}: white not much deeper than mid-grey (${depths.white} vs ${depths.grey50})`);
  check(depths.white <= 3 * QS.depth, `${prog}: white light too deep (${depths.white} px)`);
  // Monotonic along inward normals: bottom-side columns and a corner's diagonal.
  let rise = 0;
  for (const s of ['grey25', 'grey50', 'white', 'bar300']) for (const x of [200, 360, 400]) {
    const col = column(L(s), x);
    // From the pixel 1.5 px deep: the outline pixel itself is only partly covered.
    for (let d = 2; d < col.length; d++) rise = Math.max(rise, col[d] - col[d - 1]);
  }
  for (const s of ['grey50', 'white']) {
    const cx = CARD.x + R3, cy = CARD.y + CARD.h - R3, k = Math.SQRT1_2;
    let prev = Infinity;
    for (let d = 1.5; d < 80; d += 1) {
      const a = at(L(s), Math.floor(cx - (R3 - d) * k), Math.floor(cy + (R3 - d) * k), 3);
      rise = Math.max(rise, a - prev); prev = a;
    }
  }
  check(rise <= 2 / 255, `${prog}: light rises inward somewhere (inner line or band, +${rise.toFixed(3)})`);
  const bottom = (im, x0, x1) => mean(im, band.bottom.filter(([x]) => x >= x0 && x <= x1));
  const top = (im, x0, x1) => mean(im, band.top.filter(([x]) => x >= x0 && x <= x1));
  const A = L('bar300'), B = L('bar500');
  const litA = bottom(A, 330, 390), awayA = bottom(A, 0, 240), topA = top(A, 330, 390);
  const litB = bottom(B, 530, 590), oldB = bottom(B, 330, 390);
  check(litA > 0.25, `${prog}: white bar does not light the bottom rim (${litA.toFixed(3)})`);
  check(awayA <= 0.02 && topA <= 0.02, `${prog}: light spreads (60+ px away ${awayA.toFixed(3)}, top rim ${topA.toFixed(3)})`);
  check(litB > 0.25 && oldB <= 0.02, `${prog}: light does not follow the bar (new place ${litB.toFixed(3)}, old place ${oldB.toFixed(3)})`);
  let bodyMax = 0;
  for (const [x, y, gg] of cardPixels) if (gg.d > 4 * QS.depth) bodyMax = Math.max(bodyMax, at(L('white'), x, y, 3));
  check(bodyMax <= 1 / 255, `${prog}: light past 4 lambda over white (${bodyMax.toFixed(3)})`);
  console.log(`${prog} light: black ${blackMax.toFixed(3)}, grey rim ${gm.toFixed(3)} (${Object.entries(g).map(([k, v]) => `${k} ${v.toFixed(3)}`).join(' ')}), `
    + `10 % depth px ${Object.entries(depths).map(([k, v]) => `${k} ${v}`).join(' ')}, max inward rise ${rise.toFixed(3)}, `
    + `bar ${litA.toFixed(3)} (60+ px away ${awayA.toFixed(3)}, top ${topA.toFixed(3)}), moved bar ${litB.toFixed(3)} (old place ${oldB.toFixed(3)}), `
    + `past 4 lambda ${bodyMax.toFixed(3)}`);

  // 2. Refraction alone over the ramp v = 0.1 + 0.8 (y - CARD.y) / h: the source row is known.
  const R = img[`lens/${prog}/p3/ramp`];
  const srcY = v => CARD.y + (v - 0.1) / 0.8 * CARD.h;
  const smoother = u => { u = Math.min(Math.max(u, 0), 1); return u * u * u * (u * (u * 6 - 15) + 10); };
  // One 8-bit step of the ramp is 1.5 px of source row; divided by the alpha it was stored at.
  let worst = 0, order = Infinity, alphaErr = 0, past = 0;
  const rows = [];
  for (let d = 0; d < 100; d++) {
    const y = CARD.y + CARD.h - 1 - d, depth = d + 0.5, x = 400;
    const a = at(R, x, y, 3);
    if (depth >= BEVEL + 1) { past = Math.max(past, a); continue; }
    alphaErr = Math.max(alphaErr, Math.abs(a - (1 - smoother(depth / BEVEL))));
    if (a < 0.25) continue;
    const t = Math.max(0, 1 - depth / BEVEL), got = srcY(at(R, x, y, 0) / a);
    worst = Math.max(worst, Math.abs(got - ((y + 0.5) - QS.lens * BEVEL * t * t * (3 - 2 * t))) - 1.5 / a);
    rows.push(got);
  }
  // A fold maps two depths to one row and reverses the order: over 4-px means, a deeper pixel
  // must sample a higher row.
  for (let i = 4; i + 4 <= rows.length; i += 4) {
    const m0 = rows.slice(i - 4, i).reduce((a, b) => a + b) / 4, m1 = rows.slice(i, i + 4).reduce((a, b) => a + b) / 4;
    order = Math.min(order, m0 - m1);
  }
  check(worst <= 1.0, `${prog}: refraction source off the slab shift by ${worst.toFixed(2)} px beyond 8-bit precision`);
  check(order > 0, `${prog}: refraction folds (source rows out of order, min step ${order.toFixed(3)} px)`);
  check(alphaErr <= 3 / 255, `${prog}: refracted body opacity off by ${alphaErr.toFixed(3)}`);
  check(past <= 1 / 255, `${prog}: refracted body past the bevel (${past.toFixed(3)})`);
  console.log(`${prog} refraction: bevel ${BEVEL.toFixed(1)} px, max shift ${(QS.lens * BEVEL).toFixed(1)} px, source error beyond 8-bit precision ${worst.toFixed(2)} px, `
    + `min source step ${(order / 4).toFixed(3)} px/px, opacity error ${alphaErr.toFixed(3)}, past the bevel ${past.toFixed(3)}`);

  // 3. Profiles 0-2.
  for (const pf of [0, 1, 2]) {
    const cur = img[`cur/${prog}/p${pf}/grey50`], dark = img[`dark/${prog}/p${pf}/grey50`];
    const curB = img[`cur/${prog}/p${pf}/black`], darkB = img[`dark/${prog}/p${pf}/black`];
    const rp = tag(pf).r;
    let deep = 0, outline = 0, n = 0, blackDiff = 0;
    for (const [x, y] of cardPixels) {
      const { d } = geo(x, y, rp);
      const i = ((H - 1 - y) * W + x) * 4;
      let m = 0;
      for (let ch = 0; ch < 3; ch++) { m = Math.max(m, Math.abs(cur[i + ch] - dark[i + ch])); blackDiff = Math.max(blackDiff, Math.abs(curB[i + ch] - darkB[i + ch])); }
      if (d > 4 * EDGE_DEPTH) deep = Math.max(deep, m);
      else if (d >= 1 && d <= 3) { outline += (cur[i] - dark[i]) / 255; n++; }
    }
    outline /= Math.max(n, 1);
    check(deep === 0, `profile ${pf} ${prog}: edge light changes the body (max ${deep}/255 past ${4 * EDGE_DEPTH} px)`);
    check(blackDiff <= 1, `profile ${pf} ${prog}: edge light over black (${blackDiff}/255)`);
    check(outline > 0.02, `profile ${pf} ${prog}: no edge light over grey (${outline.toFixed(3)})`);
    let refDiff = null;
    if (sets.ref) {
      refDiff = 0;
      for (const s of ['black', 'grey50']) {
        const a = img[`dark/${prog}/p${pf}/${s}`], b = img[`ref/${prog}/p${pf}/${s}`];
        for (let i = 0; i < a.length; i++) refDiff = Math.max(refDiff, Math.abs(a[i] - b[i]));
      }
      check(refDiff === 0, `profile ${pf} ${prog}: gain 0 differs from the reference by ${refDiff}/255`);
    }
    console.log(`profile ${pf} ${prog}: edge light over grey +${outline.toFixed(3)} at 1-3 px, past ${4 * EDGE_DEPTH} px ${deep}/255, over black ${blackDiff}/255`
      + (refDiff === null ? '' : `, gain 0 vs reference ${refDiff}/255`));
  }
}
// 4. The two programs draw the same card (inside it: outside, the harness gives neither coverage).
let diff = 0;
for (const key of ['light/%/p3/grey50', 'light/%/p3/bar300', 'lens/%/p3/ramp', 'cur/%/p3/bar300']) {
  const a = img[key.replace('%', 'fill')], b = img[key.replace('%', 'clip')];
  for (const [x, y, g] of cardPixels) {
    if (g.d < 1) continue;
    const i = ((H - 1 - y) * W + x) * 4;
    for (let ch = 0; ch < 4; ch++) diff = Math.max(diff, Math.abs(a[i + ch] - b[i + ch]) / 255);
  }
}
check(diff <= 0.10, `fill vs clip differ by ${diff.toFixed(3)}`);
console.log(`fill vs clip: max difference ${diff.toFixed(3)}`);
console.log(`edge light: failures = ${failures}`);
process.exit(failures ? 1 : 0);
