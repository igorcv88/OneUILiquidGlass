// Renders the rewritten fragment programs of a refract_check output directory in WebGL2 (headless
// Chromium) over the same synthetic blur texture and the same tagged card, and compares them:
//   node render_check.mjs <dir> [<baseline dir>] [png prefix]
// <dir> must hold mod_002.frag (FillRRect) and mod_004.frag (clip, flipped) from the synthetic dump.
// Each is drawn with the heads-up profile (fill, clip) and the lockscreen profile (fillKg, clipKg);
// the baseline's with the heads-up profile (baseFill, baseClip).
// Prints, inside the card: max/mean |FillRRect - clip| and the body's grain and residual structure
// (see quality()) for both programs and, with a baseline dir, for the baseline's too. Build the
// directories without the debug tint (refract_check <dump> <dir> 0).
// Playwright resolves through require so NODE_PATH works (global installs).
import { createRequire } from 'module';
import fs from 'fs';
const { chromium } = createRequire(import.meta.url)('playwright');

const [dir, base, png] = process.argv.slice(2);
const W = 800, H = 520, CARD = { x: 40, y: 110, w: 720, h: 300, r: 92 }, K = 0.30;
// SemBlurBridge.sfTag: profile 0 (heads-up) band starts at 0.55, profile 1 (lockscreen) at 0.30.
const tag = (r, k, pf) => {
  const s = Math.min(1, Math.max(0, (k - 0.1) / 1.1));
  if (!pf) return Math.floor(r) - 1 + 0.55 + 0.15 * s;
  const t = Math.floor(r) + 0.30 + 0.15 * s;
  return t <= r ? t : t - 1;
};
const webgl = src => src.replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
const load = (d, n) => { const f = `${d}/mod_${n}.frag`; return fs.existsSync(f) ? webgl(fs.readFileSync(f, 'utf8')) : null; };
// name -> [source, clip program?, profile]
const programs = { fill: [load(dir, '002'), false, 0], clip: [load(dir, '004'), true, 0],
                   fillKg: [load(dir, '002'), false, 1], clipKg: [load(dir, '004'), true, 1] };
if (base) { programs.baseFill = [load(base, '002'), false, 0]; programs.baseClip = [load(base, '004'), true, 0]; }

const page = async (browser) => {
  const p = await browser.newPage();
  await p.setContent('<canvas id=c></canvas>');
  return p;
};

function harness(programs, W, H, CARD, tags, K) {
  const c = document.getElementById('c'); c.width = W; c.height = H;
  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  // Quarter-resolution source, like the compositor's downscaled blur: sharp shapes and text-like
  // stripes, then a light box blur (radius 1 texel) standing in for a small-radius Kawase pass.
  const tw = W / 4, th = H / 4, raw = new Float32Array(tw * th * 3);
  for (let y = 0; y < th; y++) for (let x = 0; x < tw; x++) {
    const i = (y * tw + x) * 3;
    const stripe = ((x >> 1) + (y >> 2)) % 3 === 0 ? 1 : 0, blob = Math.hypot(x - 90, y - 60) < 28 ? 1 : 0;
    raw[i] = 0.15 + 0.7 * stripe * (x < 120 ? 1 : 0.2); raw[i + 1] = 0.2 + 0.6 * blob; raw[i + 2] = 0.3 + 0.5 * ((x ^ y) & 8 ? 1 : 0);
  }
  const tex = new Uint8Array(tw * th * 4);
  for (let y = 0; y < th; y++) for (let x = 0; x < tw; x++) for (let ch = 0; ch < 3; ch++) {
    let s = 0, n = 0;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const xx = Math.min(tw - 1, Math.max(0, x + dx)), yy = Math.min(th - 1, Math.max(0, y + dy)); s += raw[(yy * tw + xx) * 3 + ch]; n++;
    }
    tex[(y * tw + x) * 4 + ch] = Math.round(255 * s / n); tex[(y * tw + x) * 4 + 3] = 255;
  }
  const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tw, th, 0, gl.RGBA, gl.UNSIGNED_BYTE, tex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  const hs = [CARD.w / 2, CARD.h / 2], cx = CARD.x + hs[0], cy = CARD.y + hs[1];
  // Pixel positions are top-down (Skia device space); clip space flips y.
  const vsFill = `#version 300 es
uniform float uKp, uR; in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${cx.toFixed(1)}, ${cy.toFixed(1)}) + p * vec2(${hs[0].toFixed(1)}, ${hs[1].toFixed(1)});
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(uKp, uR, ${hs[0].toFixed(1)}, ${hs[1].toFixed(1)}); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const vsClip = `#version 300 es
in vec2 p; out highp vec2 vTransformedCoords_2_S0;
void main() { vec2 dev = (p * 0.5 + 0.5) * vec2(${W}.0, ${H}.0);
  vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0); gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const build = (vs, fs) => {
    const pr = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
      const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      gl.attachShader(pr, s);
    }
    gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr);
    if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
    return pr;
  };
  const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const out = {};
  for (const [name, [fs, clip, pf]] of Object.entries(programs)) {
    if (!fs) continue;
    const rTag = tags[pf];
    const pr = build(clip ? vsClip : vsFill, fs); gl.useProgram(pr);
    if (!clip) { gl.uniform1f(gl.getUniformLocation(pr, 'uKp'), K + 2 * pf); gl.uniform1f(gl.getUniformLocation(pr, 'uR'), rTag); }
    gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
    if (clip) {
      gl.uniform2f(gl.getUniformLocation(pr, 'u_skRTFlip'), H, -1);
      gl.uniform4f(gl.getUniformLocation(pr, 'uinnerRect_S2'), CARD.x + rTag, CARD.y + rTag, CARD.x + CARD.w - rTag, CARD.y + CARD.h - rTag);
      gl.uniform2f(gl.getUniformLocation(pr, 'uradiusPlusHalf_S2'), rTag + 0.5, 1 / (rTag + 0.5));
    }
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    out[name] = { px: Array.from(px), png: c.toDataURL('image/png') };
  }
  return out;
}

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const p = await page(browser);
const tags = [tag(CARD.r, K, 0), tag(CARD.r, K, 1)];
const res = await p.evaluate(`(${harness.toString()})(${JSON.stringify(programs)}, ${W}, ${H}, ${JSON.stringify(CARD)}, ${JSON.stringify(tags)}, ${K})`);
await browser.close();

