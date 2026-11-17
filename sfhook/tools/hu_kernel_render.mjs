// Renders one generated fragment program (refract_check output, FillRRect) over a given texture in
// WebGL2 (headless Chromium, SwiftShader) for hu_kernel_report.py --glsl. Card = the whole canvas, r 92,
// profile 0 strength 0.30. Output: RGBA rows bottom-up.
// usage: node hu_kernel_render.mjs <frag> <tex.rgba> <tw> <th> <W> <H> <out.rgba>
import { createRequire } from 'module'; import fs from 'fs';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const [frag, texPath, tw, th, W, H, outPath] = process.argv.slice(2);
const fsSrc = fs.readFileSync(frag,'utf8').replace(/#extension GL_NV_shader_noperspective_interpolation : require\n/, '').replace(/noperspective /g, '');
const tex = [...fs.readFileSync(texPath)];
const CARD = { x: 0, y: 0, w: +W, h: +H, r: 92 };
const b = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const p = await b.newPage(); await p.setContent('<canvas id=c></canvas>');
const out = await p.evaluate(([fsSrc, tex, tw, th, W, H, CARD]) => {
  const c = document.getElementById('c'); c.width = W; c.height = H;
  const gl = c.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tw, th, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(tex));
  for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
  const hs = [CARD.w / 2, CARD.h / 2];
  const vs = `#version 300 es
uniform float uKp, uR; in vec2 p; flat out mediump vec4 vcolor_S0; out highp vec2 varccoord_S0; out highp vec2 vTransformedCoords_2_S0;
flat out highp vec4 voulg_tag; out highp vec2 voulg_vp;
void main() { vec2 dev = vec2(${CARD.x + hs[0]}.0, ${CARD.y + hs[1]}.0) + p * vec2(${hs[0]}.0, ${hs[1]}.0);
  vcolor_S0 = vec4(1.0); varccoord_S0 = vec2(0.0, 1.0); vTransformedCoords_2_S0 = dev / vec2(${W}.0, ${H}.0);
  voulg_tag = vec4(uKp, uR, ${hs[0]}.0, ${hs[1]}.0); voulg_vp = p;
  gl_Position = vec4(dev.x / ${W}.0 * 2.0 - 1.0, 1.0 - dev.y / ${H}.0 * 2.0, 0.0, 1.0); }`;
  const pr = gl.createProgram();
  for (const [ty, s] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fsSrc]]) { const sh = gl.createShader(ty); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); gl.attachShader(pr, sh); }
  gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr));
  gl.useProgram(pr);
  const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.uniform1i(gl.getUniformLocation(pr, 'uTextureSampler_0_S1'), 0);
  const rTag = Math.floor(CARD.r) - 1 + 0.55 + 0.15 * Math.min(1, Math.max(0, (0.30 - 0.1) / 1.1));
  gl.uniform1f(gl.getUniformLocation(pr, 'uKp'), 0.30); gl.uniform1f(gl.getUniformLocation(pr, 'uR'), rTag);
  gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
  let s = ''; for (let i = 0; i < px.length; i += 0x8000) s += String.fromCharCode.apply(null, px.subarray(i, i + 0x8000)); return btoa(s);
}, [fsSrc, tex, +tw, +th, +W, +H, CARD]);
await b.close();
fs.writeFileSync(outPath, Buffer.from(out, 'base64'));
