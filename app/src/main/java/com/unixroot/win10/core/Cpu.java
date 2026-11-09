package com.unixroot.win10.core;

/** x86-32 integer interpreter written from scratch. Covers the common user-mode integer ISA. */
public final class Cpu {
    public static final int EAX = 0, ECX = 1, EDX = 2, EBX = 3, ESP = 4, EBP = 5, ESI = 6, EDI = 7;

    public interface Hook { void onStub(Cpu cpu, int addr); }

    public final Memory mem;
    public final int[] r = new int[8];
    public int eip;
    public boolean cf, pf, af, zf, sf, of, df;
    public int fsBase;
    public int stubLo = 0x7F000000, stubHi = 0x7F100000;
    public Hook hook;
    public volatile boolean halted;
    public long steps;

    private int opsz, seg, rep;
    private int mMod, mReg, mRm, ea;

    public Cpu(Memory mem) { this.mem = mem; }

    // ---------- fetch / stack ----------
    private int fetch8() { return mem.r8(eip++); }
    private int fetch16() { int v = mem.r16(eip); eip += 2; return v; }
    private int fetch32() { int v = mem.r32(eip); eip += 4; return v; }
    public void push(int v) { r[ESP] -= 4; mem.w32(r[ESP], v); }
    public int pop() { int v = mem.r32(r[ESP]); r[ESP] += 4; return v; }
    /** i-th stdcall/cdecl argument while stopped at a stub (return address at [esp]). */
    public int arg(int i) { return mem.r32(r[ESP] + 4 + 4 * i); }

    // ---------- register helpers ----------
    private static int mask(int sz) { return sz == 1 ? 0xFF : sz == 2 ? 0xFFFF : -1; }
    private static int sign(int sz) { return sz == 1 ? 0x80 : sz == 2 ? 0x8000 : 0x80000000; }
    private static int sx(int v, int sz) { return sz == 1 ? (byte) v : sz == 2 ? (short) v : v; }

    private int getReg(int i, int sz) {
        if (sz == 4) return r[i];
        if (sz == 2) return r[i] & 0xFFFF;
        return i < 4 ? r[i] & 0xFF : (r[i - 4] >> 8) & 0xFF;
    }

    private void setReg(int i, int sz, int v) {
        if (sz == 4) r[i] = v;
        else if (sz == 2) r[i] = (r[i] & 0xFFFF0000) | (v & 0xFFFF);
        else if (i < 4) r[i] = (r[i] & ~0xFF) | (v & 0xFF);
        else r[i - 4] = (r[i - 4] & ~0xFF00) | ((v & 0xFF) << 8);
    }

    private void modrm() {
        int m = fetch8();
        mMod = m >> 6; mReg = (m >> 3) & 7; mRm = m & 7;
        if (mMod == 3) return;
        int a;
        if (mRm == 4) {
            int sib = fetch8();
            int idx = (sib >> 3) & 7, base = sib & 7;
            a = idx == 4 ? 0 : r[idx] << (sib >> 6);
            if (base == 5 && mMod == 0) a += fetch32(); else a += r[base];
        } else if (mRm == 5 && mMod == 0) a = fetch32();
        else a = r[mRm];
        if (mMod == 1) a += (byte) fetch8(); else if (mMod == 2) a += fetch32();
        if (seg == 1) a += fsBase;
        ea = a;
    }

    private int getRM(int sz) {
        if (mMod == 3) return getReg(mRm, sz);
        return sz == 1 ? mem.r8(ea) : sz == 2 ? mem.r16(ea) : mem.r32(ea);
    }

    private void setRM(int sz, int v) {
        if (mMod == 3) { setReg(mRm, sz, v); return; }
        if (sz == 1) mem.w8(ea, v); else if (sz == 2) mem.w16(ea, v); else mem.w32(ea, v);
    }

    // ---------- flags / ALU ----------
    private void szp(int res, int sz) {
        res &= mask(sz);
        zf = res == 0; sf = (res & sign(sz)) != 0; pf = (Integer.bitCount(res & 0xFF) & 1) == 0;
    }

