"""Builds app/src/main/assets/ns_enhancer.js (the in-page enhancer) from the template and the shader assets."""
import json, os
HERE = os.path.dirname(os.path.abspath(__file__))
SH = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'shaders')
names = {'dn': 'dn.frag', 'vert': 'vert.vert', 'pass_y': 'pass_y.frag', 'final_ai': 'final_ai.frag', 'final_easu': 'final_easu.frag',
         'final_rcas': 'final_rcas.frag', 'small_a': 'small_a.frag', 'small_c3': 'small_c3.frag', 'small_b': 'small_b.frag',
         'small_s': 'small_s.frag', 'full_a': 'full_a.frag', 'full_b': 'full_b.frag', 'full_s': 'full_s.frag'}
for i in (3, 4, 5, 6):
    names['full_c%d' % i] = 'full_c%d.frag' % i
shaders = {k: open(os.path.join(SH, v)).read() for k, v in names.items()}
tpl = open(os.path.join(HERE, 'ns_enhancer.template.js')).read()
out = tpl.replace('__SHADERS__', json.dumps(shaders, separators=(',', ':')))
dst = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'ns_enhancer.js')
open(dst, 'w').write(out)
print(dst, len(out) // 1024, 'KB')
