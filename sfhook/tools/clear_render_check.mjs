// Clear Glass render checks in WebGL2 (headless Chromium, SwiftShader), on the GLSL that refract.h
// actually generates (OPTICS_SPEC.md 9.2-9.4, 10):
//   NODE_PATH=<dir with playwright> node clear_render_check.mjs <refract_check> <dump> <work dir> [report.json]
// It runs refract_check itself for every configuration it needs, from the synthetic dump's FillRRect
// (mod_002) and rounded-rect clip (mod_004) programs. Gates (exit 1 on failure):
//  - field maps (debug 3/4): the shader's shift and blur radius match the C/JS twin of the clear field
//    (and of the optics-11 fields, which validates the map itself) in both programs; the pixel-grid
//    Jacobian keeps sigma_min >= 0.25, sigma_max <= 1, det > 0 (M6); the clear flag follows the tag;
//  - isolation (I2/I3): with a clear mode compiled, profiles 2 and 3, untagged cards, the profile left
//    off and, in mode 2, baseline-tagged cards render exactly as optics 11 (max difference 0);
//  - metamorphic 1, 2, 5 and fill = clip for both clear profiles;
// and reports, for the cells H01-H04 and K01-K12 of the experiment matrix against the baseline:
// structure/grain/temporal/ms on the photographic backdrop (M10), contrast transfer per zone on
// checkers (M2), the grey and hue transfer (M5), point-light locality (M3), the edge light's response
// to a spot moving across the rim (M4) and the largest second difference on a ramp (M7). Line order
// (M1) is checked on the shader's own field map: the sampled position never moves backward along
// rows and columns; optics 11's heads-up lens at sflens 0.55 is the positive control (it folds).
// SwiftShader timings are CPU rasterizer costs, relative only.
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import fs from 'fs';
const { chromium } = createRequire(import.meta.url)('playwright');

const [rcBin, dump, work, reportPath] = process.argv.slice(2);
const W = 800, H = 520;
const CARD = { x: 40, y: 110, w: 720, h: 300, r: 92 };   // render_check's card
const PILL = { x: 100, y: 152, w: 600, h: 216, r: 108 };  // a pill: the band reaches the medial axis at beta 1
const LIGHT_WIDTH = [12, 16];
const fails = [];
const report = {};

// ---- shader generation -------------------------------------------------------------------------
const dirs = {};
function gen(name, debug, args = '') {
  if (dirs[name]) return dirs[name];
  const out = `${work}/${name}`;
  fs.mkdirSync(out, { recursive: true });
  execFileSync(rcBin, [dump, out, String(debug)].concat(args ? [args] : []));
  const webgl = s => s.replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
  dirs[name] = { fill: webgl(fs.readFileSync(`${out}/mod_002.frag`, 'utf8')), clip: webgl(fs.readFileSync(`${out}/mod_004.frag`, 'utf8')) };
  return dirs[name];
}

// ---- tags (SemBlurBridge.sfTag as render_check models it) and the clip decoder --------------------
const sOf = k => Math.min(1, Math.max(0, (k - 0.1) / 1.1));
function tagR(r, k, pf) {
  if (pf === 3) return Math.floor(r) + 0.875 <= r ? Math.floor(r) + 0.875 : Math.floor(r) - 0.125;
  if (pf === 0) return Math.floor(r) - 1 + 0.55 + 0.15 * sOf(k);
  const t = Math.floor(r) + (pf === 1 ? 0.30 : 0.05) + 0.15 * sOf(k);
  return t <= r ? t : t - 1;
}
const CLEAR_K = 0.1 + 1.1 * 11 / 12;       // the mode-2 slice, s = 11/12
const f32 = Math.fround;
function clipK(rTag) {                      // OULG_DECODE on fract(r + 0.5), float arithmetic
  const rph = f32(rTag + 0.5), f = f32(rph - Math.floor(rph));
  const win = [[0.04, 0.21, 0.05], [0.79, 0.96, 0.80], [0.54, 0.71, 0.55], [0.29, 0.46, 0.30]];
  for (let i = 0; i < 4; i++) if (f > f32(win[i][0]) && f < f32(win[i][1])) return { pf: i, k: f32(0.1 + 1.1 * Math.min(1, Math.max(0, f32((f - f32(win[i][2])) / 0.15)))) };
  return { pf: -1, k: 0 };
}

