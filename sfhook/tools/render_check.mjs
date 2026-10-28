// Renders the rewritten fragment programs of refract_check output directories in WebGL2 (headless
// Chromium, SwiftShader) over a controlled reference backdrop and compares them:
//   node render_check.mjs <dir> [<baseline dir>] [png prefix]
// Build the directories without the debug tint: refract_check <dump> <dir> 0. <dir> must hold
// mod_002.frag (FillRRect) and mod_004.frag (clip, flipped) from tools/testdata/synthetic_dump.txt.
//
// Backdrop: a photographic-like gradient with a bright half (left) and a dark half (right), and
// large high-contrast text crossing the card's rim, rendered at full resolution and turned into the
// compositor's input the way RenderEngine does: downscaled 4x and lightly blurred.
// Programs: fill/clip with the heads-up profile 0, the lockscreen profile 1 (fillKg, clipKg) and the
// shade profile 2 for light text (fillShade, clipShade), the untagged card (fillPlain); the
// baseline's fill/clip with profile 0 (baseFill, baseClip). lumaPlain/lumaShade: body luma over the
// bright and the dark half, untagged vs profile 2.
// Reports, per program:
//  - grain: RMS(img - box3(img)) in the body, pixel-scale noise;
//  - structure: RMS(box5 - box41) in the body, backdrop detail left at 5-40 px (blotches);
//  - temporal: mean |frame - previous frame| in the card for 1/3 px moves of the card over a still
//    backdrop (lower = steadier under motion; the exact content change is part of it for all);
//  - ms: SwiftShader time per full-card draw, a relative cost only (CPU rasterizer, not a GPU).
//  - fillDepth: profile 0's backdrop detail per depth band from the outline (see depthProfile);
// and comparisons: fill vs clip per profile (same card, two programs), heads-up vs its baseline, and
// the straight rims' luminance of profile 1 against profile 0 or, with BASE_PF=1, against the
// baseline's profile 1 (built e.g. with rim=0,spec=0 to isolate the lighting; see rimLift).
import { createRequire } from 'module';
import fs from 'fs';
const { chromium } = createRequire(import.meta.url)('playwright');

const [dir, base, png] = process.argv.slice(2);
// Lens strengths as on the device: sflens 0.30 (profile 0), kglens 0.40 (profile 1).
// Profile 2 (shade legibility) carries the text colour in its strength: 0.3 = light (dark-theme) text.
const W = 800, H = 520, CARD = { x: 40, y: 110, w: 720, h: 300, r: 92 }, KS = [0.30, 0.40, 0.30], MOVES = 4;
// SemBlurBridge.sfTag: profile 0 band starts at 0.55; profile 1 takes the largest radius <= r in [0.30, 0.45].
const tag = (r, k, pf) => {
  const s = Math.min(1, Math.max(0, (k - 0.1) / 1.1));
  if (!pf) return Math.floor(r) - 1 + 0.55 + 0.15 * s;
  const t = Math.floor(r) + (pf === 1 ? 0.30 : 0.05) + 0.15 * s;
  return t <= r ? t : t - 1;
};
const webgl = src => src.replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
const load = (d, n) => { const f = `${d}/mod_${n}.frag`; return fs.existsSync(f) ? webgl(fs.readFileSync(f, 'utf8')) : null; };
// name -> [source, clip program?, profile (-1: untagged)]
const programs = { fill: [load(dir, '002'), false, 0], clip: [load(dir, '004'), true, 0],
                   fillKg: [load(dir, '002'), false, 1], clipKg: [load(dir, '004'), true, 1],
                   fillPlain: [load(dir, '002'), false, -1], fillShade: [load(dir, '002'), false, 2], clipShade: [load(dir, '004'), true, 2] };
// BASE_PF=1 draws the baseline with profile 1 instead (baseFillKg), e.g. to isolate one of its terms.
const basePf = process.env.BASE_PF === '1' ? 1 : 0;
if (base && !basePf) { programs.baseFill = [load(base, '002'), false, 0]; programs.baseClip = [load(base, '004'), true, 0]; }
if (base && basePf) programs.baseFillKg = [load(base, '002'), false, 1];

