// Renders profile 3 (Quick Settings edge light, refract.h OULG_FS_EDGE_FMT) from refract_check output
// in WebGL2 (headless Chromium, SwiftShader) and checks what the edge light is for:
//   node edge_check.mjs <dir> [png prefix]
// <dir> holds mod_002.frag (FillRRect) and mod_004.frag (clip, flipped) from
// tools/testdata/synthetic_dump.txt, built without the debug tint: refract_check <dump> <dir> 0.
//
// Scenes (the compositor's input is drawn at full resolution, then downscaled 4x and lightly blurred
// as RenderEngine does), card 720 x 300 px, corner radius 145 (a One UI 9 Wi-Fi pill):
//  - black: an all-black backdrop;
//  - grey: a uniform mid-grey backdrop;
//  - glyph: black, with a white bar crossing the bottom rim only, x 300-420.
// Checks, for both programs:
//  - the body is transparent (premultiplied zero) everywhere past the light band;
//  - black adds nothing at the rim;
//  - grey lights the rim evenly: every side and corner within 10 % of the mean (no lit corner);
//  - glyph lights the bottom rim where the bar crosses it, and nowhere else (top rim, and the
//    bottom rim 60 px away from the bar, stay dark);
//  - FillRRect and clip agree.
import { createRequire } from 'module';
import fs from 'fs';
const { chromium } = createRequire(import.meta.url)('playwright');

const [dir, png] = process.argv.slice(2);
const W = 800, H = 520, CARD = { x: 40, y: 110, w: 720, h: 300, r: 145 };
// QuickSettingsGlass.edgeTag: floor(r) - 1 + 0.875; kp = k + 6 with k decoded from the 0.80 band.
const R_TAG = Math.floor(CARD.r) - 1 + 0.875, KP = 6 + (0.1 + 1.1 * (0.875 - 0.80) / 0.15);
const webgl = src => src.replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
const load = n => webgl(fs.readFileSync(`${dir}/mod_${n}.frag`, 'utf8'));
const programs = { fill: [load('002'), false], clip: [load('004'), true] };