// ---- the twins ---------------------------------------------------------------------------------
const clampd = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const S5 = (lo, hi, x) => { const u = clampd((x - lo) / (hi - lo), 0, 1); return u * u * u * (u * (u * 6 - 15) + 10); };
function geom(qx, qy, hx, hy, r) {
  const wx = Math.abs(qx) - (hx - r), wy = Math.abs(qy) - (hy - r), vx = Math.max(wx, 0), vy = Math.max(wy, 0), l = Math.hypot(vx, vy);
  return { vx, vy, l, depth: r - l - Math.min(Math.max(wx, wy), 0), bevel: clampd(0.42 * r, 16, 56) };
}
// Clear field (refract.h OULG_FS_CLEAR_FIELD_FMT, then x rho and blur scale): c = {beta, acap, l0, l2, be, bc, bexp, mode, rho}.
function twinClear(qx, qy, hx, hy, r, k, c) {
  const g = geom(qx, qy, hx, hy, r), band = clampd(c.beta * r, g.bevel, r), sb = S5(0, band, g.depth);
  const br = c.be + (c.bc - c.be) * (sb > 0 ? Math.pow(sb, c.bexp) : 0), R = Math.min(hx, hy), ux = qx / Math.max(hx, 1), uy = qy / Math.max(hy, 1);
  let sx = -R * ux * (c.l0 + c.l2 * ux * ux), sy = -R * uy * (c.l0 + c.l2 * uy * uy);
  if (g.l > 0 && g.depth > 0 && g.depth < band) {
    const A = c.mode === 1 ? Math.min(k, c.acap) : c.acap, t = 1 - g.depth / band, m = A * band * t * t * (3 - 2 * t);
    sx -= g.vx / g.l * Math.sign(qx) * m; sy -= g.vy / g.l * Math.sign(qy) * m;
  }
  return { sx: sx * c.rho, sy: sy * c.rho, br };
}
// Optics 11, defaults: profile 0 (v0.7 lens + 6 % dome) and profile 1 (v0.23, kgbodylens 0.05).
function twinBase(qx, qy, hx, hy, r, k, pf) {
  const g = geom(qx, qy, hx, hy, r), R = Math.min(hx, hy);
  if (pf === 1) {
    const band = clampd(0.85 * r, g.bevel, r);
    let sx = -0.05 * qx * R / hx, sy = -0.05 * qy * R / hy;
    if (g.l > 0 && g.depth > 0 && g.depth < band) {
      const t = 1 - g.depth / band, m = Math.min(k, f32(0.4667)) * band * t * t * (3 - 2 * t);
      sx -= g.vx / g.l * Math.sign(qx) * m; sy -= g.vy / g.l * Math.sign(qy) * m;
    }
    return { sx, sy };
  }
  let sx = 0, sy = 0;
  if (g.l > 0 && g.depth > 0 && g.depth < g.bevel) { const t = 1 - g.depth / g.bevel, m = k * g.bevel * t * t; sx = -g.vx / g.l * Math.sign(qx) * m; sy = -g.vy / g.l * Math.sign(qy) * m; }
  return { sx: sx - 0.06 * qx * R / Math.max(hx, 1), sy: sy - 0.06 * qy * R / Math.max(hy, 1) };
}
// The clear parameters as refract.h fixes them (OULG_CLEAR_DEFAULT and the cap with rho; rounded down).
function clearCfg(pf, over = {}, mode = 1, rho = 1) {
  const c = { beta: 0.85, amp: 0.40, l0: pf ? 0.05 : 0.06, l2: 0, be: 4, bc: 24, bexp: 2, mode, rho, ...over };
  const amax = Math.max(0, ((1 - 0.25) / rho - c.l0 - 3 * c.l2) / 1.5);
  c.acap = Math.floor(Math.min(c.amp, amax) * 1e4) / 1e4;
  return c;
}
function clearArgs(pf, over = {}, mode = 1) {
  const p = pf ? 'kc' : 'hc', names = { beta: 'band', amp: 'amp', l0: 'body', l2: 'body2', be: 'edge', bc: 'core', bexp: 'blurexp' };
  return [`${pf ? 'kgclear' : 'huclear'}=${mode}`].concat(Object.entries(over).map(([k, v]) => `${names[k] ? p + names[k] : k}=${v}`)).join(',');
}

