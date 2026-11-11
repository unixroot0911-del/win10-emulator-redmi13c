#!/usr/bin/env python3
"""Minimal .NET assembly writer used to produce test programs for the CLR interpreter (no C# compiler available)."""
import struct, sys

class Heap:
    def __init__(self, first=b'\0'):
        self.b = bytearray(first); self.m = {}
    def add(self, data):
        if data in self.m: return self.m[data]
        o = len(self.b); self.b += data; self.m[data] = o; return o

def cu(n):
    if n < 0x80: return bytes([n])
    if n < 0x4000: return bytes([0x80 | n >> 8, n & 255])
    return bytes([0xC0 | n >> 24, n >> 16 & 255, n >> 8 & 255, n & 255])

class Asm:
    def __init__(self, name='Test'):
        self.name = name
        self.strs = Heap(); self.us = Heap(); self.blob = Heap(); self.guid = bytearray(16)
        self.typerefs = []; self.typedefs = []; self.fields = []; self.methods = []; self.memberrefs = []; self.sigs = []; self.asmrefs = []
        self.bodies = []
    def S(self, s): return self.strs.add(s.encode() + b'\0') if s else 0
    def B(self, b): return self.blob.add(cu(len(b)) + bytes(b)) if b else 0
    def US(self, s):
        d = s.encode('utf-16le') + b'\0'
        return self.us.add(cu(len(d)) + d)
    def asmref(self, name):
        self.asmrefs.append(name); return len(self.asmrefs)
    def typeref(self, scope, ns, name):
        self.typerefs.append((scope, ns, name)); return 0x01000000 | len(self.typerefs)
    def memberref(self, parent_tok, name, sig):
        self.memberrefs.append((parent_tok, name, bytes(sig))); return 0x0A000000 | len(self.memberrefs)
    def typedef(self, flags, ns, name, base_tok):
        self.typedefs.append(dict(flags=flags, ns=ns, name=name, base=base_tok, fields=[], methods=[]))
        return 0x02000000 | len(self.typedefs)
    def field(self, td_tok, flags, name, sig):
        self.fields.append((flags, name, bytes(sig))); idx = len(self.fields)
        self.typedefs[(td_tok & 0xFFFFFF) - 1]['fields'].append(idx); return 0x04000000 | idx
    def method(self, td_tok, flags, name, sig, il=None):
        self.methods.append(dict(flags=flags, name=name, sig=bytes(sig), il=il)); idx = len(self.methods)
        self.typedefs[(td_tok & 0xFFFFFF) - 1]['methods'].append(idx); return 0x06000000 | idx
    def localsig(self, types):
        self.sigs.append(bytes([0x07]) + cu(len(types)) + bytes(sum(types, []) if types and isinstance(types[0], list) else types))
        return 0x11000000 | len(self.sigs)

def coded(tag_bits, tag, row): return row << tag_bits | tag

class IL:
    def __init__(self): self.c = bytearray(); self.labels = {}; self.fix = []
    def op(self, *b): self.c += bytes(b); return self
    def tok(self, op, t): self.c += bytes([op]) + struct.pack('<I', t); return self
    def i4(self, n):
        if -1 <= n <= 8: self.c.append(0x15 + (n + 1) if n >= 0 else 0x15)
        else: self.c += b'\x20' + struct.pack('<i', n)
        return self
    def label(self, n): self.labels[n] = len(self.c)
    def br(self, op, n): self.c.append(op); self.fix.append((len(self.c), n)); self.c += b'\0\0\0\0'; return self
    def pos(self): return len(self.c)
    def done(self):
        for at, n in self.fix: struct.pack_into('<i', self.c, at, self.labels[n] - (at + 4))
        return bytes(self.c)

def body(il_bytes, maxstack, locals_tok=0, clauses=()):
    more = 8 if clauses else 0
    h = struct.pack('<HHII', 0x3003 | 0x10 | more, maxstack, len(il_bytes), locals_tok)
    out = bytearray(h + il_bytes)
    if clauses:
        while len(out) % 4: out.append(0)
        n = len(clauses)
        out += bytes([0x41]) + struct.pack('<I', 4 + 24 * n)[:3]
        for fl, ts, tl, hs, hl, tok in clauses: out += struct.pack('<IIIIII', fl, ts, tl, hs, hl, tok)
    return bytes(out)