// Pixel (x, y) top-down; readPixels rows are bottom-up.
const at = (px, x, y, ch) => px[((H - 1 - y) * W + x) * 4 + ch];
const inside = (x, y, margin) => {
  const hx = CARD.w / 2, hy = CARD.h / 2, qx = Math.abs(x + 0.5 - CARD.x - hx), qy = Math.abs(y + 0.5 - CARD.y - hy);
  const wx = qx - (hx - CARD.r), wy = qy - (hy - CARD.r);
  return CARD.r - Math.hypot(Math.max(wx, 0), Math.max(wy, 0)) - Math.min(Math.max(wx, wy), 0) > margin;
};
const body = (x, y) => inside(x, y, 1.5 * 0.42 * CARD.r);
// Body quality, per channel over the body (past the ramp): grain = RMS(img - box3(img)), pixel-scale
// noise; structure = RMS(box5(img) - box41(img)), background detail left at 5-40 px (the blotches of
// an under-filtered body). Lower is smoother for both.
function boxBlur(px, ch, rad) {
  const src = new Float64Array(W * H), tmp = new Float64Array(W * H), dst = new Float64Array(W * H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) src[y * W + x] = at(px, x, y, ch);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let s = 0, n = 0; for (let d = -rad; d <= rad; d++) { const xx = x + d; if (xx >= 0 && xx < W) { s += src[y * W + xx]; n++; } } tmp[y * W + x] = s / n;
  }
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let s = 0, n = 0; for (let d = -rad; d <= rad; d++) { const yy = y + d; if (yy >= 0 && yy < H) { s += tmp[yy * W + x]; n++; } } dst[y * W + x] = s / n;
  }
  return { src, dst };
}
function quality(px) {
  let g = 0, st = 0, n = 0;
  for (let ch = 0; ch < 3; ch++) {
    const b1 = boxBlur(px, ch, 1), b2 = boxBlur(px, ch, 2).dst, b20 = boxBlur(px, ch, 20).dst;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (body(x, y) && inside(x, y, 1.5 * 0.42 * CARD.r + 20)) {
      const i = y * W + x; g += (b1.src[i] - b1.dst[i]) ** 2; st += (b2[i] - b20[i]) ** 2; n++;
    }
  }
  return { grain: +Math.sqrt(g / n).toFixed(3), structure: +Math.sqrt(st / n).toFixed(3) };
}
// within(x, y) picks the pixels compared; by default the whole card minus its 2 px antialiased edge.
function diff(a, b, within = (x, y) => inside(x, y, 2)) {
  let max = 0, sum = 0, n = 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (within(x, y)) for (let ch = 0; ch < 3; ch++) {
    const d = Math.abs(at(a, x, y, ch) - at(b, x, y, ch)); max = Math.max(max, d); sum += d; n++;
  }
  return { max, mean: +(sum / n).toFixed(3) };
}
const report = {};
for (const [name, r] of Object.entries(res)) {
  report[name] = quality(r.px);
  if (png) fs.writeFileSync(`${png}${name}.png`, Buffer.from(r.png.split(',')[1], 'base64'));
}
if (res.fill && res.clip) report.fillVsClip = diff(res.fill.px, res.clip.px);
if (res.fillKg && res.clipKg) report.fillKgVsClipKg = diff(res.fillKg.px, res.clipKg.px);
// The lockscreen profile keeps the heads-up lens: the rim band (depth below a quarter bevel, where
// neither body blur nor vibrancy act) must match.
const bevel = Math.min(Math.max(0.42 * CARD.r, 16), 56);
if (res.fill && res.fillKg) report.rimFillVsFillKg = diff(res.fill.px, res.fillKg.px, (x, y) => inside(x, y, 2) && !inside(x, y, 0.25 * bevel - 1));
if (res.fill && res.baseFill) report.fillVsBaseFill = diff(res.fill.px, res.baseFill.px);
if (res.fill && res.baseClip) report.fillVsBaseClip = diff(res.fill.px, res.baseClip.px);
console.log(JSON.stringify(report, null, 1));
