// Lockscreen card preview over a photographic backdrop: the compositor's profile-1 program
// (mod_002.frag, FillRRect) and the module's layers above it, composited the way the device stacks
// them, written as a PNG and measured.
//   node kg_preview.mjs <dir> <backdrop.png> <out.png> [cardY]
// <dir>: refract_check output built without the debug tint (refract_check <dump> <dir> 0 [params]).
// <backdrop.png>: 1440 px wide, the wallpaper behind the card at device scale (S25 Ultra, 1440 x 3120).
//
// Stack, bottom to top, as on the lockscreen with kgblurpath=1:
//  1. the region's input: the backdrop at quarter resolution with a light blur (RenderEngine's
//     downscaled blur pass for a small radius; the exact Samsung filter is not known on the host);
//  2. the rewritten FillRRect program over that input (refraction, body blur, profile-1 material);
//  3. the region's colour, drawn by RenderEngine over the blur (KG_VEIL alpha of white; 0 since
//     v0.14 for compositor-lit cards, 0x14 before);
//  4. the module's glass at the native alpha 204: the dark fill (0x0a000000) and the edge shader's
//     hairline (KG_HAIR gain; 0 since v0.14, the shader is not drawn on compositor-lit cards);
//  5. white notification text, for legibility.
// Card: 1340 x 216, corner 108, as SystemUI lays out a two-line lockscreen card; lens 0.40.
//
// Metrics (luma 0-1, Rec. 709 on sRGB values, inside the card):
//  - body: mean, standard deviation and 5th/95th percentiles past 1.5 compositor bevels (68 px)
//    (homogeneity: a smaller spread is a more even body), share of pixels darker than 0.08;
//  - rim: mean luma in the outer 4 px band minus the body mean (contour brightness), top and bottom;
//  - contrast: WCAG ratio of white text against the body's 95th percentile (worst case).
import { createRequire } from 'module';
import fs from 'fs';
const { chromium } = createRequire(import.meta.url)('playwright');

const [dir, bgPath, outPath, cardYArg] = process.argv.slice(2);
const K = 0.40;
// The module's layers as of v0.14 for a compositor-lit card: no region colour and no edge shader.
// KG_VEIL=20 KG_HAIR=0.5 reproduce v0.13's stack (8 % white region colour, half-gain hairline).
const VEIL = +(process.env.KG_VEIL ?? 0), HAIR = +(process.env.KG_HAIR ?? 0);
const tag = r => { const s = Math.min(1, Math.max(0, (K - 0.1) / 1.1)); const t = Math.floor(r) + 0.30 + 0.15 * s; return t <= r ? t : t - 1; };
const frag = fs.readFileSync(`${dir}/mod_002.frag`, 'utf8')
  .replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
const bg = 'data:image/png;base64,' + fs.readFileSync(bgPath).toString('base64');

