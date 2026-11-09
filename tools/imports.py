#!/usr/bin/env python3
"""Print the import table of a PE32 file: python3 tools/imports.py file.exe"""
import struct, sys
d = open(sys.argv[1], 'rb').read()
e = struct.unpack_from('<I', d, 0x3C)[0]; opt = e + 24
nsec = struct.unpack_from('<H', d, e + 6)[0]; osz = struct.unpack_from('<H', d, e + 20)[0]
secs = []
for i in range(nsec):
    o = opt + osz + 40 * i
    vs, rva, rs, rp = struct.unpack_from('<IIII', d, o + 8)
    secs.append((rva, max(vs, rs), rp))
def off(rva):
    for r, s, p in secs:
        if r <= rva < r + s: return rva - r + p
def cstr(o):
    return d[o:d.index(b'\0', o)].decode('latin1')
rva, size = struct.unpack_from('<II', d, opt + 104)
o = off(rva)
while o is not None:
    ilt, _, _, name, iat = struct.unpack_from('<IIIII', d, o)
    if not name: break
    names = []
    p = off(ilt or iat)
    while True:
        v = struct.unpack_from('<I', d, p)[0]
        if not v: break
        names.append('#%d' % (v & 0xFFFF) if v & 0x80000000 else cstr(off(v) + 2))
        p += 4
    print(cstr(off(name)), ':', ' '.join(names))
    o += 20
