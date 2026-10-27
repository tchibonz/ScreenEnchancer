import sys, struct, numpy as np

def varint(b, i):
    r = 0; s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7F) << s
        if not c & 0x80: return r, i
        s += 7

def fields(b):
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        f, w = key >> 3, key & 7
        if w == 0:
            v, i = varint(b, i)
        elif w == 2:
            l, i = varint(b, i); v = b[i:i+l]; i += l
        elif w == 5:
            v = b[i:i+4]; i += 4
        elif w == 1:
            v = b[i:i+8]; i += 8
        else:
            raise ValueError(w)
        yield f, w, v

def parse_tensor(b):
    dims = []; content = None; floats = []
    for f, w, v in fields(b):
        if f == 2:  # shape
            for f2, w2, v2 in fields(v):
                if f2 == 2:
                    for f3, w3, v3 in fields(v2):
                        if f3 == 1: dims.append(v3)
        elif f == 4: content = v
        elif f == 5:
            if w == 2: floats += list(struct.unpack('<%df' % (len(v)//4), v))
            else: floats.append(struct.unpack('<f', v)[0])
    if content is not None:
        arr = np.frombuffer(content, dtype='<f4').copy()
    else:
        arr = np.array(floats, dtype=np.float32)
        n = int(np.prod(dims)) if dims else 1
        if arr.size == 1 and n > 1: arr = np.full(n, arr[0], np.float32)
    return arr.reshape(dims) if dims else arr

def load(path):
    b = open(path, 'rb').read()
    nodes = []
    for f, w, v in fields(b):
        if f != 1: continue
        n = {'name': None, 'op': None, 'inputs': [], 'attr': {}}
        for f2, w2, v2 in fields(v):
            if f2 == 1: n['name'] = v2.decode()
            elif f2 == 2: n['op'] = v2.decode()
            elif f2 == 3: n['inputs'].append(v2.decode())
            elif f2 == 5:
                key = None; val = None
                for f3, w3, v3 in fields(v2):
                    if f3 == 1: key = v3.decode()
                    elif f3 == 2: val = v3
                n['attr'][key] = val
        if n['op'] == 'Const' and 'value' in n['attr']:
            for f4, w4, v4 in fields(n['attr']['value']):
                if f4 == 8: n['tensor'] = parse_tensor(v4)
        nodes.append(n)
    return nodes

if __name__ == '__main__':
    for n in load(sys.argv[1]):
        t = n.get('tensor')
        print(n['op'].ljust(14), n['name'].ljust(28), n['inputs'], (t.shape if t is not None else ''))