// ---- the browser harness ------------------------------------------------------------------------
function harness(cases, W, H) {
  const c = document.getElementById('c'); c.width = W; c.height = H;
  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true, premultipliedAlpha: false });
  const b64 = a => { let t = ''; for (let i = 0; i < a.length; i += 0x8000) t += String.fromCharCode.apply(null, a.subarray(i, i + 0x8000)); return btoa(t); };
  const texCache = {}, progCache = {}, plainCache = {};
  function backdrop(bd) {
    const key = JSON.stringify(bd);
    if (texCache[key]) return texCache[key];
    const src = document.createElement('canvas'); src.width = W; src.height = H;
    const g = src.getContext('2d');
    if (bd.kind === 'photo') {
      const sky = g.createLinearGradient(0, 0, W / 2, H); sky.addColorStop(0, '#f6e7c8'); sky.addColorStop(0.5, '#e7b38a'); sky.addColorStop(1, '#8fb7e0');
      g.fillStyle = sky; g.fillRect(0, 0, W / 2, H);
      const night = g.createLinearGradient(W / 2, 0, W, H); night.addColorStop(0, '#0d1b2a'); night.addColorStop(0.6, '#1b263b'); night.addColorStop(1, '#3a1f4d');
      g.fillStyle = night; g.fillRect(W / 2, 0, W / 2, H);
      for (const [x, y, rad, col] of [[150, 260, 70, '#2a9d8f'], [600, 240, 60, '#ffb703'], [420, 360, 50, '#e63946']]) {
        const rg = g.createRadialGradient(x, y, 0, x, y, rad); rg.addColorStop(0, col); rg.addColorStop(1, col + '00'); g.fillStyle = rg; g.fillRect(x - rad, y - rad, 2 * rad, 2 * rad);
      }
      g.font = 'bold 64px sans-serif'; g.textBaseline = 'middle';
      g.fillStyle = '#111'; g.fillText('Glass 12:45', 20, 114);
      g.fillStyle = '#fff'; g.fillText('WhatsApp', W / 2 + 20, 406);
      g.font = '28px sans-serif'; g.fillStyle = '#222'; g.fillText('Large high-contrast text over the rim', 60, 260);
      g.fillStyle = '#eee'; g.fillText('Reply   Mark as read', W / 2 + 40, 260);
    } else if (bd.kind === 'uniform') {
      g.fillStyle = bd.c; g.fillRect(0, 0, W, H);
    } else if (bd.kind === 'checker') {
      g.fillStyle = '#262626'; g.fillRect(0, 0, W, H); g.fillStyle = '#d9d9d9';
      for (let y = 0; y < H; y += bd.p) for (let x = 0; x < W; x += bd.p) if (((x + y) / bd.p) % 2 === 0) g.fillRect(x, y, bd.p, bd.p);
    } else if (bd.kind === 'lines') {
      g.fillStyle = '#e0e0e0'; g.fillRect(0, 0, W, H); g.fillStyle = '#202020';
      for (let y = 0; y < H; y += bd.p) g.fillRect(0, y, W, bd.p / 2);
    } else if (bd.kind === 'ramp') {
      const gr = g.createLinearGradient(0, 0, W, H); gr.addColorStop(0, '#000'); gr.addColorStop(1, '#fff'); g.fillStyle = gr; g.fillRect(0, 0, W, H);
    } else if (bd.kind === 'spots') {
      g.fillStyle = '#000'; g.fillRect(0, 0, W, H); g.fillStyle = '#fff';
      for (const [x, y] of bd.pts) { g.beginPath(); g.arc(x, y, bd.rad || 12, 0, 2 * Math.PI); g.fill(); }
    }
    const tw = W / 4, th = H / 4, q = document.createElement('canvas'); q.width = tw; q.height = th;
    const qg = q.getContext('2d'); qg.filter = 'blur(1px)'; qg.drawImage(src, 0, 0, tw, th);
    const tex = qg.getImageData(0, 0, tw, th).data;
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tw, th, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(tex));
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    texCache[key] = t;
    plainCache[key] = b64(new Uint8Array(g.getImageData(0, 0, W, H).data.buffer));
    return t;
  }
  const vsFill = `#version 300 es
uniform float uKp, uR; uniform vec2 uOff, uC, uHs; in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = uC + uOff + p * uHs;
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(uKp, uR, uHs); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const vsClip = `#version 300 es
in vec2 p; out highp vec2 vTransformedCoords_2_S0;
void main() { vec2 dev = (p * 0.5 + 0.5) * vec2(${W}.0, ${H}.0);
  vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0); gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  function program(fs, clip) {
    const key = (clip ? 'c' : 'f') + fs;
    if (progCache[key]) return progCache[key];
    const pr = gl.createProgram();
    for (const [type, s] of [[gl.VERTEX_SHADER, clip ? vsClip : vsFill], [gl.FRAGMENT_SHADER, fs]]) {
      const sh = gl.createShader(type); gl.shaderSource(sh, s); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
      gl.attachShader(pr, sh);
    }
    gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr);
    if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
    return progCache[key] = pr;
  }
  const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const out = {}, plains = {};
  for (const cs of cases) {
    const t = backdrop(cs.bd);
    plains[JSON.stringify(cs.bd)] = plainCache[JSON.stringify(cs.bd)];
    const pr = program(cs.src, cs.clip); gl.useProgram(pr);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
    const C = cs.card, off = cs.off || 0;
    const draw = () => {
      if (cs.clip) {
        gl.uniform2f(gl.getUniformLocation(pr, 'u_skRTFlip'), H, -1);
        gl.uniform4f(gl.getUniformLocation(pr, 'uinnerRect_S2'), C.x + off + cs.rTag, C.y + cs.rTag, C.x + off + C.w - cs.rTag, C.y + C.h - cs.rTag);
        gl.uniform2f(gl.getUniformLocation(pr, 'uradiusPlusHalf_S2'), cs.rTag + 0.5, 1 / (cs.rTag + 0.5));
      } else {
        gl.uniform1f(gl.getUniformLocation(pr, 'uKp'), cs.kp); gl.uniform1f(gl.getUniformLocation(pr, 'uR'), cs.rTag);
        gl.uniform2f(gl.getUniformLocation(pr, 'uOff'), off, 0);
        gl.uniform2f(gl.getUniformLocation(pr, 'uC'), C.x + C.w / 2, C.y + C.h / 2); gl.uniform2f(gl.getUniformLocation(pr, 'uHs'), C.w / 2, C.h / 2);
      }
      gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    draw();
    const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    const r = { px: b64(px) };
    if (cs.time) {
      const one = new Uint8Array(4);
      const t0 = performance.now(); for (let i = 0; i < 6; i++) draw(); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, one);
      r.ms = (performance.now() - t0) / 6;
    }
    out[cs.id] = r;
  }
  return { out, plains };
}

// ---- cases ---------------------------------------------------------------------------------------
const cases = [];
function add(id, src, clip, pf, k, bd, extra = {}) {
  const card = extra.card || CARD;
  const rTag = pf < 0 ? card.r : tagR(card.r, k, pf);
  cases.push({ id, src, clip, pf, k, bd, card, rTag, kp: pf < 0 ? 0 : 2 * pf + (pf === 3 ? 0.1 + 1.1 * 0.5 : k), ...extra, card });
  return id;
}
const photo = { kind: 'photo' };
const base0 = gen('base', 0), base3 = gen('base_d3', 3);

