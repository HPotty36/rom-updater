import sys, struct, zipfile, lzma, bz2, hashlib

def varint(b, i):
    v = 0; s = 0
    while True:
        c = b[i]; i += 1
        v |= (c & 0x7F) << s; s += 7
        if not c & 0x80: return v, i

def fields(b):
    i = 0
    while i < len(b):
        k, i = varint(b, i); f, w = k >> 3, k & 7
        if w == 0: v, i = varint(b, i)
        elif w == 2:
            n, i = varint(b, i); v = b[i:i+n]; i += n
        elif w == 1: v = b[i:i+8]; i += 8
        elif w == 5: v = b[i:i+4]; i += 4
        else: raise ValueError(w)
        yield f, v

def main(zpath, name, out):
    z = zipfile.ZipFile(zpath)
    info = z.getinfo('payload.bin')
    assert info.compress_type == 0
    with open(zpath, 'rb') as fh:
        fh.seek(info.header_offset)
        h = fh.read(30)
        n, m = struct.unpack('<HH', h[26:30])
        base = info.header_offset + 30 + n + m
        fh.seek(base)
        assert fh.read(4) == b'CrAU'
        ver, msize = struct.unpack('>QQ', fh.read(16))
        sigsize = struct.unpack('>I', fh.read(4))[0]
        manifest = fh.read(msize)
        data0 = base + 24 + msize + sigsize
        bs = 4096; names = []
        for f, v in fields(manifest):
            if f == 3: bs = v
            if f != 13: continue
            pname = None; ops = []; size = None; phash = None
            for pf, pv in fields(v):
                if pf == 1: pname = pv.decode()
                elif pf == 8: ops.append(pv)
                elif pf == 7:
                    for nf, nv in fields(pv):
                        if nf == 1: size = nv
                        if nf == 2: phash = nv
            names.append(pname)
            if pname != name: continue
            with open(out, 'wb') as o:
                o.truncate(size)
                for op in ops:
                    t = 0; off = 0; ln = 0; ext = []
                    for of, ov in fields(op):
                        if of == 1: t = ov
                        elif of == 2: off = ov
                        elif of == 3: ln = ov
                        elif of == 6:
                            d = dict(fields(ov)); ext.append((d.get(1, 0), d.get(2, 0)))
                    fh.seek(data0 + off); raw = fh.read(ln)
                    if t == 0: buf = raw
                    elif t == 1: buf = bz2.decompress(raw)
                    elif t == 8: buf = lzma.decompress(raw)
                    elif t in (6, 7): buf = b'\0' * (sum(e[1] for e in ext) * bs)
                    else: raise ValueError('op type %d' % t)
                    p = 0
                    for s, c in ext:
                        o.seek(s * bs); o.write(buf[p:p + c * bs]); p += c * bs
            got = hashlib.sha256(open(out, 'rb').read()).digest()
            print(name, size, 'hash_ok' if got == phash else 'HASH_MISMATCH', got.hex())
        print('partitions:', ' '.join(names))

main(*sys.argv[1:4])
