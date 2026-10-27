import base64, json, os, sys
import numpy as np, cv2
from skimage import data
from playwright.sync_api import sync_playwright
from ref import weights, run

SH = '/home/claude/ScreenEnhancer/app/src/main/assets/shaders/'
M = '../fsrcnn_src/FSRCNN_Tensorflow-master/models/'
rd = lambda n: open(SH + n).read()

W_, H_ = 96, 72
img = (cv2.resize(data.astronaut(), (W_, H_), interpolation=cv2.INTER_AREA)).astype(np.uint8)
rgba = np.dstack([img, np.full((H_, W_), 255, np.uint8)])
y_ref = (img[..., 0] * 0.299 + img[..., 1] * 0.587 + img[..., 2] * 0.114).astype(np.float32) / 255

COPY = '#version 300 es\nprecision highp float;\nprecision highp sampler2D;\nuniform sampler2D uF0;\nout vec4 o0;\nvoid main(){ o0 = vec4(texelFetch(uF0, ivec2(gl_FragCoord.xy), 0).r, 0.0, 0.0, 1.0);}\n'
video_decl = 'uniform sampler2D uVideo;'
cfg = {
  'vert': rd('vert.vert'), 'copy': COPY,
  'y': rd('pass_y.frag').replace('//__VIDEO__', video_decl),
  'models': {
    'small': {'a': rd('small_a.frag'), 'convs': [rd('small_c3.frag')], 'b': rd('small_b.frag'), 's': rd('small_s.frag'), 'nt': 2},
    'full': {'a': rd('full_a.frag'), 'convs': [rd('full_c%d.frag' % i) for i in (3, 4, 5, 6)], 'b': rd('full_b.frag'), 's': rd('full_s.frag'), 'nt': 3},
  },
  'w': W_, 'h': H_, 'rgba': base64.b64encode(rgba.tobytes()).decode(),
}

JS = r'''
async (cfg) => {
  const c = document.createElement('canvas'); c.width = 4; c.height = 4; document.body.appendChild(c);
  const gl = c.getContext('webgl2');
  if (!gl) return {error: 'no webgl2'};
  const ext = gl.getExtension('EXT_color_buffer_float');
  if (!ext) return {error: 'no EXT_color_buffer_float'};
  const W = cfg.w, H = cfg.h;
  function sh(type, src) {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('compile: ' + gl.getShaderInfoLog(s));
    return s;
  }
  function prog(fs) {
    const p = gl.createProgram();
    gl.attachShader(p, sh(gl.VERTEX_SHADER, cfg.vert));
    gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'aPos'); gl.bindAttribLocation(p, 1, 'aUV');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p));
    return p;
  }
  const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0);
  gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8);
  function tex(w, h, fmt, filt) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texStorage2D(gl.TEXTURE_2D, 1, fmt, w, h);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filt); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filt);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return {t, w, h};
  }
  const fbo = gl.createFramebuffer();
  const ident = new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
  let stage = ''; function pass(p, targets, inputs, label) {
    stage = label || '';
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    const bufs = [];
    targets.forEach((t, i) => { gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t.t, 0); bufs.push(gl.COLOR_ATTACHMENT0 + i); });
    for (let i = targets.length; i < 4; i++) gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, null, 0);
    gl.drawBuffers(bufs);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete');
    gl.viewport(0, 0, targets[0].w, targets[0].h);
    gl.useProgram(p);
    gl.uniformMatrix4fv(gl.getUniformLocation(p, 'uST'), false, ident);
    inputs.forEach((t, i) => { gl.activeTexture(gl.TEXTURE0 + i); gl.bindTexture(gl.TEXTURE_2D, t.t); });
    inputs.forEach((t, i) => {
      let loc = gl.getUniformLocation(p, 'uF' + i); if (loc === null && i === 0) loc = gl.getUniformLocation(p, 'uY');
      if (loc === null && i === 0) loc = gl.getUniformLocation(p, 'uVideo');
      if (loc !== null) gl.uniform1i(loc, i);
    });
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.finish();
    const e = gl.getError(); if (e) throw new Error('gl error ' + e + ' at ' + stage);
  }
  // source "video" frame
  const vid = tex(W, H, gl.RGBA8, gl.LINEAR);
  const bin = atob(cfg.rgba); const bytes = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  gl.bindTexture(gl.TEXTURE_2D, vid.t); gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
  const out = {};
  const copyP = prog(cfg.copy);
  const yP = prog(cfg.y);
  for (const name of Object.keys(cfg.models)) {
    const m = cfg.models[name];
    const ty = tex(W, H, gl.R16F, gl.NEAREST);
    pass(yP, [ty], [vid], 'Y');
    const mk = () => Array.from({length: m.nt}, () => tex(W, H, gl.RGBA16F, gl.NEAREST));
    let A = mk(), B = mk();
    pass(prog(m.a), A, [ty], name + ' A');
    for (const cs of m.convs) { pass(prog(cs), B, A, name + ' conv'); [A, B] = [B, A]; }
    const t4 = tex(W, H, gl.RGBA16F, gl.NEAREST);
    pass(prog(m.b), [t4], A, name + ' B');
    const t2 = tex(W * 2, H * 2, gl.R16F, gl.LINEAR);
    pass(prog(m.s), [t2], [t4], name + ' S');
    const rb = tex(W * 2, H * 2, gl.RGBA32F, gl.NEAREST);
    pass(copyP, [rb], [t2], name + ' copy');
    const f = new Float32Array(W * 2 * H * 2 * 4);
    gl.readPixels(0, 0, W * 2, H * 2, gl.RGBA, gl.FLOAT, f);
    const r = new Float32Array(W * 2 * H * 2); for (let i = 0; i < r.length; i++) r[i] = f[i * 4];
    let s = ''; const u8 = new Uint8Array(r.buffer); for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    out[name] = btoa(s);
  }
  return out;
}
'''
with sync_playwright() as p:
    b = p.chromium.launch(args=['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
    page = b.new_page()
    res = page.evaluate(JS, cfg)
    b.close()
if 'error' in res:
    print('ERROR', res); sys.exit(1)
Wm = {'small': (weights(M + 'FSRCNN-small_x2.pb'), 1), 'full': (weights(M + 'FSRCNN_x2.pb'), 4)}
for name in ('small', 'full'):
    got = np.frombuffer(base64.b64decode(res[name]), np.float32).reshape(H_ * 2, W_ * 2)
    W, m = Wm[name]
    want = run(y_ref, W, m)
    d = np.abs(got - want)
    inner = d[6:-6, 6:-6]
    print(name, 'GL vs numpy  max=%.5f mean=%.6f | interior max=%.5f mean=%.6f | out range [%.3f, %.3f]' %
          (d.max(), d.mean(), inner.max(), inner.mean(), got.min(), got.max()))