// Field maps: debug 3 (shift) and 4 (blur, clear flag) for each clear mode, both programs, both cards.
const mapCfgs = [
  { name: 'hu1', args: 'huclear=1', pf: 0, k: 0.55, mode: 1 },
  { name: 'kg1', args: 'kgclear=1', pf: 1, k: 0.40, mode: 1 },
  { name: 'kg1b', args: 'kgclear=1,kcband=1,kcbody=0.10,kcbody2=0.03,kcamp=0.5,kgrefract=1.2', pf: 1, k: 1.2, mode: 1, over: { beta: 1, l0: 0.10, l2: 0.03, amp: 0.5 }, rho: 1.2 },
  { name: 'hu2', args: 'huclear=2,kgclear=2', pf: 0, k: CLEAR_K, mode: 2 },
  { name: 'kg2', args: 'huclear=2,kgclear=2', pf: 1, k: CLEAR_K, mode: 2 },
];
for (const m of mapCfgs) for (const card of [CARD, PILL]) for (const clip of [false, true]) {
  const d3 = gen(`map_${m.name}_3`, 3, m.args), d4 = gen(`map_${m.name}_4`, 4, m.args);
  const tagId = `${m.name}_${card === CARD ? 'card' : 'pill'}_${clip ? 'clip' : 'fill'}`;
  add(`map3_${tagId}`, clip ? d3.clip : d3.fill, clip, m.pf, m.k, photo, { card, map: m });
  add(`map4_${tagId}`, clip ? d4.clip : d4.fill, clip, m.pf, m.k, photo, { card, map: m });
}
// The optics-11 maps (validate the map encoding against the baseline twins).
for (const pf of [0, 1]) for (const clip of [false, true]) add(`mapbase_${pf}_${clip ? 'clip' : 'fill'}`, clip ? base3.clip : base3.fill, clip, pf, pf ? 0.40 : 0.30, photo, { basemap: pf });
// Positive control: optics 11's heads-up lens at the Tuning default sflens 0.55 folds (spec 2.2); the
// order check must see it.
add('mapbase_0_fold', base3.fill, false, 0, 0.55, photo, { basemap: 0, expectFold: true });
// Mode 2 with a baseline tag: flag 0.
for (const pf of [0, 1]) add(`map4_m2base_${pf}`, gen('map_m2_4', 4, 'huclear=2,kgclear=2').fill, false, pf, pf ? 0.40 : 0.30, photo, { flag: 0 });

// Isolation: what must not change.
const iso = [
  { name: 'hu1', args: 'huclear=1', same: [[1, 0.40], [2, 0.30], [2, 0.95], [3, 0], [-1, 0]] },
  { name: 'kg1', args: 'kgclear=1', same: [[0, 0.30], [0, 0.55], [2, 0.30], [3, 0], [-1, 0]] },
  { name: 'both2', args: 'huclear=2,kgclear=2', same: [[0, 0.30], [0, 0.85], [1, 0.40], [1, 0.85], [2, 0.30], [2, 0.95], [3, 0], [-1, 0]] },
];
for (const it of iso) {
  const d = gen(`iso_${it.name}`, 0, it.args);
  for (const [pf, k] of it.same) for (const clip of [false, true]) {
    if (clip && pf === -1) continue;
    const tag = `${pf}_${k}_${clip ? 'clip' : 'fill'}`;
    add(`iso_${it.name}_${tag}`, clip ? d.clip : d.fill, clip, pf, k, photo, { pair: `isoref_${tag}` });
    if (!cases.find(c => c.id === `isoref_${tag}`)) add(`isoref_${tag}`, clip ? base0.clip : base0.fill, clip, pf, k, photo);
  }
}

// Metamorphic checks.
const grey = { kind: 'uniform', c: '#6e6e6e' };
const meta1 = [['', ''], ['b', ',hcband=1,kcband=1,hcamp=0.2,kcamp=0.25,hcbody=0.12,kcbody=0.1'], ['c', ',hcedge=0,kcedge=12,hccore=8,kccore=40,hcblurexp=1,kcblurexp=4']];
for (const [s, a] of meta1) {
  const d = gen(`meta1${s}`, 0, 'huclear=1,kgclear=1' + a);
  for (const pf of [0, 1]) add(`meta1${s}_${pf}`, d.fill, false, pf, pf ? 0.40 : 0.55, grey, { mpair: `meta1_${pf}` });
}
for (const pf of [0, 1]) {
  const lightOff = gen('meta2_off', 0, 'huclear=1,kgclear=1,hulight=0,kglight=0'), lightOn = gen('meta1', 0, 'huclear=1,kgclear=1');
  add(`meta2_on_${pf}`, lightOn.fill, false, pf, pf ? 0.40 : 0.55, { kind: 'uniform', c: '#000000' });
  add(`meta2_off_${pf}`, lightOff.fill, false, pf, pf ? 0.40 : 0.55, { kind: 'uniform', c: '#000000' });
}
{
  const cl = gen('meta5_clear', 0, 'kgclear=1,kcveil=0,kcdim=0,kcknee=0,kcsat=1,kccool=0.5');
  const bs = gen('meta5_base', 0, 'flat=0,sat=1');
  for (const g of ['#1a1a1a', '#6e6e6e', '#c8c8c8']) {
    add(`meta5_clear_${g}`, cl.fill, false, 1, 0.40, { kind: 'uniform', c: g });
    add(`meta5_base_${g}`, bs.fill, false, 1, 0.40, { kind: 'uniform', c: g });
  }
}