    /** op: 0 add,1 or,2 adc,3 sbb,4 and,5 sub,6 xor,7 cmp */
    private int alu(int op, int a, int b, int sz) {
        int m = mask(sz), sg = sign(sz);
        a &= m; b &= m;
        long ua = a & 0xFFFFFFFFL, ub = b & 0xFFFFFFFFL, mk = m & 0xFFFFFFFFL;
        int res;
        switch (op) {
            case 0: case 2: {
                long t = ua + ub + (op == 2 && cf ? 1 : 0);
                res = (int) (t & mk);
                cf = t > mk; of = ((a ^ res) & (b ^ res) & sg) != 0; af = ((a ^ b ^ res) & 0x10) != 0;
                break;
            }
            case 3: case 5: case 7: {
                long t = ua - ub - (op == 3 && cf ? 1 : 0);
                res = (int) (t & mk);
                cf = t < 0; of = ((a ^ b) & (a ^ res) & sg) != 0; af = ((a ^ b ^ res) & 0x10) != 0;
                break;
            }
            case 1: res = a | b; cf = of = af = false; break;
            case 4: res = a & b; cf = of = af = false; break;
            default: res = a ^ b; cf = of = af = false; break;
        }
        szp(res, sz);
        return res;
    }

    private int inc(int v, int sz) { boolean c = cf; int x = alu(0, v, 1, sz); cf = c; return x; }
    private int dec(int v, int sz) { boolean c = cf; int x = alu(5, v, 1, sz); cf = c; return x; }

    private boolean cc(int c) {
        boolean v;
        switch (c >> 1) {
            case 0: v = of; break;
            case 1: v = cf; break;
            case 2: v = zf; break;
            case 3: v = cf || zf; break;
            case 4: v = sf; break;
            case 5: v = pf; break;
            case 6: v = sf != of; break;
            default: v = zf || (sf != of); break;
        }
        return (c & 1) != 0 ? !v : v;
    }

    private int shift(int op, int v, int cnt, int sz) {
        cnt &= 31;
        if (cnt == 0) return v;
        int m = mask(sz), sg = sign(sz), bits = sz * 8, res;
        v &= m;
        switch (op) {
            case 0: { int c = cnt % bits; res = c == 0 ? v : ((v << c) | (v >>> (bits - c))) & m;
                cf = (res & 1) != 0; of = ((res & sg) != 0) != cf; return res; }
            case 1: { int c = cnt % bits; res = c == 0 ? v : ((v >>> c) | (v << (bits - c))) & m;
                cf = (res & sg) != 0; of = ((res & sg) != 0) != ((res & (sg >>> 1)) != 0); return res; }
            case 4: case 6:
                res = cnt >= 32 ? 0 : (v << cnt) & m;
                cf = cnt <= bits && ((v >>> (bits - cnt)) & 1) != 0;
                of = ((res & sg) != 0) != cf; szp(res, sz); return res;
            case 5:
                cf = cnt <= bits && ((v >>> (cnt - 1)) & 1) != 0;
                res = cnt >= bits ? 0 : v >>> cnt;
                of = (v & sg) != 0; szp(res, sz); return res;
            case 7: {
                int s = sx(v, sz), c = Math.min(cnt, 31);
                cf = ((s >> (c - 1)) & 1) != 0; res = (s >> c) & m; of = false; szp(res, sz); return res; }
            default: throw new EmuException("rcl/rcr not supported");
        }
    }