function harness(programs, W, H, CARD, R_TAG, KP) {
  const c = document.getElementById('c'); c.width = W; c.height = H;
  const scene = kind => {
    const src = document.createElement('canvas'); src.width = W; src.height = H;
    const g = src.getContext('2d');
    g.fillStyle = kind === 'grey' ? '#808080' : '#000'; g.fillRect(0, 0, W, H);
    if (kind === 'glyph') { g.fillStyle = '#fff'; g.fillRect(300, CARD.y + CARD.h - 30, 120, 60); }
    const tw = W / 4, th = H / 4, q = document.createElement('canvas'); q.width = tw; q.height = th;
    const qg = q.getContext('2d'); qg.filter = 'blur(1px)'; qg.drawImage(src, 0, 0, tw, th);
    return qg.getImageData(0, 0, tw, th).data;
  };
  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  const hs = [CARD.w / 2, CARD.h / 2];
  const vsFill = `#version 300 es
in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${CARD.x + hs[0]}.0, ${CARD.y + hs[1]}.0) + p * vec2(${hs[0]}.0, ${hs[1]}.0);
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(${KP}, ${R_TAG}, ${hs[0]}.0, ${hs[1]}.0); voulg_vp = p;
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
  const out = {};
  for (const kind of ['black', 'grey', 'glyph']) {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, W / 4, H / 4, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(scene(kind)));
    for (const [name, [fs, clip]] of Object.entries(programs)) {
      const pr = build(clip ? vsClip : vsFill, fs); gl.useProgram(pr);
      gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
      if (clip) {
        gl.uniform2f(gl.getUniformLocation(pr, 'u_skRTFlip'), H, -1);
        gl.uniform4f(gl.getUniformLocation(pr, 'uinnerRect_S2'), CARD.x + R_TAG, CARD.y + R_TAG, CARD.x + CARD.w - R_TAG, CARD.y + CARD.h - R_TAG);
        gl.uniform2f(gl.getUniformLocation(pr, 'uradiusPlusHalf_S2'), R_TAG + 0.5, 1 / (R_TAG + 0.5));
      }
      gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
      let s = ''; for (let i = 0; i < px.length; i += 0x8000) s += String.fromCharCode.apply(null, px.subarray(i, i + 0x8000));
      out[`${name}_${kind}`] = { px: btoa(s), png: c.toDataURL('image/png') };
    }
  }
  return out;
}

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage();
await page.setContent('<canvas id="c"></canvas>');
const res = await page.evaluate(`(${harness.toString()})(${JSON.stringify(programs)}, ${W}, ${H}, ${JSON.stringify(CARD)}, ${R_TAG}, ${KP})`);
await browser.close();

let failures = 0;
const check = (ok, msg) => { if (!ok) { failures++; console.log('FAIL: ' + msg); } };
// readPixels rows are bottom-up; y here is top-down device space. Alpha channel = added light.
const alphaAt = (img, x, y) => img[((H - 1 - y) * W + x) * 4 + 3] / 255;
// Mean alpha over the light band (outline to 3 px in) along a side, or at a corner's diagonal.
const band = (img, pts) => pts.reduce((s, [x, y]) => s + alphaAt(img, x, y), 0) / pts.length;
// Depth of a pixel centre from the outline (> 0 inside), the field's own formula.
const depthAt = (x, y) => {
  const qx = x + 0.5 - (CARD.x + CARD.w / 2), qy = y + 0.5 - (CARD.y + CARD.h / 2);
  const wx = Math.abs(qx) - (CARD.w / 2 - R_TAG), wy = Math.abs(qy) - (CARD.h / 2 - R_TAG);
  return R_TAG - Math.hypot(Math.max(wx, 0), Math.max(wy, 0)) - Math.min(Math.max(wx, wy), 0);
};
// The light band, 1-3 px deep by the field's own depth, split into the four straight sides and the
// four corner arcs, so every region holds the same distribution of depths.
const regions = { top: [], bottom: [], left: [], right: [], tl: [], tr: [], bl: [], br: [] };
for (let y = CARD.y; y < CARD.y + CARD.h; y++) for (let x = CARD.x; x < CARD.x + CARD.w; x++) {
  const d = depthAt(x, y);
  if (d < 1 || d > 3) continue;
  const qx = x + 0.5 - (CARD.x + CARD.w / 2), qy = y + 0.5 - (CARD.y + CARD.h / 2);
  const cx = Math.abs(qx) > CARD.w / 2 - R_TAG, cy = Math.abs(qy) > CARD.h / 2 - R_TAG;
  const key = cx && cy ? (qy < 0 ? 't' : 'b') + (qx < 0 ? 'l' : 'r') : cy ? (qy < 0 ? 'top' : 'bottom') : cx ? (qx < 0 ? 'left' : 'right') : null;
  if (key) regions[key].push([x, y]);
}
// Bottom rim, 1-3 px deep, between two x (the white bar spans 300-420).
const bottomAt = (x0, x1) => regions.bottom.filter(([x]) => x >= x0 && x <= x1);
const topAt = (x0, x1) => regions.top.filter(([x]) => x >= x0 && x <= x1);
const decode = b => Uint8Array.from(Buffer.from(b, 'base64'));
const imgs = Object.fromEntries(Object.entries(res).map(([k, v]) => [k, decode(v.px)]));
for (const prog of Object.keys(programs)) {
  const black = imgs[`${prog}_black`], grey = imgs[`${prog}_grey`], glyph = imgs[`${prog}_glyph`];
  // Body: every pixel more than 20 px from the outline.
  let bodyMax = 0;
  for (let y = CARD.y; y < CARD.y + CARD.h; y += 2) for (let x = CARD.x; x < CARD.x + CARD.w; x += 2)
    if (depthAt(x, y) > 20) bodyMax = Math.max(bodyMax, alphaAt(grey, x, y));
  check(bodyMax <= 1 / 255, `${prog}: body not transparent over grey (max alpha ${bodyMax.toFixed(3)})`);
  const blackRim = Math.max(...Object.values(regions).map(p => band(black, p)));
  check(blackRim <= 1 / 255, `${prog}: black backdrop lights the rim (${blackRim.toFixed(3)})`);
  const g = Object.fromEntries(Object.entries(regions).map(([k, p]) => [k, band(grey, p)]));
  const mean = Object.values(g).reduce((a, b) => a + b, 0) / 8;
  check(mean > 0.15, `${prog}: grey rim too dark (${mean.toFixed(3)})`);
  for (const [k, v] of Object.entries(g)) check(Math.abs(v - mean) <= 0.10 * mean, `${prog}: grey ${k} ${v.toFixed(3)} vs mean ${mean.toFixed(3)}`);
  const lit = band(glyph, bottomAt(330, 390)), away = band(glyph, bottomAt(0, 240)), top = band(glyph, topAt(330, 390));
  check(lit > 0.25, `${prog}: white bar does not light the bottom rim (${lit.toFixed(3)})`);
  check(away <= 0.02 && top <= 0.02, `${prog}: light spreads (60+ px away ${away.toFixed(3)}, top rim ${top.toFixed(3)})`);
  console.log(`${prog}: body max ${bodyMax.toFixed(3)}, black rim ${blackRim.toFixed(3)}, grey rim mean ${mean.toFixed(3)} `
    + `(${Object.entries(g).map(([k, v]) => `${k} ${v.toFixed(3)}`).join(', ')}), white bar: at the bar ${lit.toFixed(3)}, `
    + `60+ px away ${away.toFixed(3)}, top rim ${top.toFixed(3)}`);
  if (png) for (const kind of ['grey', 'glyph']) fs.writeFileSync(`${png}${prog}_${kind}.png`, Buffer.from(res[`${prog}_${kind}`].png.split(',')[1], 'base64'));
}
// The two programs draw the same card (inside it: outside, the harness gives neither coverage).
let diff = 0;
for (const kind of ['grey', 'glyph']) {
  const a = imgs[`fill_${kind}`], b = imgs[`clip_${kind}`];
  for (let y = CARD.y; y < CARD.y + CARD.h; y++) for (let x = CARD.x; x < CARD.x + CARD.w; x++) {
    if (depthAt(x, y) < 1) continue;
    const i = ((H - 1 - y) * W + x) * 4 + 3;
    diff = Math.max(diff, Math.abs(a[i] - b[i]) / 255);
  }
}
check(diff <= 0.10, `fill vs clip differ by ${diff.toFixed(3)} alpha`);
console.log(`fill vs clip: max alpha difference ${diff.toFixed(3)}`);
console.log(`edge light: failures = ${failures}`);
process.exit(failures ? 1 : 0);