// Experiment matrix (section 10): HU H01-H04, KG K01-K12, plus baselines and "blur only" for HU.
const matrix = [];
const lenses = [{ l0: null, amp: 0.40 }, { l0: 0.10, amp: 0.33 }];
let n = 0;
for (const bc of [12, 24]) for (const L of lenses) {
  const id = `H0${++n}`, over = { bc, be: 4, amp: L.amp, ...(L.l0 ? { l0: L.l0 } : {}) };
  matrix.push({ id, pf: 0, k: 0.55, desc: `b_c ${bc}, l0 ${L.l0 || 0.06}, A ${L.amp}`, dir: gen(`cell_${id}`, 0, clearArgs(0, over)) });
}
n = 0;
for (const bc of [12, 24]) for (const veil of [0, 0.04, 0.11]) for (const L of lenses) {
  const id = `K${String(++n).padStart(2, '0')}`, over = { bc, be: 4, amp: L.amp, ...(L.l0 ? { l0: L.l0 } : {}), kcveil: veil };
  matrix.push({ id, pf: 1, k: 0.40, desc: `b_c ${bc}, veil ${veil}, l0 ${L.l0 || 0.05}, A ${L.amp}`, dir: gen(`cell_${id}`, 0, clearArgs(1, over)) });
}
matrix.push({ id: 'HU-base', pf: 0, k: 0.55, desc: 'optics 11, sflens 0.55', dir: base0 });
matrix.push({ id: 'HU-base.30', pf: 0, k: 0.30, desc: 'optics 11, sflens 0.30 (render_check)', dir: base0 });
matrix.push({ id: 'HU-blur12', pf: 0, k: 0.55, desc: 'optics 11 field, core 12', dir: gen('hublur12', 0, 'hucore=12') });
matrix.push({ id: 'HU-blur24', pf: 0, k: 0.55, desc: 'optics 11 field, core 24', dir: gen('hublur24', 0, 'hucore=24') });
matrix.push({ id: 'KG-base', pf: 1, k: 0.40, desc: 'optics 11, kglens 0.40', dir: base0 });
const midTop = [CARD.x + CARD.w / 2, CARD.y], midSide = [CARD.x, CARD.y + CARD.h / 2];
const corner = [CARD.x + CARD.r * (1 - Math.SQRT1_2), CARD.y + CARD.r * (1 - Math.SQRT1_2)];
const corner2 = [CARD.x + CARD.w - CARD.r * (1 - Math.SQRT1_2), CARD.y + CARD.h - CARD.r * (1 - Math.SQRT1_2)];
const spotPts = { top: midTop, side: midSide, corner, corner2 };
const MOVE = Array.from({ length: 9 }, (_, i) => CARD.y - 10 + 2 * i);
for (const m of matrix) {
  const src = m.dir.fill, pf = m.pf, k = m.k;
  add(`${m.id}_photo`, src, false, pf, k, photo, { time: true });
  for (let o = 1; o <= 3; o++) add(`${m.id}_photo_m${o}`, src, false, pf, k, photo, { off: o / 3 });
  add(`${m.id}_photoclip`, m.dir.clip, true, pf, k, photo);
  for (const p of [16, 32, 64]) add(`${m.id}_chk${p}`, src, false, pf, k, { kind: 'checker', p });
  for (const g of ['#1a1a1a', '#4d4d4d', '#808080', '#b3b3b3', '#e6e6e6']) add(`${m.id}_grey${g}`, src, false, pf, k, { kind: 'uniform', c: g });
  for (const h of ['#d62828', '#f77f00', '#fcbf49', '#2a9d8f', '#457b9d', '#8338ec']) add(`${m.id}_hue${h}`, src, false, pf, k, { kind: 'uniform', c: h });
  add(`${m.id}_black`, src, false, pf, k, { kind: 'uniform', c: '#000000' });
  for (const [s, pt] of Object.entries(spotPts)) add(`${m.id}_spot_${s}`, src, false, pf, k, { kind: 'spots', pts: [pt] });
  for (const y of MOVE) add(`${m.id}_move${y}`, src, false, pf, k, { kind: 'spots', pts: [[CARD.x + CARD.w / 2 - 120, y]], rad: 10 });
  add(`${m.id}_ramp`, src, false, pf, k, { kind: 'ramp' });
}

// ---- render ----------------------------------------------------------------------------------------
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage();
await page.setContent('<canvas id=c></canvas>');
const res = {}, plains = {};
for (let i = 0; i < cases.length; i += 40) {
  const batch = cases.slice(i, i + 40).map(({ id, src, clip, kp, rTag, card, bd, off, time }) => ({ id, src, clip, kp, rTag, card, bd, off, time }));
  const r = await page.evaluate(`(${harness.toString()})(${JSON.stringify(batch)}, ${W}, ${H})`);
  for (const [id, v] of Object.entries(r.out)) res[id] = { px: new Uint8Array(Buffer.from(v.px, 'base64')), ms: v.ms };
  for (const [k, v] of Object.entries(r.plains)) plains[k] = new Uint8Array(Buffer.from(v, 'base64'));
}
await browser.close();
const byId = Object.fromEntries(cases.map(c => [c.id, c]));

// ---- analysis helpers ---------------------------------------------------------------------------
const at = (px, x, y, ch) => px[((H - 1 - y) * W + x) * 4 + ch];
const lumaAt = (px, x, y) => 0.2126 * at(px, x, y, 0) + 0.7152 * at(px, x, y, 1) + 0.0722 * at(px, x, y, 2);
const depthOf = (card, x, y) => geom(x + 0.5 - card.x - card.w / 2, y + 0.5 - card.y - card.h / 2, card.w / 2, card.h / 2, card.r).depth;
function diff(a, b, card = CARD, margin = 2) {
  let max = 0, sum = 0, n = 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (depthOf(card, x, y) > margin) for (let ch = 0; ch < 3; ch++) {
    const d = Math.abs(at(a, x, y, ch) - at(b, x, y, ch)); max = Math.max(max, d); sum += d; n++;
  }
  return { max, mean: +(sum / n).toFixed(3) };
}
const fx = v => +v.toFixed(3);

