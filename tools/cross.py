import time, http.server, socketserver, threading, functools
from playwright.sync_api import sync_playwright
ROOT = '/tmp/claude-0/-home-claude/30754570-9322-5885-904e-5d51d6cbfe11/scratchpad/webtest'
JS = open('/home/claude/ScreenEnhancer/app/src/main/assets/ns_enhancer.js').read()
class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
for port in (8771, 8772):
    s = socketserver.ThreadingTCPServer(('127.0.0.1', port), functools.partial(Q, directory=ROOT)); s.allow_reuse_address = True
    threading.Thread(target=s.serve_forever, daemon=True).start()
with sync_playwright() as p:
    br = p.chromium.launch(args=['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'])
    ctx = br.new_context(viewport={'width': 900, 'height': 520}); ctx.set_default_timeout(60000)
    ctx.add_init_script(JS + '\n;window.__nsParams.ai = 1;')
    page = ctx.new_page(); logs = []
    page.on('console', lambda m: logs.append(m.text) if m.text.startswith('NS:') else None)
    page.goto('http://127.0.0.1:8771/cross.html'); time.sleep(5)
    print('cross-origin: enhancers =', page.evaluate('window.__nsEnhancers.length'),
          '| video opacity =', repr(page.evaluate("document.getElementById('vid').style.opacity")),
          '| canvases =', page.evaluate("document.querySelectorAll('canvas[data-ns]').length"), '| logs:', logs[-2:])
    br.close()
