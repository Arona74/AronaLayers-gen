"""Block-state histogram of the fully generated chunks of a world's overworld region files.
usage: mca_hist.py <world dir> <out json> <cx0,cz0,nx,nz>...   (only fully generated chunks in the rectangles)"""
import sys, os, zlib, gzip, struct, json, hashlib, collections, io

def read_nbt(buf):
    pos = 0
    def u(fmt, n):
        nonlocal pos
        v = struct.unpack_from(fmt, buf, pos)[0]; pos += n; return v
    def s():
        nonlocal pos
        n = u('>H', 2); v = buf[pos:pos+n].decode('utf-8', 'replace'); pos += n; return v
    def payload(t):
        nonlocal pos
        if t == 1: return u('>b', 1)
        if t == 2: return u('>h', 2)
        if t == 3: return u('>i', 4)
        if t == 4: return u('>q', 8)
        if t == 5: return u('>f', 4)
        if t == 6: return u('>d', 8)
        if t == 7:
            n = u('>i', 4); v = buf[pos:pos+n]; pos += n; return v
        if t == 8: return s()
        if t == 9:
            et = u('>b', 1); n = u('>i', 4); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u('>b', 1)
                if tt == 0: return d
                k = s(); d[k] = payload(tt)
        if t == 11:
            n = u('>i', 4); v = struct.unpack_from('>%di' % n, buf, pos); pos += 4*n; return v
        if t == 12:
            n = u('>i', 4); v = struct.unpack_from('>%dQ' % n, buf, pos); pos += 8*n; return v
        raise ValueError(t)
    t = u('>b', 1); s(); return payload(t)

def state_name(p):
    props = p.get('Properties')
    if not props: return p['Name']
    return p['Name'] + '[' + ','.join('%s=%s' % kv for kv in sorted(props.items())) + ']'

def main(world, out):
    total = collections.Counter(); chunks = {}
    rdir = os.path.join(world, 'region')
    for fn in sorted(os.listdir(rdir)):
        if not fn.endswith('.mca'): continue
        data = open(os.path.join(rdir, fn), 'rb').read()
        if len(data) < 8192: continue
        for i in range(1024):
            off = struct.unpack_from('>I', data, i*4)[0]
            if off == 0: continue
            start = (off >> 8) * 4096
            ln, comp = struct.unpack_from('>IB', data, start)
            raw = data[start+5:start+4+ln]
            raw = zlib.decompress(raw) if comp == 2 else gzip.decompress(raw) if comp == 1 else raw
            c = read_nbt(raw)
            cx, cz = c['xPos'], c['zPos']
            if not any(x0 <= cx < x0 + nx and z0 <= cz < z0 + nz for x0, z0, nx, nz in RECTS): continue
            if c.get('Status') != 'minecraft:full': continue
            cnt = collections.Counter(); h = hashlib.sha1()
            for sec in sorted(c['sections'], key=lambda s: s['Y']):
                bs = sec.get('block_states')
                if not bs: continue
                pal = [state_name(p) for p in bs['palette']]
                d = bs.get('data')
                if d is None or len(pal) == 1:
                    cnt[pal[0]] += 4096; h.update(('%d:%s*4096;' % (sec['Y'], pal[0])).encode()); continue
                bits = max(4, (len(pal) - 1).bit_length()); per = 64 // bits; mask = (1 << bits) - 1
                idx = []
                for L in d:
                    for k in range(per):
                        idx.append((L >> (k*bits)) & mask)
                        if len(idx) == 4096: break
                    if len(idx) == 4096: break
                names = [pal[j] for j in idx]
                cnt.update(names)
                h.update(('%d:' % sec['Y']).encode()); h.update('|'.join(names).encode())
            total.update(cnt)
            chunks['%d,%d' % (cx, cz)] = h.hexdigest()
    json.dump({'chunks': chunks, 'total': dict(sorted(total.items()))}, open(out, 'w'), indent=1, sort_keys=True)
    print(len(chunks), 'full chunks,', len(total), 'distinct states')

RECTS = [tuple(int(v) for v in a.split(",")) for a in sys.argv[3:]]
main(sys.argv[1], sys.argv[2])