// ---- field maps (M6) ---------------------------------------------------------------------------------
{
  const mapRep = {};
  for (const cs of cases) {
    if (!cs.id.startsWith('map') || cs.flag !== undefined) continue;
    const px = res[cs.id].px, card = cs.card, hx = card.w / 2, hy = card.h / 2, debug4 = cs.id.startsWith('map4');
    const pf = cs.basemap !== undefined ? cs.basemap : cs.pf;
    const kUsed = cs.clip ? clipK(cs.rTag).k : cs.k;
    const cfg = cs.map ? clearCfg(pf, cs.map.over || {}, cs.map.mode, cs.map.rho || 1) : null;
    const grid = new Float64Array(W * H * 2).fill(NaN);
    let err = 0, n = 0, flagBad = 0;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const d = depthOf(card, x, y);
      if (d <= 1.5) continue;
      const qx = x + 0.5 - card.x - hx, qy = y + 0.5 - card.y - hy;
      if (debug4) {
        const br = (at(px, x, y, 0) + at(px, x, y, 1) / 255) / 255 * 128;
        const want = twinClear(qx, qy, hx, hy, card.r === cs.rTag ? card.r : cs.rTag, kUsed, cfg).br * 1.0;
        err = Math.max(err, Math.abs(br - want)); n++;
        if (at(px, x, y, 2) !== 255) flagBad++;
        continue;
      }
      const sx = ((at(px, x, y, 0) + at(px, x, y, 1) / 255) / 255 - 0.5) * 256, sy = ((at(px, x, y, 2) + at(px, x, y, 3) / 255) / 255 - 0.5) * 256;
      grid[(y * W + x) * 2] = sx; grid[(y * W + x) * 2 + 1] = sy;
      const w = cfg ? twinClear(qx, qy, hx, hy, cs.rTag, kUsed, cfg) : twinBase(qx, qy, hx, hy, cs.rTag, kUsed, pf);
      err = Math.max(err, Math.abs(sx - w.sx), Math.abs(sy - w.sy)); n++;
    }
    const entry = { maxErrPx: +err.toFixed(4), px: n };
    if (debug4) entry.flagNotClear = flagBad;
    if (!debug4) {
      // Pixel-grid Jacobian (central differences over 2 px) wherever the 5-point stencil is inside.
      let smin = 9, smax = 0, detMin = 9;
      for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
        const g = (xx, yy, c) => grid[(yy * W + xx) * 2 + c];
        if ([g(x - 1, y, 0), g(x + 1, y, 0), g(x, y - 1, 0), g(x, y + 1, 0)].some(Number.isNaN)) continue;
        const j00 = 1 + (g(x + 1, y, 0) - g(x - 1, y, 0)) / 2, j01 = (g(x, y + 1, 0) - g(x, y - 1, 0)) / 2;
        const j10 = (g(x + 1, y, 1) - g(x - 1, y, 1)) / 2, j11 = 1 + (g(x, y + 1, 1) - g(x, y - 1, 1)) / 2;
        const a2 = j00 * j00 + j10 * j10, b = j00 * j01 + j10 * j11, c2 = j01 * j01 + j11 * j11, tr = a2 + c2, disc = Math.sqrt(Math.max((a2 - c2) ** 2 + 4 * b * b, 0));
        smin = Math.min(smin, Math.sqrt(Math.max((tr - disc) / 2, 0))); smax = Math.max(smax, Math.sqrt((tr + disc) / 2));
        detMin = Math.min(detMin, j00 * j11 - j01 * j10);
      }
      Object.assign(entry, { sigmaMin: fx(smin), sigmaMax: fx(smax), detMin: fx(detMin) });
      // M1 on the shader itself: along columns (straight rim, junction, arc) and rows, the sampled
      // position q + F(q) moves strictly forward, so lines behind the glass keep their order (no
      // fold, no doubled line). J symmetric positive definite makes J00, J11 > 0; this checks it.
      let reversals = 0;
      const cols = [card.x + card.w / 2, card.x + card.r, card.x + 0.4 * card.r, card.x + 0.15 * card.r].map(Math.floor);
      for (const x of cols) for (let y = card.y + 1; y < card.y + card.h - 2; y++) {
        const a = grid[(y * W + x) * 2 + 1], b = grid[((y + 1) * W + x) * 2 + 1];
        if (!Number.isNaN(a) && !Number.isNaN(b) && 1 + b - a <= 0) reversals++;
      }
      const rows = [card.y + card.h / 2, card.y + card.r, card.y + 0.4 * card.r].map(Math.floor);
      for (const y of rows) for (let x = card.x + 1; x < card.x + card.w - 2; x++) {
        const a = grid[(y * W + x) * 2], b = grid[(y * W + x + 1) * 2];
        if (!Number.isNaN(a) && !Number.isNaN(b) && 1 + b - a <= 0) reversals++;
      }
      entry.orderReversals = reversals;
      if (cs.expectFold ? !reversals : reversals) fails.push(`M1 ${cs.id}: ${reversals} reversals of the sampled position${cs.expectFold ? ' (positive control expects some)' : ''}`);
      // Pixel differences over 2 px smooth the analytic minimum; allow 0.02 for that, not for a fold.
      if (cfg && (smin < 0.23 || smax > 1.02 || detMin <= 0)) fails.push(`M6 ${cs.id}: sigma ${fx(smin)}..${fx(smax)}, det ${fx(detMin)}`);
    }
    if (err > (debug4 ? 0.02 : 0.02)) fails.push(`map ${cs.id}: GLSL vs twin ${err.toFixed(4)} px`);
    if (debug4 && flagBad) fails.push(`map ${cs.id}: ${flagBad} px not flagged clear`);
    mapRep[cs.id] = entry;
  }
  for (const pf of [0, 1]) {
    const px = res[`map4_m2base_${pf}`].px;
    let bad = 0;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (depthOf(CARD, x, y) > 2 && at(px, x, y, 2) !== 0) bad++;
    if (bad) fails.push(`mode 2: ${bad} px of a baseline-tagged profile-${pf} card flagged clear`);
    mapRep[`map4_m2base_${pf}`] = { flaggedClear: bad };
  }
  report.fieldMaps = mapRep;
}