def build(a):
    # --- body placement
    text_rva = 0x2000
    cli_size = 72
    code = bytearray()
    body_off = []
    base = cli_size
    for m in a.methods:
        while (base + len(code)) % 4: code.append(0)
        body_off.append(base + len(code)); code += m['il'] if m['il'] else b''
    # metadata tables
    S, B = a.S, a.B
    rows = {}
    rows[0x00] = [struct.pack('<HHHHH', 0, S(a.name + '.exe'), 1, 0, 0)]
    rows[0x01] = [struct.pack('<HHH', s, S(n), S(ns)) for s, ns, n in a.typerefs]
    def tdr(tok):
        if tok == 0: return 0
        tab, row = tok >> 24, tok & 0xFFFFFF
        return coded(2, {0x02: 0, 0x01: 1, 0x1B: 2}[tab], row)
    # fix first field/method indexes for types without members: spec requires next type's start; compute run-lengths properly
    nf = 1; nm = 1; td = []
    for t in a.typedefs:
        td.append(struct.pack('<IHHHHH', t['flags'], S(t['name']), S(t['ns']), tdr(t['base']), nf, nm))
        nf += len(t['fields']); nm += len(t['methods'])
    rows[0x02] = td
    rows[0x04] = [struct.pack('<HHH', fl, S(n), B(sg)) for fl, n, sg in a.fields]
    rows[0x06] = [struct.pack('<IHHHHH', text_rva + body_off[i] if a.methods[i]['il'] else 0, 0, m['flags'], S(m['name']), B(m['sig']), 1) for i, m in enumerate(a.methods)]
    def mrp(tok):
        tab, row = tok >> 24, tok & 0xFFFFFF
        return coded(3, {0x02: 0, 0x01: 1, 0x06: 3, 0x1B: 4}[tab], row)
    rows[0x0A] = [struct.pack('<HHH', mrp(p), S(n), B(sg)) for p, n, sg in a.memberrefs]
    rows[0x11] = [struct.pack('<H', B(sg)) for sg in a.sigs]
    rows[0x20] = [struct.pack('<IHHHHIHHH', 0x8004, 1, 0, 0, 0, 0, 0, S(a.name), 0)]
    rows[0x23] = [struct.pack('<HHHHIHHHH', 4, 0, 0, 0, 0, 0, S(n), 0, 0) for n in a.asmrefs]
    valid = 0
    tbl = bytearray()
    for t in sorted(rows):
        if rows[t]: valid |= 1 << t
    tdata = bytearray()
    for t in sorted(rows):
        for r in rows[t]: tdata += r
    counts = b''.join(struct.pack('<I', len(rows[t])) for t in sorted(rows) if rows[t])
    tstream = struct.pack('<IBBBBQQ', 0, 2, 0, 0, 1, valid, 0) + counts + tdata
    while len(tstream) % 4: tstream += b'\0'
    def pad4(b):
        b = bytes(b)
        while len(b) % 4: b += b'\0'
        return b
    streams = [('#~', tstream), ('#Strings', pad4(a.strs.b)), ('#US', pad4(a.us.b)), ('#GUID', bytes(a.guid)), ('#Blob', pad4(a.blob.b))]
    ver = b'v4.0.30319\0\0'
    hdr = struct.pack('<IHHII', 0x424A5342, 1, 1, 0, len(ver)) + ver + struct.pack('<HH', 0, len(streams))
    sh_len = sum(8 + ((len(n) + 1 + 3) & ~3) for n, _ in streams)
    off = len(hdr) + sh_len
    sh = b''
    for n, d in streams:
        nb = n.encode() + b'\0'
        while len(nb) % 4: nb += b'\0'
        sh += struct.pack('<II', off, len(d)) + nb; off += len(d)
    md = hdr + sh + b''.join(d for _, d in streams)
    md_off = cli_size + len(code)
    while md_off % 4: md_off += 1
    pad = md_off - cli_size - len(code)
    text = bytearray()
    entry = 0x06000000 | next(i + 1 for i, m in enumerate(a.methods) if m['name'] == 'Main')
    cli = struct.pack('<IHHIIIIIII', 72, 2, 5, text_rva + md_off, len(md), 1, entry, 0, 0, 0)
    cli += b'\0' * (72 - len(cli))
    text = cli + code + b'\0' * pad + md
    # --- PE
    raw = (len(text) + 0x1FF) & ~0x1FF
    dos = bytearray(0x80); dos[0:2] = b'MZ'; struct.pack_into('<I', dos, 0x3C, 0x80)
    coff = b'PE\0\0' + struct.pack('<HHIIIHH', 0x14C, 1, 0, 0, 0, 224, 0x102 | 0x2000)
    opt = struct.pack('<HBBIIIIIIIIIHHHHHHIIIIHHIIIIII', 0x10B, 8, 0, raw, 0, 0, 0, text_rva, 0, 0x400000, 0x2000, 0x200, 4, 0, 0, 0, 4, 0, 0, text_rva + len(text), 0x200, 0, 3, 0, 0x100000, 0x1000, 0x100000, 0x1000, 0, 16)
    dd = bytearray(16 * 8); struct.pack_into('<II', dd, 14 * 8, text_rva, 72)
    sec = b'.text\0\0\0' + struct.pack('<IIIIIIHHI', len(text), text_rva, raw, 0x200, 0, 0, 0, 0, 0x60000020)
    hdrs = bytes(dos) + coff + opt + bytes(dd) + sec
    hdrs += b'\0' * (0x200 - len(hdrs))
    return hdrs + text + b'\0' * (raw - len(text))