function harness(programs, W, H, CARD, tags, KS, MOVES) {
  const c = document.getElementById('c'); c.width = W; c.height = H;
  // Reference backdrop at full resolution.
  const src = document.createElement('canvas'); src.width = W; src.height = H;
  const g = src.getContext('2d');
  const sky = g.createLinearGradient(0, 0, W / 2, H); sky.addColorStop(0, '#f6e7c8'); sky.addColorStop(0.5, '#e7b38a'); sky.addColorStop(1, '#8fb7e0');
  g.fillStyle = sky; g.fillRect(0, 0, W / 2, H);
  const night = g.createLinearGradient(W / 2, 0, W, H); night.addColorStop(0, '#0d1b2a'); night.addColorStop(0.6, '#1b263b'); night.addColorStop(1, '#3a1f4d');
  g.fillStyle = night; g.fillRect(W / 2, 0, W / 2, H);
  for (const [x, y, rad, col] of [[150, 260, 70, '#2a9d8f'], [600, 240, 60, '#ffb703'], [420, 360, 50, '#e63946']]) {
    const rg = g.createRadialGradient(x, y, 0, x, y, rad); rg.addColorStop(0, col); rg.addColorStop(1, col + '00'); g.fillStyle = rg; g.fillRect(x - rad, y - rad, 2 * rad, 2 * rad);
  }
  g.font = 'bold 64px sans-serif'; g.textBaseline = 'middle';
  g.fillStyle = '#111'; g.fillText('Glass 12:45', 20, CARD.y + 4);       // crosses the top rim, bright half
  g.fillStyle = '#fff'; g.fillText('WhatsApp', W / 2 + 20, CARD.y + CARD.h - 4); // crosses the bottom rim, dark half
  g.font = '28px sans-serif'; g.fillStyle = '#222'; g.fillText('Large high-contrast text over the rim', 60, CARD.y + 150);
  g.fillStyle = '#eee'; g.fillText('Reply   Mark as read', W / 2 + 40, CARD.y + 150);
  // The compositor's input: a quarter-resolution, lightly blurred copy (a small-radius blur pass).
  const tw = W / 4, th = H / 4, q = document.createElement('canvas'); q.width = tw; q.height = th;
  const qg = q.getContext('2d'); qg.filter = 'blur(1px)'; qg.drawImage(src, 0, 0, tw, th);
  const tex = qg.getImageData(0, 0, tw, th).data;
  const plain = g.getImageData(0, 0, W, H).data;

  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tw, th, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(tex));
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  const hs = [CARD.w / 2, CARD.h / 2];
  // Pixel positions are top-down (Skia device space); clip space flips y. uOff moves the card.
  const vsFill = `#version 300 es
uniform float uKp, uR; uniform vec2 uOff; in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${CARD.x + hs[0]}.0, ${CARD.y + hs[1]}.0) + uOff + p * vec2(${hs[0]}.0, ${hs[1]}.0);
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(uKp, uR, ${hs[0]}.0, ${hs[1]}.0); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const vsClip = `#version 300 es
in vec2 p; out highp vec2 vTransformedCoords_2_S0;
void main() { vec2 dev = (p * 0.5 + 0.5) * vec2(${W}.0, ${H}.0);
  vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0); gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const build = (vs, fs) => {
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
  // The plain backdrop as the compositor would show it without the card: bilinear upscale of the input.
  // Pixels travel as base64: plain arrays of every frame of every program exhaust node's heap.
  const b64 = a => { let t = ''; for (let i = 0; i < a.length; i += 0x8000) t += String.fromCharCode.apply(null, a.subarray(i, i + 0x8000)); return btoa(t); };
  const out = { plain: b64(new Uint8Array(plain.buffer)) };
  for (const [name, [fs, clip, pf]] of Object.entries(programs)) {
    if (!fs) continue;
    const rTag = pf < 0 ? CARD.r : tags[pf];
    const pr = build(clip ? vsClip : vsFill, fs); gl.useProgram(pr);
    gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
    const draw = off => {
      if (clip) {
        gl.uniform2f(gl.getUniformLocation(pr, 'u_skRTFlip'), H, -1);
        gl.uniform4f(gl.getUniformLocation(pr, 'uinnerRect_S2'), CARD.x + off + rTag, CARD.y + rTag, CARD.x + off + CARD.w - rTag, CARD.y + CARD.h - rTag);
        gl.uniform2f(gl.getUniformLocation(pr, 'uradiusPlusHalf_S2'), rTag + 0.5, 1 / (rTag + 0.5));
      } else {
        gl.uniform1f(gl.getUniformLocation(pr, 'uKp'), pf < 0 ? 0 : KS[pf] + 2 * pf); gl.uniform1f(gl.getUniformLocation(pr, 'uR'), rTag);
        gl.uniform2f(gl.getUniformLocation(pr, 'uOff'), off, 0);
      }
      gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    const frames = [];
    for (let m = 0; m < MOVES; m++) {
      draw(m / 3);
      const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px); frames.push(b64(px));
      if (m === 0) out[name] = { px: frames[0], png: c.toDataURL('image/png') };
    }
    out[name].frames = frames;
    // readPixels blocks until the draws are done (gl.finish does not, on SwiftShader through ANGLE).
    const one = new Uint8Array(4);
    draw(0); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, one);
    const t0 = performance.now(); for (let i = 0; i < 6; i++) draw(0); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, one);
    out[name].ms = (performance.now() - t0) / 6;
  }
  return out;
}

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const p = await browser.newPage();
await p.setContent('<canvas id=c></canvas>');
const tags = [tag(CARD.r, KS[0], 0), tag(CARD.r, KS[1], 1), tag(CARD.r, KS[2], 2)];
const res = await p.evaluate(`(${harness.toString()})(${JSON.stringify(programs)}, ${W}, ${H}, ${JSON.stringify(CARD)}, ${JSON.stringify(tags)}, ${JSON.stringify(KS)}, ${MOVES})`);
await browser.close();
const bytes = t => new Uint8Array(Buffer.from(t, 'base64'));
res.plain = bytes(res.plain);
for (const [name, r] of Object.entries(res)) if (name !== 'plain') { r.frames = r.frames.map(bytes); r.px = r.frames[0]; }