// ---- isolation (I2/I3) and metamorphic -----------------------------------------------------------------
{
  const isoRep = {};
  for (const cs of cases) if (cs.pair) {
    const d = diff(res[cs.id].px, res[cs.pair].px, CARD, -1e9);
    isoRep[cs.id] = d.max;
    if (d.max !== 0) fails.push(`isolation ${cs.id}: max ${d.max}`);
  }
  report.isolation = { cases: Object.keys(isoRep).length, maxDifference: Math.max(...Object.values(isoRep)) };
  const m = {};
  for (const pf of [0, 1]) {
    const a = res[`meta1_${pf}`].px;
    m[`uniform_params_pf${pf}`] = Math.max(diff(res[`meta1b_${pf}`].px, a).max, diff(res[`meta1c_${pf}`].px, a).max);
    if (m[`uniform_params_pf${pf}`] > 1) fails.push(`metamorphic 1 profile ${pf}: ${m[`uniform_params_pf${pf}`]}`);
    m[`black_light_pf${pf}`] = diff(res[`meta2_on_${pf}`].px, res[`meta2_off_${pf}`].px).max;
    if (m[`black_light_pf${pf}`] !== 0) fails.push(`metamorphic 2 profile ${pf}: ${m[`black_light_pf${pf}`]}`);
  }
  let m5 = 0;
  for (const g of ['#1a1a1a', '#6e6e6e', '#c8c8c8']) m5 = Math.max(m5, diff(res[`meta5_clear_${g}`].px, res[`meta5_base_${g}`].px).max);
  m.structural_lighting_vs_flat0 = m5;
  if (m5 > 1) fails.push(`metamorphic 5: ${m5}`);
  report.metamorphic = m;
}

