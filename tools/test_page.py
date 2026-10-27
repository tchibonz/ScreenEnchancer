import base64, http.server, socketserver, threading, os, sys, functools, time
import numpy as np, cv2
from playwright.sync_api import sync_playwright

ROOT = '/tmp/claude-0/-home-claude/30754570-9322-5885-904e-5d51d6cbfe11/scratchpad/webtest'
JS = open('/home/claude/ScreenEnhancer/app/src/main/assets/ns_enhancer.js').read()

class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def end_headers(self):
        self.send_header('Accept-Ranges', 'bytes'); super().end_headers()

def serve(port):
    h = functools.partial(Q, directory=ROOT)
    srv = socketserver.ThreadingTCPServer(('127.0.0.1', port), h)
    srv.allow_reuse_address = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv
a = serve(8771); b = serve(8772)

def psnr(x, y):
    m = np.mean((x.astype(np.float32) - y.astype(np.float32)) ** 2)
    return 99 if m == 0 else 10 * np.log10(255 ** 2 / m)

GRAB = '''
async (mode) => {
  const e = window.__nsEnhancers[0];
  if (!e) return {error: 'no enhancer'};
  window.__nsSet({ai: mode});
  const v = e.v; v.pause();
  e.active = -1;
  e.draw();                               // draw + read back in the same task
  const png = e.canvas.toDataURL('image/png');
  const active = e.active;
  window.__nsSet({ai: 1});
  // reference: the same video frame scaled by the browser's own 2D canvas
  const c2 = document.createElement('canvas'); c2.width = e.canvas.width; c2.height = e.canvas.height;
  c2.getContext('2d').drawImage(v, 0, 0, c2.width, c2.height);
  return {png, ref: c2.toDataURL('image/png'), w: e.canvas.width, h: e.canvas.height, active: active,
          shown: e.shown, opacity: v.style.opacity, disp: e.canvas.style.display, vw: v.videoWidth, vh: v.videoHeight};
}
'''
def dec(url):
    return cv2.imdecode(np.frombuffer(base64.b64decode(url.split(',')[1]), np.uint8), cv2.IMREAD_COLOR)

with sync_playwright() as p:
    br = p.chromium.launch(args=['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader',
                                 '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'])
    ctx = br.new_context(viewport={'width': 900, 'height': 520})
    ctx.set_default_timeout(150000)
    ctx.add_init_script(JS + '\n;window.__nsParams.ai = 1;')
    # ---------------- 1. plain page
    page = ctx.new_page()
    logs = []
    page.on('console', lambda m: logs.append(m.text) if m.text.startswith('NS:') else None)
    page.on('pageerror', lambda e: print('PAGE ERROR', e))
    page.goto('http://127.0.0.1:8771/page.html')
    page.wait_for_function('window.__nsEnhancers && window.__nsEnhancers.length > 0', timeout=20000)
    time.sleep(1.5)
    for mode, name in [(1, 'classic (AI off)'), (2, 'AI small'), (3, 'AI full')]:
        r = page.evaluate(GRAB, mode)
        if 'error' in r: print(name, r); continue
        out, ref = dec(r['png']), dec(r['ref'])
        print('%-18s canvas %dx%d video %dx%d active=%d shown=%s video-opacity=%s display=%r  PSNR vs plain scaling %.2f dB'
              % (name, r['w'], r['h'], r['vw'], r['vh'], r['active'], r['shown'], r['opacity'], r['disp'], psnr(out, ref)))
        cv2.imwrite('%s/out_%d.png' % (ROOT, mode), out); cv2.imwrite('%s/ref.png' % ROOT, ref)
    print('layout check:', page.evaluate('''() => { const c = document.querySelector('canvas[data-ns]'); const v = document.getElementById('vid');
      const rc = c.getBoundingClientRect(), rv = v.getBoundingClientRect(); return {canvas: [rc.x, rc.y, rc.width, rc.height].map(Math.round), video: [rv.x, rv.y, rv.width, rv.height].map(Math.round)}; }'''))
    print('logs:', logs[-3:])
    # ---------------- 2. iframe
    page2 = ctx.new_page()
    page2.on('pageerror', lambda e: print('PAGE ERROR', e))
    page2.goto('http://127.0.0.1:8771/frame.html')
    time.sleep(4)
    fr = [f for f in page2.frames if f != page2.main_frame][0]
    n = fr.evaluate('window.__nsEnhancers ? window.__nsEnhancers.length : -1')
    page2.evaluate('window.__nsSet({ai: 3, sharp: 0.33})')
    time.sleep(1)
    print('iframe: enhancers in frame =', n, '| params arrived in iframe:', fr.evaluate('JSON.stringify({ai: window.__nsParams.ai, sharp: window.__nsParams.sharp})'))
    # ---------------- 3. cross-origin video without CORS
    page3 = ctx.new_page()
    logs3 = []
    page3.on('console', lambda m: logs3.append(m.text) if m.text.startswith('NS:') else None)
    page3.goto('http://127.0.0.1:8771/cross.html')
    time.sleep(4)
    print('cross-origin: enhancers =', page3.evaluate('window.__nsEnhancers.length'), '| video opacity =', repr(page3.evaluate("document.getElementById('vid').style.opacity")),
          '| canvases =', page3.evaluate("document.querySelectorAll('canvas[data-ns]').length"), '| logs:', logs3[-2:])
    br.close()
