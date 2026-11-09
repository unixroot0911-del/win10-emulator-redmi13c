#!/usr/bin/env python3
"""Generates tiny 32-bit PE test programs (hello.exe, calc.exe) from raw machine code. No external tools."""
import struct, sys, os

BASE = 0x400000
RVA = 0x1000


def build_pe(imports, data, make_code):
    """imports: {dll: [func,...]}; data: bytes; make_code(iat: {func: va}, data_va: int) -> bytes"""
    # import block layout
    off = 0
    ndll = len(imports)
    desc_off = 0
    off = 20 * (ndll + 1)
    layout = {}
    for dll, funcs in imports.items():
        ilt = off; off += 4 * (len(funcs) + 1)
        iat = off; off += 4 * (len(funcs) + 1)
        names = {}
        for f in funcs:
            names[f] = off; off += 2 + len(f) + 1
            if off % 2: off += 1
        dname = off; off += len(dll) + 1
        layout[dll] = (ilt, iat, names, dname)
    imp = bytearray(off)
    iat_va = {}
    for i, (dll, funcs) in enumerate(imports.items()):
        ilt, iat, names, dname = layout[dll]
        struct.pack_into('<IIIII', imp, 20 * i, RVA + ilt, 0, 0, RVA + dname, RVA + iat)
        for j, f in enumerate(funcs):
            struct.pack_into('<I', imp, ilt + 4 * j, RVA + names[f])
            struct.pack_into('<I', imp, iat + 4 * j, RVA + names[f])
            imp[names[f] + 2: names[f] + 2 + len(f)] = f.encode()
            iat_va[f] = BASE + RVA + iat + 4 * j
        imp[dname:dname + len(dll)] = dll.encode()
    imp_size = len(imp)
    data_off = (imp_size + 15) & ~15
    data_va = BASE + RVA + data_off
    code_off = (data_off + len(data) + 15) & ~15
    code = make_code(iat_va, data_va)
    sect = bytearray(code_off + len(code))
    sect[:imp_size] = imp
    sect[data_off:data_off + len(data)] = data
    sect[code_off:] = code
    entry = RVA + code_off
    raw = (len(sect) + 0x1FF) & ~0x1FF
    sect += bytes(raw - len(sect))
    vsize = (len(sect) + 0xFFF) & ~0xFFF
    hdr = bytearray(0x200)
    hdr[0:2] = b'MZ'; struct.pack_into('<I', hdr, 0x3C, 0x80)
    hdr[0x80:0x84] = b'PE\0\0'
    struct.pack_into('<HHIIIHH', hdr, 0x84, 0x14C, 1, 0, 0, 0, 0xE0, 0x103)
    o = 0x98
    struct.pack_into('<HBBIIIII', hdr, o, 0x10B, 6, 0, raw, 0, 0, entry, RVA)
    struct.pack_into('<I', hdr, o + 24, RVA)  # BaseOfData
    struct.pack_into('<IIIHHHHHHIIIIHHIIIIII', hdr, o + 28, BASE, 0x1000, 0x200, 4, 0, 0, 0, 4, 0, 0,
                     RVA + vsize, 0x200, 0, 3, 0, 0x100000, 0x1000, 0x100000, 0x1000, 0, 16)
    # data directories start at o+96; import dir index 1
    struct.pack_into('<II', hdr, o + 96 + 8, RVA, imp_size)
    s = o + 0xE0
    hdr[s:s + 8] = b'.text\0\0\0'
    struct.pack_into('<IIIIIIHHI', hdr, s + 8, len(sect), RVA, raw, 0x200, 0, 0, 0, 0, 0xE0000060)
    return bytes(hdr) + bytes(sect)


def call_iat(va):
    return b'\xFF\x15' + struct.pack('<I', va)


def push_imm(v):
    return b'\x68' + struct.pack('<I', v & 0xFFFFFFFF)


def hello():
    msg = b'Hello from my own x86 emulator on Android!\r\n'
    imports = {'kernel32.dll': ['GetStdHandle', 'WriteFile', 'ExitProcess']}

    def code(iat, dva):
        c = b'\x6A\xF5'  # push -11
        c = b'\x6A\xF5'
        c += call_iat(iat['GetStdHandle'])
        c += b'\x6A\x00' + push_imm(dva + len(msg)) + push_imm(len(msg)) + push_imm(dva) + b'\x50'
        c += call_iat(iat['WriteFile'])
        c += b'\x6A\x00' + call_iat(iat['ExitProcess'])
        return c
    return build_pe(imports, msg + b'\0\0\0\0', code)


def calc():
    fmt = b'%d! = %d\n\0'
    fmt2 = b'100/7 = %d remainder %d\n\0'
    done = b'done (printed by msvcrt puts)\0'
    fmt3 = b'shifts: %x %x %d\n\0'
    data = fmt + fmt2 + done + fmt3
    imports = {'msvcrt.dll': ['printf', 'puts', 'exit']}

    def code(iat, dva):
        p_fmt, p_fmt2 = dva, dva + len(fmt)
        p_done = p_fmt2 + len(fmt2)
        p_fmt3 = p_done + len(done)
        c = b'\xBE\x01\x00\x00\x00'  # mov esi,1
        c += b'\xBF\x01\x00\x00\x00'  # mov edi,1
        loop = len(c)
        c += b'\x0F\xAF\xFE'  # imul edi,esi
        c += b'\x57\x56' + push_imm(p_fmt) + call_iat(iat['printf']) + b'\x83\xC4\x0C'
        c += b'\x46'  # inc esi
        c += b'\x83\xFE\x0A'  # cmp esi,10
        rel = loop - (len(c) + 2)
        c += b'\x7E' + struct.pack('<b', rel)  # jle loop
        c += b'\xB8\x64\x00\x00\x00\x99\xB9\x07\x00\x00\x00\xF7\xF9'  # mov eax,100; cdq; mov ecx,7; idiv ecx
        c += b'\x52\x50' + push_imm(p_fmt2) + call_iat(iat['printf']) + b'\x83\xC4\x0C'
        # shifts: mov eax,0x80; shl eax,4 ; mov ebx,eax; shr ebx,2 ; mov ecx,-64 ; sar ecx,3 -> push ecx,ebx,eax
        c += b'\xB8\x80\x00\x00\x00\xC1\xE0\x04'  # eax = 0x800
        c += b'\x89\xC3\xC1\xEB\x02'  # ebx = eax >> 2 = 0x200
        c += b'\xB9\xC0\xFF\xFF\xFF\xC1\xF9\x03'  # ecx = -64 >> 3 = -8
        c += b'\x51\x53\x50' + push_imm(p_fmt3) + call_iat(iat['printf']) + b'\x83\xC4\x10'
        c += push_imm(p_done) + call_iat(iat['puts']) + b'\x83\xC4\x04'
        c += b'\x6A\x00' + call_iat(iat['exit'])
        return c
    return build_pe(imports, data, code)


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(out, exist_ok=True)
    open(os.path.join(out, 'hello.exe'), 'wb').write(hello())
    open(os.path.join(out, 'calc.exe'), 'wb').write(calc())
    print('wrote', out)