// ---- matrix metrics -----------------------------------------------------------------------------------
function boxBlur(px, ch, rad) {
  const s = new Float64Array(W * H), tmp = new Float64Array(W * H), dst = new Float64Array(W * H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) s[y * W + x] = at(px, x, y, ch);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { let a = 0, n = 0; for (let d = -rad; d <= rad; d++) { const xx = x + d; if (xx >= 0 && xx < W) { a += s[y * W + xx]; n++; } } tmp[y * W + x] = a / n; }
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { let a = 0, n = 0; for (let d = -rad; d <= rad; d++) { const yy = y + d; if (yy >= 0 && yy < H) { a += tmp[yy * W + x]; n++; } } dst[y * W + x] = a / n; }
  return { s, dst };
}
const bevel = clampd(0.42 * CARD.r, 16, 56);
function quality(px) {
  let gr = 0, st = 0, n = 0;
  for (let ch = 0; ch < 3; ch++) {
    const b1 = boxBlur(px, ch, 1), b2 = boxBlur(px, ch, 2).dst, b20 = boxBlur(px, ch, 20).dst;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (depthOf(CARD, x, y) > 1.5 * bevel + 20) { const i = y * W + x; gr += (b1.s[i] - b1.dst[i]) ** 2; st += (b2[i] - b20[i]) ** 2; n++; }
  }
  return { grain: fx(Math.sqrt(gr / n)), structure: fx(Math.sqrt(st / n)) };
}
function temporal(frames) {
  let s = 0, n = 0;
  for (let m = 1; m < frames.length; m++) for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (depthOf(CARD, x, y) > 3 && depthOf(CARD, x - 1, y) > 3) for (let ch = 0; ch < 3; ch++) { s += Math.abs(at(frames[m], x, y, ch) - at(frames[m - 1], x, y, ch)); n++; }
  return fx(s / n);
}
// M2: RMS luma contrast in a depth zone along the straight rims, over the backdrop's own (full resolution).
function zoneContrast(px, plain, lo, hi) {
  let a = [], b = [];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    if (Math.abs(x + 0.5 - CARD.x - CARD.w / 2) > CARD.w / 2 - CARD.r) continue;
    const d = depthOf(CARD, x, y);
    if (d < lo || d >= hi) continue;
    a.push(lumaAt(px, x, y)); const i = (y * W + x) * 4; b.push(0.2126 * plain[i] + 0.7152 * plain[i + 1] + 0.0722 * plain[i + 2]);
  }
  const sd = v => { const m = v.reduce((p, q) => p + q, 0) / v.length; return Math.sqrt(v.reduce((p, q) => p + (q - m) ** 2, 0) / v.length); };
  return fx(sd(a) / Math.max(sd(b), 1e-6));
}
function coreMean(px) {
  const acc = [0, 0, 0]; let n = 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (depthOf(CARD, x, y) > 100) { for (let c = 0; c < 3; c++) acc[c] += at(px, x, y, c); n++; }
  return acc.map(v => v / n);
}
const hexRgb = h => [1, 3, 5].map(i => parseInt(h.substr(i, 2), 16));
const lum = c => 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
const satOf = c => (Math.max(...c) - Math.min(...c)) / Math.max(Math.max(...c), 1e-6);
const cells = {};
for (const m of matrix) {
  const g = id => res[`${m.id}_${id}`].px;
  const band = clampd(0.85 * CARD.r, bevel, CARD.r);
  const plainOf = bd => plains[JSON.stringify(bd)];
  const rep = { desc: m.desc, ...quality(g('photo')), temporal: temporal([g('photo'), g('photo_m1'), g('photo_m2'), g('photo_m3')]), ms: +res[`${m.id}_photo`].ms.toFixed(1) };
  rep.fillVsClip = diff(g('photo'), g('photoclip')).max;
  rep.contrast = {};
  for (const p of [16, 32, 64]) {
    const bd = { kind: 'checker', p };
    rep.contrast[p] = { rim: zoneContrast(g(`chk${p}`), plainOf(bd), 2, 0.25 * band), mid: zoneContrast(g(`chk${p}`), plainOf(bd), 0.4 * band, 0.6 * band), core: zoneContrast(g(`chk${p}`), plainOf(bd), band + 4, 150) };
  }
  const greys = ['#1a1a1a', '#4d4d4d', '#808080', '#b3b3b3', '#e6e6e6'].map(h => [lum(hexRgb(h)) / 255, lum(coreMean(g(`grey${h}`))) / 255]);
  rep.greyTransfer = { slope: fx((greys[4][1] - greys[0][1]) / (greys[4][0] - greys[0][0])), maxAbsDev: fx(Math.max(...greys.map(([a, b]) => Math.abs(a - b)))), black: fx(greys[0][1]) };
  rep.hueSatRatio = fx(['#d62828', '#f77f00', '#fcbf49', '#2a9d8f', '#457b9d', '#8338ec'].map(h => satOf(coreMean(g(`hue${h}`))) / satOf(hexRgb(h))).reduce((a, b) => a + b, 0) / 6);
  // M3: share of the spot's added light within 3 light widths of the spot.
  const lw = LIGHT_WIDTH[m.pf], black = g('black');
  rep.spotLocality = {};
  for (const [s, [sx0, sy0]] of Object.entries(spotPts)) {
    let inE = 0, all = 0; const px = g(`spot_${s}`);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (depthOf(CARD, x, y) > 0.5) {
      const d = Math.max(0, lumaAt(px, x, y) - lumaAt(black, x, y)); all += d;
      if (Math.hypot(x + 0.5 - sx0, y + 0.5 - sy0) <= 3 * lw + 12) inE += d;
    }
    rep.spotLocality[s] = all > 0 ? fx(inE / all) : null;
  }
  // M4: the moving spot's image centroid (y, in the card) and frame-to-frame change.
  let tsum = 0, prev = null;
  const centroids = [];
  for (const y0 of MOVE) {
    const px = g(`move${y0}`);
    let wy = 0, w = 0;
    for (let y = CARD.y; y < CARD.y + 80; y++) for (let x = CARD.x + CARD.w / 2 - 160; x < CARD.x + CARD.w / 2 - 80; x++) if (depthOf(CARD, x, y) > 0.5) {
      const d = Math.max(0, lumaAt(px, x, y) - lumaAt(black, x, y)); wy += d * (y + 0.5); w += d;
    }
    const c = w > 0 ? wy / w : null;
    centroids.push(c === null ? NaN : c);
    if (prev) { let s = 0, n = 0; for (let y = CARD.y; y < CARD.y + 80; y++) for (let x = CARD.x + CARD.w / 2 - 160; x < CARD.x + CARD.w / 2 - 80; x++) { s += Math.abs(lumaAt(px, x, y) - lumaAt(prev, x, y)); n++; } tsum += s / n; }
    prev = px;
  }
  // Near the outline the refraction samples deeper inside the card (S(0) = rho A band), so a spot
  // within ~S(0) of the rim reaches the card only through oulg_glow: this measures the edge light's
  // response to moving content (frame-to-frame change, centroid track), not refraction order (M1).
  rep.movingSpot = { meanChange: fx(tsum / (MOVE.length - 1)), centroids: centroids.map(fx) };
  // M7: largest second difference of luma on a diagonal ramp, depth > 20 px (past the rim lighting).
  { const px = g('ramp'); let mx = 0; for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) if (depthOf(CARD, x, y) > 20) mx = Math.max(mx, Math.abs(lumaAt(px, x + 1, y) - 2 * lumaAt(px, x, y) + lumaAt(px, x - 1, y)), Math.abs(lumaAt(px, x, y + 1) - 2 * lumaAt(px, x, y) + lumaAt(px, x, y - 1))); rep.rampSecondDiff = fx(mx); }
  cells[m.id] = rep;
}
report.matrix = cells;
const hu = cells['HU-base'], kg = cells['KG-base'];
report.summary = Object.fromEntries(Object.entries(cells).map(([id, c]) => [id, `${c.desc}: structure ${c.structure}, grain ${c.grain}, temporal ${c.temporal}, ms ${c.ms}, core contrast 64px ${c.contrast[64].core}, grey slope ${c.greyTransfer.slope}, black ${c.greyTransfer.black}, fill-clip ${c.fillVsClip}`]));
for (const [id, c] of Object.entries(cells)) if (/^[HK]\d/.test(id) && c.fillVsClip > Math.max(hu.fillVsClip, kg.fillVsClip, 1)) fails.push(`fill vs clip ${id}: ${c.fillVsClip}`);
report.fails = fails;
const text = JSON.stringify(report, null, 1);
if (reportPath) fs.writeFileSync(reportPath, text);
console.log(JSON.stringify({ fieldMaps: report.fieldMaps, isolation: report.isolation, metamorphic: report.metamorphic, summary: report.summary }, null, 1));
if (fails.length) { console.log('FAIL:\n' + fails.join('\n')); process.exit(1); }
console.log('clear_render_check: all gates passed');