async function harness(frag, bg, cardY, rTag, kp, veilAlpha, hairGain) {
  const img = new Image(); img.src = bg; await img.decode();
  const W = img.width, H = img.height, CARD = { x: 50, y: cardY, w: 1340, h: 216, r: 108 };
  const src = document.createElement('canvas'); src.width = W; src.height = H;
  const g = src.getContext('2d'); g.drawImage(img, 0, 0);
  const plain = g.getImageData(0, 0, W, H).data;
  const tw = W / 4, th = Math.round(H / 4), q = document.createElement('canvas'); q.width = tw; q.height = th;
  const qg = q.getContext('2d'); qg.filter = 'blur(3px)'; qg.drawImage(src, 0, 0, tw, th);
  const tex = qg.getImageData(0, 0, tw, th).data;

  const c = document.createElement('canvas'); c.width = W; c.height = H;
  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tw, th, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(tex));
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  const hs = [CARD.w / 2, CARD.h / 2];
  const vs = `#version 300 es
uniform float uKp, uR; in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${CARD.x + hs[0]}.0, ${CARD.y + hs[1]}.0) + p * vec2(${hs[0]}.0, ${hs[1]}.0);
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(uKp, uR, ${hs[0]}.0, ${hs[1]}.0); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const pr = gl.createProgram();
  for (const [type, s] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, frag]]) {
    const sh = gl.createShader(type); gl.shaderSource(sh, s); gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
    gl.attachShader(pr, sh);
  }
  gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr);
  if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
  gl.useProgram(pr);
  const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
  gl.uniform1f(gl.getUniformLocation(pr, 'uKp'), kp); gl.uniform1f(gl.getUniformLocation(pr, 'uR'), rTag);
  gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);

  // Composite on the CPU, top-down rows.
  const out = new Uint8ClampedArray(plain);
  const density = 3.5, bevel = Math.max(1, Math.min(20 * density, Math.min(CARD.w, CARD.h) * 0.32));
  const hair = Math.max(1.5, Math.min(0.95 * density, 5)), fringe = 0.55, specular = 0.60;
  const sdf = (x, y) => {
    const qx = Math.abs(x) - hs[0] + CARD.r, qy = Math.abs(y) - hs[1] + CARD.r;
    return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - CARD.r;
  };
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const light = [0.45, 0.89], ll = Math.hypot(light[0], light[1]);
  for (let y = CARD.y - 2; y < CARD.y + CARD.h + 2; y++) for (let x = CARD.x - 2; x < CARD.x + CARD.w + 2; x++) {
    const p0 = x + 0.5 - CARD.x - hs[0], p1 = y + 0.5 - CARD.y - hs[1], d = sdf(p0, p1);
    const cover = clamp(0.5 - d / 1.5, 0, 1);
    if (cover <= 0.004) continue;
    const i = (y * W + x) * 4, j = ((H - 1 - y) * W + x) * 4;
    // 2: the compositor's region (premultiplied, opaque over the card), then its colour.
    let r = px[j] / 255, gg = px[j + 1] / 255, b = px[j + 2] / 255;
    const va = veilAlpha / 255; r = r * (1 - va) + va; gg = gg * (1 - va) + va; b = b * (1 - va) + va;
    // 4: the glass at the native alpha (fill, then the hairline, which is additive light).
    const ga = 204 / 255, fa = 0x0a / 255 * ga;
    r *= 1 - fa; gg *= 1 - fa; b *= 1 - fa;
    const nx = sdf(p0 + 1, p1) - sdf(p0 - 1, p1), ny = sdf(p0, p1 + 1) - sdf(p0, p1 - 1), nl = Math.hypot(nx, ny) || 1;
    const facing = (nx / nl) * (-light[0] / ll) + (ny / nl) * (-light[1] / ll);
    const angle = Math.atan2(p1 / hs[1], p0 / hs[0]);
    const runs = clamp(0.52 + 0.31 * Math.sin(angle * 3 + 0.7) + 0.17 * Math.sin(angle * 5 - 2.1), 0.06, 1);
    const under = clamp(ny / nl, 0, 1);
    const hw = Math.max(hair, 1), sep = hw * 0.9 * fringe, core = Math.max(0.9, hw * 0.32), ramp = Math.max(hw * 0.5, 1), reach = sep + core + ramp;
    let line = [clamp((reach - Math.abs(d + reach + 2 * sep)) / ramp, 0, 1), clamp((reach - Math.abs(d + reach + sep)) / ramp, 0, 1), clamp((reach - Math.abs(d + reach)) / ramp, 0, 1)];
    const att = clamp(1 - Math.abs(d + reach + sep) / (reach + sep), 0, 1); line = line.map(v => v * att);
    const lo = Math.min(...line); line = line.map(v => (lo + (v - lo) * 0.55) * 0.86);
    const gain = (0.70 + 0.22 * Math.max(facing, 0)) * (1 + (runs - 1) * 0.35) * (1 - 0.30 * under * under) * 0.60 * specular * hairGain * ga;
    r = clamp(r + line[0] * gain, 0, 1); gg = clamp(gg + line[1] * gain, 0, 1); b = clamp(b + line[2] * gain, 0, 1);
    out[i] = Math.round((r * cover + plain[i] / 255 * (1 - cover)) * 255);
    out[i + 1] = Math.round((gg * cover + plain[i + 1] / 255 * (1 - cover)) * 255);
    out[i + 2] = Math.round((b * cover + plain[i + 2] / 255 * (1 - cover)) * 255);
  }
  // Metrics before the text.
  const lum = (k) => (0.2126 * out[k] + 0.7152 * out[k + 1] + 0.0722 * out[k + 2]) / 255;
  const body = [], rimTop = [], rimBottom = [], sfBevel = clamp(0.42 * CARD.r, 16, 56);
  for (let y = CARD.y; y < CARD.y + CARD.h; y++) for (let x = CARD.x; x < CARD.x + CARD.w; x++) {
    const d = -sdf(x + 0.5 - CARD.x - hs[0], y + 0.5 - CARD.y - hs[1]), k = (y * W + x) * 4;
    if (d > 1.5 * sfBevel) body.push(lum(k));
    else if (d > 0.5 && d < 4.5 && Math.abs(x + 0.5 - CARD.x - hs[0]) < hs[0] - CARD.r) (y < CARD.y + hs[1] ? rimTop : rimBottom).push(lum(k));
  }
  const mean = a => a.reduce((s, v) => s + v, 0) / a.length;
  body.sort((a, b) => a - b);
  const m = mean(body), sd = Math.sqrt(mean(body.map(v => (v - m) ** 2))), pct = f => body[Math.floor(f * (body.length - 1))];
  const lin = v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  const metrics = { bodyMean: +m.toFixed(3), bodySd: +sd.toFixed(3), p05: +pct(0.05).toFixed(3), p95: +pct(0.95).toFixed(3),
    dark: +(body.filter(v => v < 0.08).length / body.length).toFixed(3),
    rimTop: +(mean(rimTop) - m).toFixed(3), rimBottom: +(mean(rimBottom) - m).toFixed(3),
    contrast: +(1.05 / (lin(pct(0.95)) + 0.05)).toFixed(2) };
  // 5: the text.
  const o = document.createElement('canvas'); o.width = W; o.height = H;
  const og = o.getContext('2d'); og.putImageData(new ImageData(out, W, H), 0, 0);
  og.fillStyle = '#fff'; og.textBaseline = 'alphabetic';
  og.font = 'bold 40px sans-serif'; og.fillText('Ajustar teclado Samsung   10:50', CARD.x + 200, CARD.y + 92);
  og.font = '38px sans-serif'; og.fillText('Estado atual do repositório', CARD.x + 200, CARD.y + 146);
  og.beginPath(); og.arc(CARD.x + 104, CARD.y + hs[1], 52, 0, 2 * Math.PI); og.strokeStyle = 'rgba(255,255,255,0.85)'; og.lineWidth = 5; og.stroke();
  return { png: o.toDataURL('image/png'), metrics };
}

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const p = await browser.newPage();
await p.setContent('<body></body>');
const cardY = cardYArg ? +cardYArg : 150;
const res = await p.evaluate(`(${harness.toString()})(${JSON.stringify(frag)}, ${JSON.stringify(bg)}, ${cardY}, ${tag(108)}, ${2 + K}, ${VEIL}, ${HAIR})`);
await browser.close();
fs.writeFileSync(outPath, Buffer.from(res.png.split(',')[1], 'base64'));
console.log(JSON.stringify(res.metrics));