    // ---------- group 3 (mul/div) ----------
    private void group3(int sz) {
        int sub = mReg;
        int v;
        switch (sub) {
            case 0: case 1: { v = getRM(sz); int imm = sz == 1 ? fetch8() : sz == 2 ? fetch16() : fetch32();
                alu(4, v, imm, sz); return; }
            case 2: setRM(sz, ~getRM(sz)); return;
            case 3: { v = getRM(sz); int res = alu(5, 0, v, sz); cf = (v & mask(sz)) != 0; setRM(sz, res); return; }
            default: break;
        }
        v = getRM(sz);
        boolean signed = sub == 5 || sub == 7;
        if (sub == 4 || sub == 5) {
            if (sz == 4) {
                long t = signed ? (long) r[EAX] * (long) v : (r[EAX] & 0xFFFFFFFFL) * (v & 0xFFFFFFFFL);
                r[EAX] = (int) t; r[EDX] = (int) (t >> 32);
                cf = of = signed ? t != (int) t : r[EDX] != 0;
            } else if (sz == 2) {
                int a = r[EAX] & 0xFFFF, b = v & 0xFFFF;
                int t = signed ? (short) a * (short) b : a * b;
                setReg(EAX, 2, t); setReg(EDX, 2, t >>> 16);
                cf = of = signed ? t != (short) t : (t >>> 16) != 0;
            } else {
                int a = r[EAX] & 0xFF, b = v & 0xFF;
                int t = signed ? (byte) a * (byte) b : a * b;
                setReg(EAX, 2, t);
                cf = of = signed ? t != (byte) t : (t >>> 8) != 0;
            }
            return;
        }
        // div / idiv
        if ((v & mask(sz)) == 0) throw new EmuException("Division by zero");
        if (sz == 4) {
            if (signed) {
                long n = ((long) r[EDX] << 32) | (r[EAX] & 0xFFFFFFFFL), d = v;
                long q = n / d;
                if (q != (int) q) throw new EmuException("Divide overflow");
                r[EAX] = (int) q; r[EDX] = (int) (n % d);
            } else {
                long n = ((r[EDX] & 0xFFFFFFFFL) << 32) | (r[EAX] & 0xFFFFFFFFL), d = v & 0xFFFFFFFFL;
                if (Long.compareUnsigned(n >>> 32, d) >= 0) throw new EmuException("Divide overflow");
                long q = Long.divideUnsigned(n, d), rem = Long.remainderUnsigned(n, d);
                r[EAX] = (int) q; r[EDX] = (int) rem;
            }
        } else if (sz == 2) {
            int n = ((r[EDX] & 0xFFFF) << 16) | (r[EAX] & 0xFFFF);
            if (signed) {
                int d = (short) v, q = n / d;
                if (q != (short) q) throw new EmuException("Divide overflow");
                setReg(EAX, 2, q); setReg(EDX, 2, n % d);
            } else {
                long nn = n & 0xFFFFFFFFL, d = v & 0xFFFF, q = nn / d;
                if (q > 0xFFFF) throw new EmuException("Divide overflow");
                setReg(EAX, 2, (int) q); setReg(EDX, 2, (int) (nn % d));
            }
        } else {
            int n = r[EAX] & 0xFFFF;
            if (signed) {
                int nn = (short) n, d = (byte) v, q = nn / d;
                if (q != (byte) q) throw new EmuException("Divide overflow");
                setReg(0, 1, q); setReg(4, 1, nn % d);
            } else {
                int d = v & 0xFF, q = n / d;
                if (q > 0xFF) throw new EmuException("Divide overflow");
                setReg(0, 1, q); setReg(4, 1, n % d);
            }
        }
    }

    // ---------- string ops ----------
    private void strOnce(int op, int sz) {
        int step = df ? -sz : sz;
        switch (op) {
            case 0xA4: write(r[EDI], sz, read(r[ESI], sz)); r[ESI] += step; r[EDI] += step; break;
            case 0xA6: alu(7, read(r[ESI], sz), read(r[EDI], sz), sz); r[ESI] += step; r[EDI] += step; break;
            case 0xAA: write(r[EDI], sz, getReg(0, sz)); r[EDI] += step; break;
            case 0xAC: setReg(0, sz, read(r[ESI], sz)); r[ESI] += step; break;
            default: alu(7, getReg(0, sz), read(r[EDI], sz), sz); r[EDI] += step; break;
        }
    }

    private int read(int a, int sz) { return sz == 1 ? mem.r8(a) : sz == 2 ? mem.r16(a) : mem.r32(a); }
    private void write(int a, int sz, int v) { if (sz == 1) mem.w8(a, v); else if (sz == 2) mem.w16(a, v); else mem.w32(a, v); }