// readPixels rows are bottom-up; the plain canvas rows are top-down.
const at = (px, x, y, ch) => px[((H - 1 - y) * W + x) * 4 + ch];
const plainAt = (x, y, ch) => res.plain[(y * W + x) * 4 + ch];
const depthOf = (x, y) => {
  const hx = CARD.w / 2, hy = CARD.h / 2, qx = Math.abs(x + 0.5 - CARD.x - hx), qy = Math.abs(y + 0.5 - CARD.y - hy);
  const wx = qx - (hx - CARD.r), wy = qy - (hy - CARD.r);
  return CARD.r - Math.hypot(Math.max(wx, 0), Math.max(wy, 0)) - Math.min(Math.max(wx, wy), 0);
};
const bevel = Math.min(Math.max(0.42 * CARD.r, 16), 56);
const inside = (x, y, margin) => depthOf(x, y) > margin;
const bodyPx = (x, y) => inside(x, y, 1.5 * bevel + 20);
const rimPx = (x, y) => { const d = depthOf(x, y); return d > 2 && d < 0.6 * bevel; };
function boxBlur(px, ch, rad) {
  const s = new Float64Array(W * H), tmp = new Float64Array(W * H), dst = new Float64Array(W * H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) s[y * W + x] = at(px, x, y, ch);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { let a = 0, n = 0; for (let d = -rad; d <= rad; d++) { const xx = x + d; if (xx >= 0 && xx < W) { a += s[y * W + xx]; n++; } } tmp[y * W + x] = a / n; }
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { let a = 0, n = 0; for (let d = -rad; d <= rad; d++) { const yy = y + d; if (yy >= 0 && yy < H) { a += tmp[yy * W + x]; n++; } } dst[y * W + x] = a / n; }
  return { s, dst };
}
function quality(px) {
  let gr = 0, st = 0, n = 0;
  for (let ch = 0; ch < 3; ch++) {
    const b1 = boxBlur(px, ch, 1), b2 = boxBlur(px, ch, 2).dst, b20 = boxBlur(px, ch, 20).dst;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (bodyPx(x, y)) { const i = y * W + x; gr += (b1.s[i] - b1.dst[i]) ** 2; st += (b2[i] - b20[i]) ** 2; n++; }
  }
  return { grain: +Math.sqrt(gr / n).toFixed(3), structure: +Math.sqrt(st / n).toFixed(3) };
}
const luma = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
// Lighting alone: rim luminance of a profile-1 render minus the profile-0 render of the same card
// (the two refract alike; the difference is profile 1's reflection and specular), on the lit top edge
// and the unlit bottom edge, over the bright (left) and dark (right) half.
function rimLift(px, ref) {
  const acc = {};
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (rimPx(x, y) && Math.abs(x + 0.5 - CARD.x - CARD.w / 2) < CARD.w / 2 - CARD.r) {
    const k = (y < CARD.y + CARD.h / 2 ? 'top' : 'bottom') + (x < W / 2 ? 'Bright' : 'Dark');
    const d = luma(at(px, x, y, 0), at(px, x, y, 1), at(px, x, y, 2)) - luma(at(ref, x, y, 0), at(ref, x, y, 1), at(ref, x, y, 2));
    acc[k] = acc[k] || [0, 0]; acc[k][0] += d; acc[k][1]++;
  }
  return Object.fromEntries(Object.entries(acc).map(([k, [a, n]]) => [k, +(a / n).toFixed(2)]));
}
function temporal(frames) {
  let s = 0, n = 0;
  for (let m = 1; m < frames.length; m++) for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (inside(x, y, 3) && inside(x - 1, y, 3)) for (let ch = 0; ch < 3; ch++) { s += Math.abs(at(frames[m], x, y, ch) - at(frames[m - 1], x, y, ch)); n++; }
  return +(s / n).toFixed(3);
}
function diff(a, b, within = (x, y) => inside(x, y, 2)) {
  let max = 0, sum = 0, n = 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (within(x, y)) for (let ch = 0; ch < 3; ch++) { const d = Math.abs(at(a, x, y, ch) - at(b, x, y, ch)); max = Math.max(max, d); sum += d; n++; }
  return { max, mean: +(sum / n).toFixed(3) };
}
// Shade legibility: body luma (0-1) over the bright and the dark half, mean and max.
function lumaStats(px) {
  const acc = { bright: [0, 0, 0], dark: [0, 0, 0] };
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (inside(x, y, 3)) {
    const k = x < W / 2 ? 'bright' : 'dark', l = luma(at(px, x, y, 0), at(px, x, y, 1), at(px, x, y, 2)) / 255;
    acc[k][0] += l; acc[k][1]++; acc[k][2] = Math.max(acc[k][2], l);
  }
  return Object.fromEntries(Object.entries(acc).map(([k, [a, n, m]]) => [k, { mean: +(a / n).toFixed(3), max: +m.toFixed(3) }]));
}
// Spatial continuity of profile 0 (heads-up) from the outline inward: backdrop detail at 3-13 px
// (RMS(box1 - box6), where a magnified downscaled texel shows) per 4 px depth band, along the straight
// rims only (corners excluded), from 8 px (the box stays inside the card) to past the ramp. maxStep is
// the largest change between neighbouring bands as a share of the rim value: a sharp rim band over a
// smeared body reads as one large step; a single continuous curve spreads it over many small ones.
function depthProfile(px) {
  const lo = 8, step = 4, hi = Math.ceil((2 * bevel + 20) / step) * step;
  const bins = Array.from({ length: (hi - lo) / step }, () => [0, 0]);
  for (let ch = 0; ch < 3; ch++) {
    const b1 = boxBlur(px, ch, 1).dst, b6 = boxBlur(px, ch, 6).dst;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (Math.abs(x + 0.5 - CARD.x - CARD.w / 2) > CARD.w / 2 - CARD.r) continue;
      const d = depthOf(x, y), b = Math.floor((d - lo) / step);
      if (d < lo || b >= bins.length) continue;
      const i = y * W + x; bins[b][0] += (b1[i] - b6[i]) ** 2; bins[b][1]++;
    }
  }
  const detail = bins.map(([a, n]) => +Math.sqrt(a / Math.max(n, 1)).toFixed(2));
  let maxStep = 0;
  for (let i = 1; i < detail.length; i++) maxStep = Math.max(maxStep, Math.abs(detail[i] - detail[i - 1]));
  return { from: lo, step, detail, maxStep: +(maxStep / Math.max(detail[0], 1e-3)).toFixed(3) };
}
const report = {};
for (const [name, r] of Object.entries(res)) {
  if (name === 'plain') continue;
  report[name] = { ...quality(r.px), temporal: temporal(r.frames), ms: +r.ms.toFixed(1) };
  if (png) fs.writeFileSync(`${png}${name}.png`, Buffer.from(r.png.split(',')[1], 'base64'));
}
if (res.fill) report.fillDepth = depthProfile(res.fill.px);
if (res.baseFill) report.baseFillDepth = depthProfile(res.baseFill.px);
if (res.fill && res.fillKg) report.rimFillKgVsFill = rimLift(res.fillKg.px, res.fill.px);
if (res.fillKg && res.baseFillKg) report.rimFillKgVsBaseFillKg = rimLift(res.fillKg.px, res.baseFillKg.px);
if (res.fillKg && res.baseFillKg) report.fillKgVsBaseFillKg = diff(res.fillKg.px, res.baseFillKg.px);
if (res.fillPlain && res.fillShade) { report.lumaPlain = lumaStats(res.fillPlain.px); report.lumaShade = lumaStats(res.fillShade.px); }
if (res.fillShade && res.clipShade) report.fillShadeVsClipShade = diff(res.fillShade.px, res.clipShade.px);
if (res.fill && res.clip) report.fillVsClip = diff(res.fill.px, res.clip.px);
if (res.fillKg && res.clipKg) report.fillKgVsClipKg = diff(res.fillKg.px, res.clipKg.px);
if (res.fill && res.baseFill) report.fillVsBaseFill = diff(res.fill.px, res.baseFill.px);
console.log(JSON.stringify(report, null, 1));
