import base64, re, sys, json
import numpy as np, cv2
from skimage import data
from playwright.sync_api import sync_playwright

SH = '/home/claude/ScreenEnhancer/app/src/main/assets/shaders/'
rd = lambda n: open(SH + n).read()
VIDEO = 'uniform sampler2D uVideo;'

def hr_images():
    out = {}
    for n, im in {'astronaut': data.astronaut(), 'coffee': data.coffee(), 'chelsea': data.chelsea(),
                  'rocket': data.rocket(), 'hubble': data.hubble_deep_field()}.items():
        h, w = im.shape[:2]
        im = im[:min(h, 240) // 2 * 2, :min(w, 320) // 2 * 2]
        out[n] = np.ascontiguousarray(im)
    return out

JS = r'''
async (cfg) => {
  const c = document.createElement('canvas'); c.width = 4; c.height = 4; document.body.appendChild(c);
  const gl = c.getContext('webgl2');
  gl.getExtension('EXT_color_buffer_float');
  function sh(type, src) { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('compile: ' + gl.getShaderInfoLog(s)); return s; }
  function prog(fs) { const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, cfg.vert)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'aPos'); gl.bindAttribLocation(p, 1, 'aUV'); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p)); return p; }
  const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0);
  gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8);
  const ident = new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
  const fbo = gl.createFramebuffer();
  function tex(w, h, filt) { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t); gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, w, h);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, filt], [gl.TEXTURE_MAG_FILTER, filt], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    return t; }
  const pe = prog(cfg.easu), pr = prog(cfg.rcas); const pc = cfg.classic ? prog(cfg.classic) : null;
  function draw(p, target, w, h, ins, uni) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0);
    gl.viewport(0, 0, w, h); gl.useProgram(p);
    gl.uniformMatrix4fv(gl.getUniformLocation(p, 'uST'), false, ident);
    ins.forEach(([name, t], i) => { gl.activeTexture(gl.TEXTURE0 + i); gl.bindTexture(gl.TEXTURE_2D, t); gl.uniform1i(gl.getUniformLocation(p, name), i); });
    for (const [k, v] of Object.entries(uni)) { const l = gl.getUniformLocation(p, k); if (Array.isArray(v)) gl.uniform2f(l, v[0], v[1]); else gl.uniform1f(l, v); }
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); gl.finish();
    const e = gl.getError(); if (e) throw new Error('gl error ' + e);
  }
  function b64(u8) { let s = ''; for (let i = 0; i < u8.length; i += 8192) s += String.fromCharCode.apply(null, u8.subarray(i, i + 8192)); return btoa(s); }
  const res = [];
  for (const im of cfg.images) {
    const W = im.w, H = im.h, bin = atob(im.rgba); const bytes = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const vid = tex(W, H, gl.LINEAR); gl.bindTexture(gl.TEXTURE_2D, vid); gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
    const OW = W * cfg.scaleX, OH = H * cfg.scaleY;
    const up = tex(OW, OH, gl.NEAREST), fin = tex(OW, OH, gl.NEAREST);
    if (cfg.classic) { draw(pc, fin, OW, OH, [['uVideo', vid]], {uTexel: [1 / W, 1 / H], uSharp: cfg.sharp, uContrast: 1.0, uSat: 1.0, uWipe: 0.0});
      const r0 = new Uint8Array(OW * OH * 4); gl.readPixels(0, 0, OW, OH, gl.RGBA, gl.UNSIGNED_BYTE, r0); res.push({easu: b64(r0), rcas: b64(r0), ow: OW, oh: OH}); continue; }
    draw(pe, up, OW, OH, [['uVideo', vid]], {uSize: [W, H], uUp: 1.0});
    const a = new Uint8Array(OW * OH * 4); gl.readPixels(0, 0, OW, OH, gl.RGBA, gl.UNSIGNED_BYTE, a);
    draw(pr, fin, OW, OH, [['uVideo', vid], ['uUp', up]], {uSharp: cfg.sharp, uContrast: 1.0, uSat: 1.0, uWipe: 0.0});
    const r = new Uint8Array(OW * OH * 4); gl.readPixels(0, 0, OW, OH, gl.RGBA, gl.UNSIGNED_BYTE, r);
    res.push({easu: b64(a), rcas: b64(r), ow: OW, oh: OH});
  }
  return res;
}
'''

def run_gl(page, lr_imgs, across, along, sharp, scale=2, kernel=0, lo=None, hi=None, classic=False):
    easu = rd('final_easu.frag').replace('//__VIDEO__', VIDEO)
    easu = re.sub(r'const float ACROSS = [0-9.]+;', 'const float ACROSS = %s;' % across, easu)
    easu = re.sub(r'const float ALONG = [0-9.]+;', 'const float ALONG = %s;' % along, easu)
    if lo is not None: easu = re.sub(r'const float EDGE_LO = [0-9.]+;', 'const float EDGE_LO = %s;' % lo, easu)
    if hi is not None: easu = re.sub(r'const float EDGE_HI = [0-9.]+;', 'const float EDGE_HI = %s;' % hi, easu)
    rcas = rd('final_rcas.frag').replace('//__VIDEO__', VIDEO)
    cfg = {'vert': rd('vert.vert'), 'easu': easu, 'rcas': rcas, 'sharp': sharp, 'scaleX': scale, 'scaleY': scale,
           'images': [{'w': im.shape[1], 'h': im.shape[0],
                       'rgba': base64.b64encode(np.dstack([im, np.full(im.shape[:2], 255, np.uint8)]).tobytes()).decode()} for im in lr_imgs]}
    if classic: cfg['classic'] = open('/home/claude/ScreenEnhancer/tools/old_final_classic.frag').read().replace('//__VIDEO__', VIDEO)
    res = page.evaluate(JS, cfg)
    outs = []
    for r in res:
        def dec(s): return np.frombuffer(base64.b64decode(s), np.uint8).reshape(r['oh'], r['ow'], 4)[..., :3]
        outs.append((dec(r['easu']), dec(r['rcas'])))
    return outs

def psnr(a, b):
    a = a.astype(np.float32); b = b.astype(np.float32)
    m = np.mean((a[4:-4, 4:-4] - b[4:-4, 4:-4]) ** 2)
    return 10 * np.log10(255 ** 2 / m)

if __name__ == '__main__':
    from skimage.metrics import structural_similarity as ssim
    imgs = hr_images()
    names = list(imgs); hr = list(imgs.values())
    lr = [cv2.resize(im, (im.shape[1] // 2, im.shape[0] // 2), interpolation=cv2.INTER_AREA) for im in hr]
    def score(outs):
        return (np.mean([psnr(h, o) for h, o in zip(hr, outs)]),
                np.mean([ssim(h[4:-4, 4:-4], o[4:-4, 4:-4], channel_axis=2, data_range=255) for h, o in zip(hr, outs)]))
    variants = {}
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
        page = b.new_page()
        variants['old: bilinear+sharpen 0.6'] = [o[0] for o in run_gl(page, lr, 1.0, 1.0, 0.6, classic=True)]
        variants['bicubic'] = [cv2.resize(l, (h.shape[1], h.shape[0]), interpolation=cv2.INTER_CUBIC) for h, l in zip(hr, lr)]
        variants['EASU iso'] = [o[0] for o in run_gl(page, lr, 1.0, 1.0, 0.0, kernel=1)]
        variants['EASU adaptive 1.15/0.65'] = [o[0] for o in run_gl(page, lr, 1.15, 0.65, 0.0, kernel=1)]
        for sh in (0.3, 0.6, 1.0):
            variants['EASU adaptive + RCAS %.1f' % sh] = [o[1] for o in run_gl(page, lr, 1.15, 0.65, sh, kernel=1)]
        b.close()
    for k, v in variants.items():
        p_, s_ = score(v)
        print('%-30s PSNR %.2f SSIM %.4f' % (k, p_, s_))
    # visual montage: zoomed crop of one image
    i = names.index('astronaut')
    g = cv2.Sobel(cv2.cvtColor(hr[i], cv2.COLOR_RGB2GRAY).astype(np.float32), cv2.CV_32F, 1, 1)
    e = cv2.boxFilter(g * g, -1, (100, 70))
    cy, cx = np.unravel_index(np.argmax(e), e.shape)
    hh, ww = 70, 100
    y0 = int(np.clip(cy - hh // 2, 0, hr[i].shape[0] - hh)); x0 = int(np.clip(cx - ww // 2, 0, hr[i].shape[1] - ww))
    print('crop at', y0, x0)
    tiles = []
    for k, v in [('original', hr), *variants.items()]:
        im = (hr if k == 'original' else v)[i][y0:y0 + hh, x0:x0 + ww]
        im = cv2.resize(im, None, fx=4, fy=4, interpolation=cv2.INTER_NEAREST)
        im = cv2.putText(np.ascontiguousarray(im), k[:26], (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 0), 1)
        tiles.append(im)
    rows = [np.hstack(tiles[j:j + 3]) for j in range(0, len(tiles), 3) if len(tiles[j:j + 3]) == 3]
    cv2.imwrite('montage.png', cv2.cvtColor(np.vstack(rows), cv2.COLOR_RGB2BGR))
    print('tiles', len(tiles))