    private void strOp(int op, int sz) {
        if (rep == 0) { strOnce(op, sz); return; }
        boolean cmp = op == 0xA6 || op == 0xAE;
        while (r[ECX] != 0 && !halted) {
            strOnce(op, sz);
            r[ECX]--;
            if (cmp && ((rep == 1 && !zf) || (rep == 2 && zf))) break;
        }
    }

    private int flagsWord() {
        return (cf ? 1 : 0) | 2 | (pf ? 4 : 0) | (af ? 16 : 0) | (zf ? 64 : 0) | (sf ? 128 : 0) | 0x200 | (df ? 0x400 : 0) | (of ? 0x800 : 0);
    }

    private void setFlagsWord(int f) {
        cf = (f & 1) != 0; pf = (f & 4) != 0; af = (f & 16) != 0; zf = (f & 64) != 0;
        sf = (f & 128) != 0; df = (f & 0x400) != 0; of = (f & 0x800) != 0;
    }

    // ---------- main step ----------
    public void step() {
        steps++;
        if (eip >= stubLo && eip < stubHi) { hook.onStub(this, eip); return; }
        int start = eip;
        opsz = 4; seg = 0; rep = 0;
        try {
            for (;;) {
                int op = fetch8();
                switch (op) {
                    case 0x66: opsz = 2; continue;
                    case 0x64: seg = 1; continue;
                    case 0xF3: rep = 1; continue;
                    case 0xF2: rep = 2; continue;
                    case 0x26: case 0x2E: case 0x36: case 0x3E: case 0x65: case 0x67: case 0xF0: continue;
                    default: exec(op, start); return;
                }
            }
        } catch (EmuException e) { throw e; }
    }

    private void unsupported(int op, int start) {
        throw new EmuException(String.format("Unsupported opcode %02X at %08X", op, start));
    }

