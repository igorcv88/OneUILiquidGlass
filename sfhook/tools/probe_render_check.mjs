// Positive control of the composition probe (debug 5; HU_ALPHA_PROBE.md): renders the generated
// probe GLSL of a FillRRect program in WebGL2 (headless Chromium, SwiftShader) with a known paint
// alpha (vcolor) and a known texture alpha, and checks that the decoded codes give them back.
// usage: node probe_render_check.mjs <probe frag (refract_check ... 5)>
// Needs node and the preinstalled playwright Chromium. Not a CI gate (check.sh compiles and links).
import { createRequire } from 'module'; import fs from 'fs';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const fsSrc = fs.readFileSync(process.argv[2], 'utf8').replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
const W = 256, H = 128, r = 60;
const b = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const p = await b.newPage(); await p.setContent('<canvas id=c></canvas>');
let fails = 0;
for (const [paint, texA] of [[1.0, 255], [0.98, 255], [1.0, 250], [0.97, 250], [0.90, 255]]) {
  const px = await p.evaluate(([fsSrc, W, H, r, paint, texA]) => {
    const c = document.getElementById('c'); c.width = W; c.height = H;
    const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true, premultipliedAlpha: true });
    const tw = 64, th = 32, tex = new Uint8Array(tw * th * 4);
    for (let i = 0; i < tw * th; i++) { const a = texA; tex.set([Math.round(0.6 * a), Math.round(0.5 * a), Math.round(0.4 * a), a], i * 4); }
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tw, th, 0, gl.RGBA, gl.UNSIGNED_BYTE, tex);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    const vs = `#version 300 es
uniform float uKp, uR, uA; in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${W / 2}.0, ${H / 2}.0) + p * vec2(${W / 2}.0, ${H / 2}.0);
  vcolor_S0 = vec4(uA); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(uKp, uR, ${W / 2}.0, ${H / 2}.0); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
    const pr = gl.createProgram();
    for (const [ty, s] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fsSrc]]) { const sh = gl.createShader(ty); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); gl.attachShader(pr, sh); }
    gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
    gl.useProgram(pr);
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
    gl.uniform1f(gl.getUniformLocation(pr, 'uKp'), 0.30); gl.uniform1f(gl.getUniformLocation(pr, 'uR'), Math.floor(r) - 1 + 0.55 + 0.15 * (0.2 / 1.1));
    gl.uniform1f(gl.getUniformLocation(pr, 'uA'), paint);
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    return Array.from(px);
  }, [fsSrc, W, H, r, paint, texA]);
  // Centre of the card, away from the band: data tiles have B < 254.
  const vals = [[], [], []]; let ids = new Set();
  for (let y = 40; y < 88; y++) for (let x = 80; x < 176; x++) {
    const i = (y * W + x) * 4;
    if (px[i + 2] >= 254) ids.add((px[i] << 8) | px[i + 1]); else for (let k = 0; k < 3; k++) vals[k].push(px[i + k] / 255 / 12.75);
  }
  const med = vals.map(v => v.sort((a, b) => a - b)[v.length >> 1]);
  // Codes saturate at 255: 7.84 % of transmission or more reads as 7.84 %.
  const want = [1 - paint * texA / 255, 1 - paint, 1 - texA / 255].map(w => Math.min(w, 1 / 12.75));
  const ok = med.every((m, k) => Math.abs(m - want[k]) < 0.0012) && ids.size === 1;
  if (!ok) fails++;
  console.log(`paint ${paint} texture alpha ${texA}/255: decoded final ${(med[0] * 100).toFixed(3)} % paint ${(med[1] * 100).toFixed(3)} % texture ${(med[2] * 100).toFixed(3)} %`
    + ` (expected ${want.map(w => (w * 100).toFixed(3)).join(' / ')} %), ids ${[...ids].map(i => i.toString(16)).join(',')} ${ok ? 'OK' : 'FAIL'}`);
}
await b.close();
process.exit(fails ? 1 : 0);