def demo():
    a = Asm('Demo')
    mscorlib = a.asmref('mscorlib')
    tObject = a.typeref(mscorlib << 2 | 2, 'System', 'Object')
    tConsole = a.typeref(mscorlib << 2 | 2, 'System', 'Console')
    tException = a.typeref(mscorlib << 2 | 2, 'System', 'Exception')
    tString = a.typeref(mscorlib << 2 | 2, 'System', 'String')
    I4, STR, OBJ, VOID = 0x08, 0x0E, 0x1C, 0x01
    wlS = a.memberref(tConsole, 'WriteLine', [0x00, 1, VOID, STR])
    wlI = a.memberref(tConsole, 'WriteLine', [0x00, 1, VOID, I4])
    objCtor = a.memberref(tObject, '.ctor', [0x20, 0, VOID])
    excCtor = a.memberref(tException, '.ctor', [0x20, 1, VOID, STR])
    getMsg = a.memberref(tException, 'get_Message', [0x20, 0, STR])
    concat = a.memberref(tString, 'Concat', [0x00, 2, STR, STR, STR])
    tModule = a.typedef(0, '', '<Module>', 0)
    tAnimal = a.typedef(0x100001, '', 'Animal', tObject)
    tDog = a.typedef(0x100001, '', 'Dog', 0)
    tProg = a.typedef(0x100000, '', 'Program', tObject)
    a.typedefs[2]['base'] = tAnimal
    legs = a.field(tAnimal, 6, 'legs', [0x06, I4])
    # Animal::.ctor(int)
    il = IL(); il.op(0x02).tok(0x28, objCtor).op(0x02, 0x03).tok(0x7D, legs).op(0x2A)
    aCtor = a.method(tAnimal, 0x1806, '.ctor', [0x20, 1, VOID, I4], body(il.done(), 8))
    il = IL(); il.tok(0x72, 0x70000000 | a.US('animal')).op(0x2A)
    aName = a.method(tAnimal, 0x1C6, 'Name', [0x20, 0, STR], body(il.done(), 8))
    il = IL(); il.op(0x02).i4(4).tok(0x28, aCtor).op(0x2A)
    dCtor = a.method(tDog, 0x1806, '.ctor', [0x20, 0, VOID], body(il.done(), 8))
    il = IL(); il.tok(0x72, 0x70000000 | a.US('dog')).op(0x2A)
    dName = a.method(tDog, 0x1C6, 'Name', [0x20, 0, STR], body(il.done(), 8))
    # Fib (own token = 5 given method order)
    fibTok = 0x06000005
    il = IL()
    il.op(0x02).i4(2).br(0x3C, 'rec').op(0x02, 0x2A)
    il.label('rec')
    il.op(0x02).i4(1).op(0x59).tok(0x28, fibTok)
    il.op(0x02).i4(2).op(0x59).tok(0x28, fibTok)
    il.op(0x58, 0x2A)
    fib = a.method(tProg, 0x96, 'Fib', [0x00, 1, I4, I4], body(il.done(), 8))
    assert fib == fibTok, hex(fib)
    # Main
    sig = bytes([0x07, 4, I4, I4, 0x12, ((tAnimal & 0xFFFFFF) << 2) | 0, 0x12, ((tException & 0xFFFFFF) << 2) | 1])
    a.sigs.append(sig); locTok = 0x11000000 | len(a.sigs)
    il = IL()
    il.tok(0x72, 0x70000000 | a.US('Hello from .NET')).tok(0x28, wlS)
    il.i4(0).op(0x0A).i4(1).op(0x0B)
    il.label('loop'); il.op(0x07).i4(100).br(0x3D, 'end')  # ldloc.1 ; ldc 100; bgt end  (0x3D = bgt long: 0x3B+2)
    il.op(0x06).op(0x07).op(0x58).op(0x0A).op(0x07).i4(1).op(0x58).op(0x0B).br(0x38, 'loop')
    il.label('end'); il.op(0x06).tok(0x28, wlI)
    il.i4(20).tok(0x28, fibTok).tok(0x28, wlI)
    il.tok(0x73, dCtor).op(0x0C).op(0x08).tok(0x6F, aName).tok(0x28, wlS)
    il.op(0x08).tok(0x7B, legs).tok(0x28, wlI)
    t0 = il.pos()
    il.tok(0x72, 0x70000000 | a.US('boom')).tok(0x73, excCtor).op(0x7A)
    c0 = il.pos()
    il.op(0x0D).op(0x09).tok(0x6F, getMsg).tok(0x28, wlS).br(0xDD, 'after')
    c1 = il.pos()
    f0 = il.pos()
    il.tok(0x72, 0x70000000 | a.US('fin')).tok(0x28, wlS).op(0xDC)
    f1 = il.pos()
    il.label('after')
    il.tok(0x72, 0x70000000 | a.US('a')).tok(0x72, 0x70000000 | a.US('b')).tok(0x28, concat).tok(0x28, wlS)
    il.op(0x2A)
    clauses = [(0, t0, c0 - t0, c0, c1 - c0, tException), (2, t0, c1 - t0, f0, f1 - f0, 0)]
    a.method(tProg, 0x96, 'Main', [0x00, 0, VOID], body(il.done(), 8, locTok, clauses))
    return a


