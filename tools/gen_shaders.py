"""Generates GLSL ES 3.00 shaders that run FSRCNN (luma only) on the GPU.
Model weights: Saafke/FSRCNN_Tensorflow (Apache-2.0)."""
import os, sys
import numpy as np
from parse_pb import load

OUT = '/home/claude/ScreenEnhancer/app/src/main/assets/shaders'
M = '../fsrcnn_src/FSRCNN_Tensorflow-master/models/'

def fl(v):
    s = '%.9g' % float(v)
    if '.' not in s and 'e' not in s and 'n' not in s:
        s += '.0'
    return s

def arr(name, a):
    a = np.asarray(a, np.float32).reshape(-1)
    return 'const float %s[%d] = float[%d](%s);\n' % (name, a.size, a.size, ','.join(fl(x) for x in a))

HEAD = '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n'

def outs(nt):
    return ''.join('layout(location=%d) out vec4 o%d;\n' % (i, i) for i in range(nt))

def pack_outputs(var, S, nt):
    lines = []
    for t in range(nt):
        comps = []
        for c in range(4):
            k = t * 4 + c
            comps.append('%s[%d]' % (var, k) if k < S else '0.0')
        lines.append('  o%d = vec4(%s);' % (t, ','.join(comps)))
    return '\n'.join(lines) + '\n'

def unpack_inputs(S, nt, qvar='q', var='v'):
    lines = []
    for t in range(nt):
        lines.append('  vec4 t%d = texelFetch(uF%d, %s, 0);' % (t, t, qvar))
        for c in range(4):
            k = t * 4 + c
            if k < S:
                lines.append('  %s[%d] = t%d.%s;' % (var, k, t, 'xyzw'[c]))
    return '\n'.join(lines) + '\n'

def samplers(nt):
    return ''.join('uniform sampler2D uF%d;\n' % i for i in range(nt))

def shader_a(W, D, S):
    nt = (S + 3) // 4
    s = HEAD + 'uniform sampler2D uY;\n' + outs(nt)
    s += arr('W1', W['f1']) + arr('B1', W['b1']) + arr('A1', W['alpha1'])
    s += arr('W2', W['f2']) + arr('B2', W['b2']) + arr('A2', W['alpha2'])
    s += '''
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  ivec2 hi = textureSize(uY, 0) - 1;
  float y[25];
  for (int j = 0; j < 5; j++)
    for (int i = 0; i < 5; i++)
      y[j * 5 + i] = texelFetch(uY, clamp(p + ivec2(i - 2, j - 2), ivec2(0), hi), 0).r;
  float acc[%d];
  for (int k = 0; k < %d; k++) acc[k] = B2[k];
  for (int c = 0; c < %d; c++) {
    float a = B1[c];
    for (int t = 0; t < 25; t++) a += y[t] * W1[t * %d + c];
    a = a > 0.0 ? a : A1[c] * a;
    for (int k = 0; k < %d; k++) acc[k] += a * W2[c * %d + k];
  }
  for (int k = 0; k < %d; k++) acc[k] = acc[k] > 0.0 ? acc[k] : A2[k] * acc[k];
''' % (S, S, D, D, S, S, S)
    s += pack_outputs('acc', S, nt) + '}\n'
    return s

def shader_conv(W, S, idx):
    nt = (S + 3) // 4
    s = HEAD + samplers(nt) + outs(nt)
    s += arr('W', W['f%d' % idx]) + arr('B', W['b%d' % idx]) + arr('A', W['alpha%d' % idx])
    s += '''
void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  ivec2 hi = textureSize(uF0, 0) - 1;
  float acc[%d];
  for (int k = 0; k < %d; k++) acc[k] = B[k];
  for (int ky = 0; ky < 3; ky++) {
    for (int kx = 0; kx < 3; kx++) {
      ivec2 q = clamp(p + ivec2(kx - 1, ky - 1), ivec2(0), hi);
      float v[%d];
''' % (S, S, S)
    s += unpack_inputs(S, nt).replace('\n  ', '\n      ').replace('  vec4', '      vec4', 1) if False else ''
    # (indent not important in GLSL)
    s += unpack_inputs(S, nt)
    s += '''      for (int i = 0; i < %d; i++) {
        for (int o = 0; o < %d; o++) acc[o] += v[i] * W[((ky * 3 + kx) * %d + i) * %d + o];
      }
    }
  }
  for (int k = 0; k < %d; k++) acc[k] = acc[k] > 0.0 ? acc[k] : A[k] * acc[k];
''' % (S, S, S, S, S)
    s += pack_outputs('acc', S, nt) + '}\n'
    return s

def shader_b(W, D, S, i7, i8):
    nt = (S + 3) // 4
    s = HEAD + samplers(nt) + outs(1)
    s += arr('W7', W['f%d' % i7]) + arr('B7', W['b%d' % i7]) + arr('A7', W['alpha%d' % i7]) + arr('W8', W['f%d' % i8])
    s += '''
void main() {
  ivec2 q = ivec2(gl_FragCoord.xy);
  float v[%d];
''' % S
    s += unpack_inputs(S, nt)
    s += '''  float r[4] = float[4](0.0, 0.0, 0.0, 0.0);
  for (int c = 0; c < %d; c++) {
    float a = B7[c];
    for (int i = 0; i < %d; i++) a += v[i] * W7[i * %d + c];
    a = a > 0.0 ? a : A7[c] * a;
    for (int k = 0; k < 4; k++) r[k] += a * W8[c * 4 + k];
  }
  o0 = vec4(r[0], r[1], r[2], r[3]);
}
''' % (D, S, D)
    return s