    private void exec(int op, int start) {
        int sz = opsz;
        if (op < 0x40 && (op & 7) < 6) {
            int aop = (op >> 3) & 7, res;
            switch (op & 7) {
                case 0: modrm(); res = alu(aop, getRM(1), getReg(mReg, 1), 1); if (aop != 7) setRM(1, res); return;
                case 1: modrm(); res = alu(aop, getRM(sz), getReg(mReg, sz), sz); if (aop != 7) setRM(sz, res); return;
                case 2: modrm(); res = alu(aop, getReg(mReg, 1), getRM(1), 1); if (aop != 7) setReg(mReg, 1, res); return;
                case 3: modrm(); res = alu(aop, getReg(mReg, sz), getRM(sz), sz); if (aop != 7) setReg(mReg, sz, res); return;
                case 4: res = alu(aop, getReg(0, 1), fetch8(), 1); if (aop != 7) setReg(0, 1, res); return;
                default: res = alu(aop, getReg(0, sz), sz == 2 ? fetch16() : fetch32(), sz); if (aop != 7) setReg(0, sz, res); return;
            }
        }
        if (op >= 0x40 && op <= 0x47) { setReg(op & 7, sz, inc(getReg(op & 7, sz), sz)); return; }
        if (op >= 0x48 && op <= 0x4F) { setReg(op & 7, sz, dec(getReg(op & 7, sz), sz)); return; }
        if (op >= 0x50 && op <= 0x57) { push(r[op & 7]); return; }
        if (op >= 0x58 && op <= 0x5F) { r[op & 7] = pop(); return; }
        if (op >= 0x70 && op <= 0x7F) { int d = (byte) fetch8(); if (cc(op & 15)) eip += d; return; }
        if (op >= 0x91 && op <= 0x97) { int t = getReg(0, sz); setReg(0, sz, getReg(op & 7, sz)); setReg(op & 7, sz, t); return; }
        if (op >= 0xB0 && op <= 0xB7) { setReg(op & 7, 1, fetch8()); return; }
        if (op >= 0xB8 && op <= 0xBF) { setReg(op & 7, sz, sz == 2 ? fetch16() : fetch32()); return; }
        switch (op) {
            case 0x0F: exec0F(start); return;
            case 0x68: push(fetch32()); return;
            case 0x6A: push((byte) fetch8()); return;
            case 0x69: case 0x6B: {
                modrm(); int a = getRM(sz); int b = op == 0x6B ? (byte) fetch8() : (sz == 2 ? (short) fetch16() : fetch32());
                imul2(a, b, sz); return; }
            case 0x80: case 0x82: { modrm(); int res = alu(mReg, getRM(1), fetch8(), 1); if (mReg != 7) setRM(1, res); return; }
            case 0x81: { modrm(); int a = getRM(sz); int i = sz == 2 ? fetch16() : fetch32(); int res = alu(mReg, a, i, sz); if (mReg != 7) setRM(sz, res); return; }
            case 0x83: { modrm(); int a = getRM(sz); int i = (byte) fetch8(); int res = alu(mReg, a, i, sz); if (mReg != 7) setRM(sz, res); return; }
            case 0x84: modrm(); alu(4, getRM(1), getReg(mReg, 1), 1); return;
            case 0x85: modrm(); alu(4, getRM(sz), getReg(mReg, sz), sz); return;
            case 0x86: { modrm(); int a = getRM(1), b = getReg(mReg, 1); setRM(1, b); setReg(mReg, 1, a); return; }
            case 0x87: { modrm(); int a = getRM(sz), b = getReg(mReg, sz); setRM(sz, b); setReg(mReg, sz, a); return; }
            case 0x88: modrm(); setRM(1, getReg(mReg, 1)); return;
            case 0x89: modrm(); setRM(sz, getReg(mReg, sz)); return;
            case 0x8A: modrm(); setReg(mReg, 1, getRM(1)); return;
            case 0x8B: modrm(); setReg(mReg, sz, getRM(sz)); return;
            case 0x8D: modrm(); setReg(mReg, sz, ea - (seg == 1 ? fsBase : 0)); return;
            case 0x8F: { modrm(); int v = pop(); setRM(4, v); return; }
            case 0x90: return;
            case 0x98: if (sz == 4) r[EAX] = (short) r[EAX]; else setReg(0, 2, (byte) r[EAX]); return;
            case 0x99: if (sz == 4) r[EDX] = r[EAX] >> 31; else setReg(EDX, 2, (short) r[EAX] >> 15); return;
            case 0x9C: push(flagsWord()); return;
            case 0x9D: setFlagsWord(pop()); return;
            case 0xA0: { int a = fetch32() + (seg == 1 ? fsBase : 0); setReg(0, 1, mem.r8(a)); return; }
            case 0xA1: { int a = fetch32() + (seg == 1 ? fsBase : 0); setReg(0, sz, read(a, sz)); return; }
            case 0xA2: { int a = fetch32() + (seg == 1 ? fsBase : 0); mem.w8(a, r[EAX]); return; }
            case 0xA3: { int a = fetch32() + (seg == 1 ? fsBase : 0); write(a, sz, getReg(0, sz)); return; }
            case 0xA4: case 0xA6: case 0xAA: case 0xAC: case 0xAE: strOp(op, 1); return;
            case 0xA5: case 0xA7: case 0xAB: case 0xAD: case 0xAF: strOp(op - 1, sz); return;
            case 0xA8: alu(4, getReg(0, 1), fetch8(), 1); return;
            case 0xA9: alu(4, getReg(0, sz), sz == 2 ? fetch16() : fetch32(), sz); return;
            case 0xC0: case 0xC1: case 0xD0: case 0xD1: case 0xD2: case 0xD3: {
                int s = (op & 1) == 0 ? 1 : sz;
                modrm();
                int cnt = op <= 0xC1 ? fetch8() : op <= 0xD1 ? 1 : r[ECX] & 0xFF;
                setRM(s, shift(mReg, getRM(s), cnt, s)); return; }
            case 0xC2: { int n = fetch16(); eip = pop(); r[ESP] += n; return; }
            case 0xC3: eip = pop(); return;
            case 0xC6: { modrm(); setRM(1, fetch8()); return; }
            case 0xC7: { modrm(); setRM(sz, sz == 2 ? fetch16() : fetch32()); return; }
            case 0xC9: r[ESP] = r[EBP]; r[EBP] = pop(); return;
            case 0xCC: halted = true; throw new EmuException("Breakpoint (int3) at " + String.format("%08X", start));
            case 0xE2: { int d = (byte) fetch8(); if (--r[ECX] != 0) eip += d; return; }
            case 0xE3: { int d = (byte) fetch8(); if (r[ECX] == 0) eip += d; return; }
            case 0xE8: { int d = fetch32(); push(eip); eip += d; return; }
            case 0xE9: { int d = fetch32(); eip += d; return; }
            case 0xEB: { int d = (byte) fetch8(); eip += d; return; }
            case 0xF4: halted = true; return;
            case 0xF5: cf = !cf; return;
            case 0xF6: modrm(); group3(1); return;
            case 0xF7: modrm(); group3(sz); return;
            case 0xF8: cf = false; return;
            case 0xF9: cf = true; return;
            case 0xFC: df = false; return;
            case 0xFD: df = true; return;
            case 0xFE: { modrm(); int v = getRM(1); if (mReg == 0) setRM(1, inc(v, 1)); else if (mReg == 1) setRM(1, dec(v, 1)); else unsupported(op, start); return; }
            case 0xFF: {
                modrm();
                switch (mReg) {
                    case 0: setRM(sz, inc(getRM(sz), sz)); return;
                    case 1: setRM(sz, dec(getRM(sz), sz)); return;
                    case 2: { int t = getRM(4); push(eip); eip = t; return; }
                    case 4: eip = getRM(4); return;
                    case 6: push(getRM(4)); return;
                    default: unsupported(op, start); return;
                }
            }
            default: unsupported(op, start);
        }
    }