def winforms_demo():
    a = Asm('WfDemo')
    mscorlib = a.asmref('mscorlib'); swf = a.asmref('System.Windows.Forms'); sd = a.asmref('System.Drawing')
    R = lambda asm, ns, n: a.typeref(asm << 2 | 2, ns, n)
    tObject = R(mscorlib, 'System', 'Object'); tString = R(mscorlib, 'System', 'String'); tEH = R(mscorlib, 'System', 'EventHandler')
    tForm = R(swf, 'System.Windows.Forms', 'Form'); tButton = R(swf, 'System.Windows.Forms', 'Button'); tLabel = R(swf, 'System.Windows.Forms', 'Label')
    tApp = R(swf, 'System.Windows.Forms', 'Application'); tCtl = R(swf, 'System.Windows.Forms', 'Control'); tColl = R(swf, 'System.Windows.Forms', 'Control/ControlCollection')
    tPoint = R(sd, 'System.Drawing', 'Point'); tSize = R(sd, 'System.Drawing', 'Size')
    I4, STR, OBJ, VOID, IPTR = 0x08, 0x0E, 0x1C, 0x01, 0x18
    def cls(tok): return [0x12, ((tok & 0xFFFFFF) << 2) | 1]
    def vt(tok): return [0x11, ((tok & 0xFFFFFF) << 2) | 1]
    formCtor = a.memberref(tForm, '.ctor', [0x20, 0, VOID]); btnCtor = a.memberref(tButton, '.ctor', [0x20, 0, VOID]); lblCtor = a.memberref(tLabel, '.ctor', [0x20, 0, VOID])
    setText = a.memberref(tCtl, 'set_Text', [0x20, 1, VOID, STR]); getText = a.memberref(tCtl, 'get_Text', [0x20, 0, STR])
    getControls = a.memberref(tCtl, 'get_Controls', bytes([0x20, 0]) + bytes(cls(tColl)))
    cadd = a.memberref(tColl, 'Add', bytes([0x20, 1, VOID]) + bytes(cls(tCtl)))
    setLoc = a.memberref(tCtl, 'set_Location', bytes([0x20, 1, VOID]) + bytes(vt(tPoint)))
    setSize = a.memberref(tCtl, 'set_Size', bytes([0x20, 1, VOID]) + bytes(vt(tSize)))
    pointCtor = a.memberref(tPoint, '.ctor', [0x20, 2, VOID, I4, I4]); sizeCtor = a.memberref(tSize, '.ctor', [0x20, 2, VOID, I4, I4])
    addClick = a.memberref(tCtl, 'add_Click', bytes([0x20, 1, VOID]) + bytes(cls(tEH)))
    ehCtor = a.memberref(tEH, '.ctor', [0x20, 2, VOID, OBJ, IPTR])
    appRun = a.memberref(tApp, 'Run', bytes([0x00, 1, VOID]) + bytes(cls(tForm)))
    concat = a.memberref(tString, 'Concat', [0x00, 2, STR, STR, STR])
    objCtor = a.memberref(tObject, '.ctor', [0x20, 0, VOID])
    itoa = a.memberref(R(mscorlib, 'System', 'Convert'), 'ToString', [0x00, 1, STR, I4])
    tModule = a.typedef(0, '', '<Module>', 0)
    tMain = a.typedef(0x100001, '', 'MainForm', tForm)
    tProg = a.typedef(0x100000, '', 'Program', tObject)
    fCount = a.field(tMain, 6, 'count', [0x06, I4]); fLabel = a.field(tMain, 6, 'label', bytes([0x06]) + bytes(cls(tLabel)))
    # ctor
    il = IL()
    il.op(0x02).tok(0x28, formCtor)
    il.op(0x02).tok(0x72, 0x70000000 | a.US('Demo .NET')).tok(0x6F, setText)
    il.op(0x02).i4(240).i4(120).tok(0x73, sizeCtor).tok(0x6F, a.memberref(R(swf, 'System.Windows.Forms', 'Form'), 'set_ClientSize', bytes([0x20, 1, VOID]) + bytes(vt(tSize))))
    il.tok(0x73, lblCtor).op(0x0A)  # stloc.0 (label) -- locals: label, button
    il.op(0x06).i4(10).i4(10).tok(0x73, pointCtor).tok(0x6F, setLoc)
    il.op(0x06).tok(0x72, 0x70000000 | a.US('Clicks: 0')).tok(0x6F, setText)
    il.op(0x02).op(0x06).tok(0x7D, fLabel)
    il.tok(0x73, btnCtor).op(0x0B)
    il.op(0x07).i4(10).i4(50).tok(0x73, pointCtor).tok(0x6F, setLoc)
    il.op(0x07).tok(0x72, 0x70000000 | a.US('Click me')).tok(0x6F, setText)
    clickTok = 0x06000002
    il.op(0x07).op(0x02); il.c += bytes([0xFE, 0x06]) + struct.pack('<I', clickTok); il.tok(0x73, ehCtor).tok(0x6F, addClick)
    il.op(0x02).tok(0x28, getControls).op(0x06).tok(0x6F, cadd)
    il.op(0x02).tok(0x28, getControls).op(0x07).tok(0x6F, cadd)
    il.op(0x2A)
    loc = bytes([0x07, 2]) + bytes(cls(tLabel)) + bytes(cls(tButton))
    a.sigs.append(loc); locTok = 0x11000000 | len(a.sigs)
    mCtor = a.method(tMain, 0x1806, '.ctor', [0x20, 0, VOID], body(il.done(), 8, locTok))
    # OnClick(object, EventArgs)
    il = IL()
    il.op(0x02).op(0x02).tok(0x7B, fCount).i4(1).op(0x58).tok(0x7D, fCount)
    il.op(0x02).tok(0x7B, fLabel).tok(0x72, 0x70000000 | a.US('Clicks: '))
    il.op(0x02).tok(0x7B, fCount).tok(0x28, itoa).tok(0x28, concat).tok(0x6F, setText).op(0x2A)
    mClick = a.method(tMain, 0x86, 'OnClick', bytes([0x20, 2, VOID, OBJ]) + bytes(cls(R(mscorlib, 'System', 'EventArgs'))), body(il.done(), 8))
    assert mClick == clickTok, hex(mClick)
    il = IL(); il.tok(0x73, mCtor).tok(0x28, appRun).op(0x2A)
    a.method(tProg, 0x96, 'Main', [0x00, 0, VOID], body(il.done(), 8))
    return a

