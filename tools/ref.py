import numpy as np
from parse_pb import load

def weights(path):
    d = {n['name']: n['tensor'] for n in load(path) if 'tensor' in n}
    return d

def conv(x, w):  # x HxWxCin, w kh,kw,Cin,Cout ; SAME zero padding, correlation
    kh, kw, ci, co = w.shape
    ph, pw = kh // 2, kw // 2
    H, W, _ = x.shape
    xp = np.pad(x, ((ph, ph), (pw, pw), (0, 0)))
    out = np.zeros((H, W, co), np.float32)
    for dy in range(kh):
        for dx in range(kw):
            patch = xp[dy:dy+H, dx:dx+W, :]
            out += patch @ w[dy, dx]
    return out

def prelu(x, a):
    return np.maximum(x, 0) + a * np.minimum(x, 0)

def run(y, W, m):
    # y: HxW float32 in [0,1]
    x = y[..., None]
    x = prelu(conv(x, W['f1']) + W['b1'], W['alpha1'])
    x = prelu(conv(x, W['f2']) + W['b2'], W['alpha2'])
    for i in range(m):
        k = 3 + i
        x = prelu(conv(x, W['f%d' % k]) + W['b%d' % k], W['alpha%d' % k])
    k = 3 + m
    x = prelu(conv(x, W['f%d' % k]) + W['b%d' % k], W['alpha%d' % k])
    x = conv(x, W['f%d' % (k + 1)])  # H W 4
    H, Wd, _ = x.shape
    out = np.zeros((H * 2, Wd * 2), np.float32)
    for dy in range(2):
        for dx in range(2):
            out[dy::2, dx::2] = x[..., dy * 2 + dx]
    return out + W['b%d' % (k + 1)][0]