def shader_shuffle(bias):
    return HEAD + 'uniform sampler2D uF0;\n' + outs(1) + '''
void main() {
  ivec2 q = ivec2(gl_FragCoord.xy);
  vec4 v = texelFetch(uF0, q >> 1, 0);
  int k = (q.y & 1) * 2 + (q.x & 1);
  float y = k == 0 ? v.x : (k == 1 ? v.y : (k == 2 ? v.z : v.w));
  o0 = vec4(y + %s, 0.0, 0.0, 1.0);
}
''' % fl(bias)

VERT = '''#version 300 es
in vec2 aPos;
in vec2 aUV;
uniform mat4 uST;
out vec2 vUV;
out vec2 vVideoUV;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vUV = aUV;
  vVideoUV = (uST * vec4(aUV, 0.0, 1.0)).xy;
}
'''

# //__VIDEO__ is replaced by the loader with the right sampler declaration.
PASS_Y = HEAD + '//__VIDEO__\nin vec2 vVideoUV;\n' + outs(1) + '''
void main() {
  vec3 c = texture(uVideo, vVideoUV).rgb;
  o0 = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
'''

FINAL_AI = HEAD + '//__VIDEO__\nuniform sampler2D uNet;\nuniform vec2 uPx;\nuniform float uSharp;\nuniform float uContrast;\nuniform float uSat;\nuniform float uWipe;\nin vec2 vUV;\nin vec2 vVideoUV;\nout vec4 fragColor;\n' + '''
void main() {
  vec3 c = texture(uVideo, vVideoUV).rgb;
  if (vUV.x < uWipe) { fragColor = vec4(c, 1.0); return; }
  float ys = dot(c, vec3(0.299, 0.587, 0.114));
  float yn = texture(uNet, vUV).r;
  if (uSharp > 0.001) {
    float e = texture(uNet, vUV + vec2(uPx.x, 0.0)).r;
    float w = texture(uNet, vUV - vec2(uPx.x, 0.0)).r;
    float n = texture(uNet, vUV + vec2(0.0, uPx.y)).r;
    float s = texture(uNet, vUV - vec2(0.0, uPx.y)).r;
    yn += (yn - 0.25 * (e + w + n + s)) * uSharp;
  }
  vec3 rgb = c + (yn - ys);
  float l = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
  rgb = mix(vec3(l), rgb, uSat);
  rgb = (rgb - 0.5) * uContrast + 0.5;
  fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
'''

FINAL_CLASSIC = HEAD + '//__VIDEO__\nuniform vec2 uTexel;\nuniform float uSharp;\nuniform float uContrast;\nuniform float uSat;\nuniform float uWipe;\nin vec2 vUV;\nin vec2 vVideoUV;\nout vec4 fragColor;\n' + '''
void main() {
  vec3 e = texture(uVideo, vVideoUV).rgb;
  if (vUV.x < uWipe) { fragColor = vec4(e, 1.0); return; }
  vec3 c = e;
  if (uSharp > 0.001) {
    vec3 b = texture(uVideo, vVideoUV + vec2(0.0, -uTexel.y)).rgb;
    vec3 d = texture(uVideo, vVideoUV + vec2(-uTexel.x, 0.0)).rgb;
    vec3 f = texture(uVideo, vVideoUV + vec2(uTexel.x, 0.0)).rgb;
    vec3 h = texture(uVideo, vVideoUV + vec2(0.0, uTexel.y)).rgb;
    vec3 mn = min(min(min(d, e), min(f, b)), h);
    vec3 mx = max(max(max(d, e), max(f, b)), h);
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, uSharp);
    vec3 w = amp * peak;
    c = (b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w);
  }
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, uSat);
  c = (c - 0.5) * uContrast + 0.5;
  fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
'''

def write(name, text):
    with open(os.path.join(OUT, name), 'w') as f:
        f.write(text)

def main():
    write('vert.vert', VERT)
    write('pass_y.frag', PASS_Y)
    write('final_ai.frag', FINAL_AI)
    write('final_classic.frag', FINAL_CLASSIC)

    Ws = {n['name']: n['tensor'] for n in load(M + 'FSRCNN-small_x2.pb') if 'tensor' in n}
    write('small_a.frag', shader_a(Ws, 32, 5))
    write('small_c3.frag', shader_conv(Ws, 5, 3))
    write('small_b.frag', shader_b(Ws, 32, 5, 4, 5))
    write('small_s.frag', shader_shuffle(float(Ws['b5'][0])))

    Wf = {n['name']: n['tensor'] for n in load(M + 'FSRCNN_x2.pb') if 'tensor' in n}
    write('full_a.frag', shader_a(Wf, 56, 12))
    for i in (3, 4, 5, 6):
        write('full_c%d.frag' % i, shader_conv(Wf, 12, i))
    write('full_b.frag', shader_b(Wf, 56, 12, 7, 8))
    write('full_s.frag', shader_shuffle(float(Wf['b8'][0])))

main()
for f in sorted(os.listdir(OUT)):
    print(f.ljust(22), os.path.getsize(os.path.join(OUT, f)))