def wpf_demo():
    a = Asm('WpfDemo')
    mscorlib = a.asmref('mscorlib'); pf = a.asmref('PresentationFramework'); pc = a.asmref('PresentationCore'); wb = a.asmref('WindowsBase')
    R = lambda asm, ns, n: a.typeref(asm << 2 | 2, ns, n)
    tObject = R(mscorlib, 'System', 'Object')
    tWin = R(pf, 'System.Windows', 'Window'); tApp = R(pf, 'System.Windows', 'Application'); tRD = R(pf, 'System.Windows', 'ResourceDictionary')
    tBtn = R(pf, 'System.Windows.Controls', 'Button'); tCC = R(pf, 'System.Windows.Controls', 'ContentControl')
    tSCB = R(pc, 'System.Windows.Media', 'SolidColorBrush'); tColor = R(pc, 'System.Windows.Media', 'Color'); tFrz = R(wb, 'System.Windows', 'Freezable')
    tIList = R(mscorlib, 'System.Collections', 'IList')
    I4, STR, OBJ, VOID = 0x08, 0x0E, 0x1C, 0x01
    def cls(tok): return [0x12, ((tok & 0xFFFFFF) << 2) | 1]
    def vt(tok): return [0x11, ((tok & 0xFFFFFF) << 2) | 1]
    appCtor = a.memberref(tApp, '.ctor', [0x20, 0, VOID]); winCtor = a.memberref(tWin, '.ctor', [0x20, 0, VOID]); btnCtor = a.memberref(tBtn, '.ctor', [0x20, 0, VOID]); rdCtor = a.memberref(tRD, '.ctor', [0x20, 0, VOID])
    getCur = a.memberref(tApp, 'get_Current', bytes([0x00, 0]) + bytes(cls(tApp)))
    getRes = a.memberref(tApp, 'get_Resources', bytes([0x20, 0]) + bytes(cls(tRD)))
    setItem = a.memberref(tRD, 'set_Item', [0x20, 2, VOID, OBJ, OBJ]); getItem = a.memberref(tRD, 'get_Item', [0x20, 1, OBJ, OBJ])
    getMerged = a.memberref(tRD, 'get_MergedDictionaries', [0x20, 0, OBJ]); ilAdd = a.memberref(tIList, 'Add', [0x20, 1, I4, OBJ])
    fromRgb = a.memberref(tColor, 'FromRgb', bytes([0x00, 3]) + bytes(vt(tColor)) + bytes([0x05, 0x05, 0x05]))
    scbCtor = a.memberref(tSCB, '.ctor', bytes([0x20, 1, VOID]) + bytes(vt(tColor)))
    freeze = a.memberref(tFrz, 'Freeze', [0x20, 0, VOID])
    setTitle = a.memberref(tWin, 'set_Title', [0x20, 1, VOID, STR]); setContent = a.memberref(tCC, 'set_Content', [0x20, 1, VOID, OBJ])
    run = a.memberref(tApp, 'Run', bytes([0x20, 1, I4]) + bytes(cls(tWin)))
    tModule = a.typedef(0, '', '<Module>', 0); tProg = a.typedef(0x100000, '', 'Program', tObject)
    US = lambda t: 0x70000000 | a.US(t)
    il = IL()
    il.tok(0x73, appCtor).op(0x0A)
    il.tok(0x28, getCur).tok(0x6F, getRes).op(0x0B)
    il.op(0x07).tok(0x72, US('s')).tok(0x72, US('FromResource')).tok(0x6F, setItem)
    il.i4(200).i4(100).i4(50).tok(0x28, fromRgb).tok(0x73, scbCtor).op(0x0C)
    il.op(0x08).tok(0x6F, freeze)
    il.op(0x07).tok(0x72, US('b')).op(0x08).tok(0x6F, setItem)
    il.op(0x07).tok(0x6F, getMerged).tok(0x73, rdCtor).tok(0x6F, ilAdd).op(0x26)
    il.tok(0x73, winCtor).op(0x0D)
    il.op(0x09).tok(0x72, US('Wpf IL demo')).tok(0x6F, setTitle)
    il.tok(0x73, btnCtor).op(0x13).op(4)
    il.op(0x11).op(4).op(0x07).tok(0x72, US('s')).tok(0x6F, getItem).tok(0x6F, setContent)
    il.op(0x09).op(0x11).op(4).tok(0x6F, setContent)
    il.op(0x06).op(0x09).tok(0x6F, run).op(0x26).op(0x2A)
    loc = bytes([0x07, 5]) + bytes(cls(tApp)) + bytes(cls(tRD)) + bytes(cls(tSCB)) + bytes(cls(tWin)) + bytes(cls(tBtn))
    a.sigs.append(loc); locTok = 0x11000000 | len(a.sigs)
    a.method(tProg, 0x96, 'Main', [0x00, 0, VOID], body(il.done(), 8, locTok))
    return a

if __name__ == '__main__':
    open(sys.argv[1], 'wb').write(build(wpf_demo() if len(sys.argv) > 2 and sys.argv[2] == 'wpf' else winforms_demo() if len(sys.argv) > 2 and sys.argv[2] == 'wf' else demo()))