    private void imul2(int a, int b, int sz) {
        if (sz == 4) { long t = (long) a * (long) b; setReg(mReg, 4, (int) t); cf = of = t != (int) t; }
        else { int t = (short) a * (short) b; setReg(mReg, 2, t); cf = of = t != (short) t; }
        szp(getReg(mReg, sz), sz);
    }

    private void exec0F(int start) {
        int op = fetch8(), sz = opsz;
        if (op >= 0x80 && op <= 0x8F) { int d = fetch32(); if (cc(op & 15)) eip += d; return; }
        if (op >= 0x90 && op <= 0x9F) { modrm(); setRM(1, cc(op & 15) ? 1 : 0); return; }
        if (op >= 0x40 && op <= 0x4F) { modrm(); int v = getRM(sz); if (cc(op & 15)) setReg(mReg, sz, v); return; }
        if (op >= 0xC8 && op <= 0xCF) { r[op & 7] = Integer.reverseBytes(r[op & 7]); return; }
        switch (op) {
            case 0x1F: modrm(); return;
            case 0x31: { long t = System.nanoTime(); r[EAX] = (int) t; r[EDX] = (int) (t >>> 32); return; }
            case 0xA2: r[EAX] = 0; r[EBX] = 0; r[ECX] = 0; r[EDX] = 0; return;
            case 0xAF: modrm(); imul2(getReg(mReg, sz), getRM(sz), sz); return;
            case 0xB6: modrm(); setReg(mReg, sz, getRM(1) & 0xFF); return;
            case 0xB7: modrm(); setReg(mReg, sz, getRM(2) & 0xFFFF); return;
            case 0xBE: modrm(); setReg(mReg, sz, (byte) getRM(1)); return;
            case 0xBF: modrm(); setReg(mReg, sz, (short) getRM(2)); return;
            case 0xB1: { modrm(); int d = getRM(sz); alu(7, getReg(0, sz), d, sz);
                if (zf) setRM(sz, getReg(mReg, sz)); else setReg(0, sz, d); return; }
            case 0xC1: { modrm(); int d = getRM(sz), s = getReg(mReg, sz); int res = alu(0, d, s, sz); setReg(mReg, sz, d); setRM(sz, res); return; }
            default: throw new EmuException(String.format("Unsupported opcode 0F %02X at %08X", op, start));
        }
    }
}
