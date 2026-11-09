package com.unixroot.win10.core;

/** SSE / SSE2 subset: scalar and packed float/double, conversions, moves and integer operations. */
public final class Sse {
    private final Cpu c;
    private final long[] x = new long[16]; // xmm i: lo at 2i, hi at 2i+1
    public int mxcsr = 0x1F80;

    Sse(Cpu c) { this.c = c; }

    // ---- operand helpers ----
    private int rd() { return c.mReg; }
    private long lo(int i) { return x[2 * i]; }
    private long hi(int i) { return x[2 * i + 1]; }
    private long eLo() { return c.mMod == 3 ? x[2 * c.mRm] : c.mem.r64(c.ea); }
    private long eHi() { return c.mMod == 3 ? x[2 * c.mRm + 1] : c.mem.r64(c.ea + 8); }
    private int e32() { return c.mMod == 3 ? (int) x[2 * c.mRm] : c.mem.r32(c.ea); }
    private int g32() { return c.mMod == 3 ? c.r[c.mRm] : c.mem.r32(c.ea); }

    private void storeE128(long l, long h) {
        if (c.mMod == 3) { x[2 * c.mRm] = l; x[2 * c.mRm + 1] = h; }
        else { c.mem.w64(c.ea, l); c.mem.w64(c.ea + 8, h); }
    }

    private void set(int i, long l, long h) { x[2 * i] = l; x[2 * i + 1] = h; }

    private static float f(long v, int shift) { return Float.intBitsToFloat((int) (v >>> shift)); }
    private static long fb(float v) { return Float.floatToRawIntBits(v) & 0xFFFFFFFFL; }
    private static double d(long v) { return Double.longBitsToDouble(v); }
    private static long db(double v) { return Double.doubleToRawLongBits(v); }

    private static double fop(int op, double a, double b) {
        switch (op) {
            case 0x51: return Math.sqrt(b);
            case 0x58: return a + b;
            case 0x59: return a * b;
            case 0x5C: return a - b;
            case 0x5D: return a < b ? a : b;
            case 0x5E: return a / b;
            case 0x5F: return a > b ? a : b;
            case 0x52: return 1.0 / Math.sqrt(b);
            default: return 1.0 / b;
        }
    }

    private static float fopf(int op, float a, float b) {
        switch (op) {
            case 0x51: return (float) Math.sqrt(b);
            case 0x58: return a + b;
            case 0x59: return a * b;
            case 0x5C: return a - b;
            case 0x5D: return a < b ? a : b;
            case 0x5E: return a / b;
            case 0x5F: return a > b ? a : b;
            case 0x52: return (float) (1.0 / Math.sqrt(b));
            default: return 1.0f / b;
        }
    }

    private static boolean pred(int p, double a, double b) {
        boolean un = Double.isNaN(a) || Double.isNaN(b);
        switch (p & 7) {
            case 0: return !un && a == b;
            case 1: return !un && a < b;
            case 2: return !un && a <= b;
            case 3: return un;
            case 4: return un || a != b;
            case 5: return un || !(a < b);
            case 6: return un || !(a <= b);
            default: return !un;
        }
    }

    private int round(double v) {
        double r;
        switch ((mxcsr >> 13) & 3) {
            case 0: r = Math.rint(v); break;
            case 1: r = Math.floor(v); break;
            case 2: r = Math.ceil(v); break;
            default: r = v < 0 ? Math.ceil(v) : Math.floor(v);
        }
        return (Double.isNaN(r) || r > Integer.MAX_VALUE || r < Integer.MIN_VALUE) ? 0x80000000 : (int) r;
    }

    private static int trunc(double v) {
        double r = v < 0 ? Math.ceil(v) : Math.floor(v);
        return (Double.isNaN(r) || r > Integer.MAX_VALUE || r < Integer.MIN_VALUE) ? 0x80000000 : (int) r;
    }

    private void comis(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) { c.zf = c.pf = c.cf = true; }
        else { c.zf = a == b; c.pf = false; c.cf = a < b; }
        c.of = c.sf = c.af = false;
    }

    /** Executes 0F op as an SSE instruction; returns false (without consuming anything) if op is not SSE. */
    boolean exec(int op, int pfx) {
        switch (op) {
            case 0x10: c.modrm(); {
                int r = rd();
                if (pfx == 0xF3) { if (c.mMod == 3) x[2 * r] = (x[2 * r] & ~0xFFFFFFFFL) | (eLo() & 0xFFFFFFFFL); else set(r, c.mem.r32(c.ea) & 0xFFFFFFFFL, 0); }
                else if (pfx == 0xF2) { if (c.mMod == 3) x[2 * r] = eLo(); else set(r, c.mem.r64(c.ea), 0); }
                else set(r, eLo(), eHi());
                return true; }
            case 0x11: c.modrm(); {
                int r = rd();
                if (pfx == 0xF3) { if (c.mMod == 3) x[2 * c.mRm] = (x[2 * c.mRm] & ~0xFFFFFFFFL) | (lo(r) & 0xFFFFFFFFL); else c.mem.w32(c.ea, (int) lo(r)); }
                else if (pfx == 0xF2) { if (c.mMod == 3) x[2 * c.mRm] = lo(r); else c.mem.w64(c.ea, lo(r)); }
                else storeE128(lo(r), hi(r));
                return true; }
            case 0x12: c.modrm(); {
                int r = rd();
                if (pfx == 0xF2) { long v = eLo(); set(r, v, v); }
                else if (c.mMod == 3) x[2 * r] = eHi();
                else x[2 * r] = c.mem.r64(c.ea);
                return true; }
            case 0x13: c.modrm(); c.mem.w64(c.ea, lo(rd())); return true;
            case 0x14: c.modrm(); {
                int r = rd(); long dl = lo(r), sl = eLo();
                if (pfx == 0x66) set(r, dl, sl);
                else set(r, (dl & 0xFFFFFFFFL) | (sl << 32), (dl >>> 32) | (sl & 0xFFFFFFFF00000000L));
                return true; }
            case 0x15: c.modrm(); {
                int r = rd(); long dh = hi(r), sh = eHi();
                if (pfx == 0x66) set(r, dh, sh);
                else set(r, (dh & 0xFFFFFFFFL) | (sh << 32), (dh >>> 32) | (sh & 0xFFFFFFFF00000000L));
                return true; }
            case 0x16: c.modrm(); {
                int r = rd();
                if (c.mMod == 3 && pfx == 0) x[2 * r + 1] = eLo(); else x[2 * r + 1] = c.mem.r64(c.ea);
                return true; }
            case 0x17: c.modrm(); c.mem.w64(c.ea, hi(rd())); return true;
            case 0x28: c.modrm(); set(rd(), eLo(), eHi()); return true;
            case 0x29: c.modrm(); storeE128(lo(rd()), hi(rd())); return true;
            case 0x2B: case 0xE7: c.modrm(); c.mem.w64(c.ea, lo(rd())); c.mem.w64(c.ea + 8, hi(rd())); return true;
            case 0x2A: c.modrm(); {
                int v = g32(), r = rd();
                if (pfx == 0xF3) x[2 * r] = (x[2 * r] & ~0xFFFFFFFFL) | fb((float) v);
                else if (pfx == 0xF2) x[2 * r] = db((double) v);
                else throw new EmuException("MMX cvtpi2ps unsupported");
                return true; }
            case 0x2C: case 0x2D: c.modrm(); {
                double v;
                if (pfx == 0xF3) v = Float.intBitsToFloat(e32()); else if (pfx == 0xF2) v = d(eLo());
                else throw new EmuException("MMX cvtps2pi unsupported");
                c.r[rd()] = op == 0x2C ? trunc(v) : round(v);
                return true; }
            case 0x2E: case 0x2F: c.modrm();
                if (pfx == 0x66) comis(d(lo(rd())), d(eLo())); else comis(f(lo(rd()), 0), Float.intBitsToFloat(e32()));
                return true;
            case 0x50: c.modrm(); {
                int m = 0, r = c.mRm;
                if (pfx == 0x66) m = (int) (x[2 * r] >>> 63) | (int) ((x[2 * r + 1] >>> 63) << 1);
                else m = (int) ((x[2 * r] >>> 31) & 1) | (int) (((x[2 * r] >>> 63) & 1) << 1) | (int) (((x[2 * r + 1] >>> 31) & 1) << 2) | (int) (((x[2 * r + 1] >>> 63) & 1) << 3);
                c.r[rd()] = m; return true; }
            case 0x51: case 0x52: case 0x53: case 0x58: case 0x59: case 0x5C: case 0x5D: case 0x5E: case 0x5F: c.modrm(); {
                int r = rd();
                if (pfx == 0xF3) { float v = fopf(op, f(lo(r), 0), Float.intBitsToFloat(e32())); x[2 * r] = (x[2 * r] & ~0xFFFFFFFFL) | fb(v); }
                else if (pfx == 0xF2) x[2 * r] = db(fop(op, d(lo(r)), d(eLo())));
                else if (pfx == 0x66) set(r, db(fop(op, d(lo(r)), d(eLo()))), db(fop(op, d(hi(r)), d(eHi()))));
                else {
                    long sl = eLo(), sh = eHi(), dl = lo(r), dh = hi(r);
                    set(r, fb(fopf(op, f(dl, 0), f(sl, 0))) | (fb(fopf(op, f(dl, 32), f(sl, 32))) << 32),
                            fb(fopf(op, f(dh, 0), f(sh, 0))) | (fb(fopf(op, f(dh, 32), f(sh, 32))) << 32));
                }
                return true; }
            case 0x54: c.modrm(); set(rd(), lo(rd()) & eLo(), hi(rd()) & eHi()); return true;
            case 0x55: c.modrm(); set(rd(), ~lo(rd()) & eLo(), ~hi(rd()) & eHi()); return true;
            case 0x56: c.modrm(); set(rd(), lo(rd()) | eLo(), hi(rd()) | eHi()); return true;
            case 0x57: c.modrm(); set(rd(), lo(rd()) ^ eLo(), hi(rd()) ^ eHi()); return true;
            case 0x5A: c.modrm(); {
                int r = rd();
                if (pfx == 0xF3) x[2 * r] = db((double) Float.intBitsToFloat(e32()));
                else if (pfx == 0xF2) x[2 * r] = (x[2 * r] & ~0xFFFFFFFFL) | fb((float) d(eLo()));
                else if (pfx == 0x66) set(r, fb((float) d(eLo())) | (fb((float) d(eHi())) << 32), 0);
                else { long s = eLo(); set(r, db((double) f(s, 0)), db((double) f(s, 32))); }
                return true; }
            case 0x5B: c.modrm(); {
                int r = rd(); long sl = eLo(), sh = eHi();
                if (pfx == 0) set(r, fb((float) (int) sl) | (fb((float) (int) (sl >>> 32)) << 32), fb((float) (int) sh) | (fb((float) (int) (sh >>> 32)) << 32));
                else {
                    boolean t = pfx == 0xF3;
                    int[] v = {t ? trunc(f(sl, 0)) : round(f(sl, 0)), t ? trunc(f(sl, 32)) : round(f(sl, 32)), t ? trunc(f(sh, 0)) : round(f(sh, 0)), t ? trunc(f(sh, 32)) : round(f(sh, 32))};
                    set(r, (v[0] & 0xFFFFFFFFL) | ((long) v[1] << 32), (v[2] & 0xFFFFFFFFL) | ((long) v[3] << 32));
                }
                return true; }
            case 0xE6: c.modrm(); {
                int r = rd(); long s = eLo();
                if (pfx == 0xF3) set(r, db((double) (int) s), db((double) (int) (s >>> 32)));
                else {
                    boolean t = pfx == 0x66;
                    int a = t ? trunc(d(s)) : round(d(s)), b = t ? trunc(d(eHi())) : round(d(eHi()));
                    set(r, (a & 0xFFFFFFFFL) | ((long) b << 32), 0);
                }
                return true; }
            case 0xC2: c.modrm(); {
                int r = rd(); int p = c.fetch8();
                if (pfx == 0xF3) { boolean t = pred(p, f(lo(r), 0), Float.intBitsToFloat(e32())); x[2 * r] = (x[2 * r] & ~0xFFFFFFFFL) | (t ? 0xFFFFFFFFL : 0); }
                else if (pfx == 0xF2) x[2 * r] = pred(p, d(lo(r)), d(eLo())) ? -1L : 0;
                else if (pfx == 0x66) set(r, pred(p, d(lo(r)), d(eLo())) ? -1L : 0, pred(p, d(hi(r)), d(eHi())) ? -1L : 0);
                else {
                    long sl = eLo(), sh = eHi(), dl = lo(r), dh = hi(r);
                    set(r, (pred(p, f(dl, 0), f(sl, 0)) ? 0xFFFFFFFFL : 0) | (pred(p, f(dl, 32), f(sl, 32)) ? 0xFFFFFFFF00000000L : 0),
                            (pred(p, f(dh, 0), f(sh, 0)) ? 0xFFFFFFFFL : 0) | (pred(p, f(dh, 32), f(sh, 32)) ? 0xFFFFFFFF00000000L : 0));
                }
                return true; }
            case 0xC6: c.modrm(); {
                int r = rd(); int imm = c.fetch8();
                long dl = lo(r), dh = hi(r), sl = eLo(), sh = eHi();
                if (pfx == 0x66) set(r, (imm & 1) == 0 ? dl : dh, (imm & 2) == 0 ? sl : sh);
                else {
                    int[] dd = {(int) dl, (int) (dl >>> 32), (int) dh, (int) (dh >>> 32)}, ss = {(int) sl, (int) (sl >>> 32), (int) sh, (int) (sh >>> 32)};
                    int a = dd[imm & 3], b = dd[(imm >> 2) & 3], cc = ss[(imm >> 4) & 3], dd2 = ss[(imm >> 6) & 3];
                    set(r, (a & 0xFFFFFFFFL) | ((long) b << 32), (cc & 0xFFFFFFFFL) | ((long) dd2 << 32));
                }
                return true; }
            case 0x6E: c.modrm(); if (pfx != 0x66) throw new EmuException("MMX movd unsupported"); set(rd(), g32() & 0xFFFFFFFFL, 0); return true;
            case 0x7E: c.modrm();
                if (pfx == 0xF3) set(rd(), eLo(), 0);
                else if (pfx == 0x66) { int v = (int) lo(rd()); if (c.mMod == 3) c.r[c.mRm] = v; else c.mem.w32(c.ea, v); }
                else throw new EmuException("MMX movd unsupported");
                return true;
            case 0xD6: c.modrm(); if (c.mMod == 3) set(c.mRm, lo(rd()), 0); else c.mem.w64(c.ea, lo(rd())); return true;
            case 0x6F: c.modrm(); if (pfx == 0) throw new EmuException("MMX movq unsupported"); set(rd(), eLo(), eHi()); return true;
            case 0x7F: c.modrm(); if (pfx == 0) throw new EmuException("MMX movq unsupported"); storeE128(lo(rd()), hi(rd())); return true;
            case 0xC3: c.modrm(); c.mem.w32(c.ea, c.r[rd()]); return true;
            case 0xD7: c.modrm(); {
                byte[] b = bytes(c.mRm); int m = 0;
                for (int i = 0; i < 16; i++) if (b[i] < 0) m |= 1 << i;
                c.r[rd()] = m; return true; }
            case 0xC4: c.modrm(); { int v = g32() & 0xFFFF; int imm = c.fetch8() & 7; byte[] b = bytes(rd()); b[2 * imm] = (byte) v; b[2 * imm + 1] = (byte) (v >> 8); put(rd(), b); return true; }
            case 0xC5: c.modrm(); { int imm = c.fetch8() & 7; byte[] b = bytes(c.mRm); c.r[rd()] = (b[2 * imm] & 0xFF) | ((b[2 * imm + 1] & 0xFF) << 8); return true; }
            case 0x70: c.modrm(); {
                int imm = c.fetch8(); byte[] s = eBytes(); byte[] o = new byte[16];
                if (pfx == 0x66) for (int i = 0; i < 4; i++) { int k = (imm >> (2 * i)) & 3; System.arraycopy(s, 4 * k, o, 4 * i, 4); }
                else if (pfx == 0xF2) { System.arraycopy(s, 8, o, 8, 8); for (int i = 0; i < 4; i++) { int k = (imm >> (2 * i)) & 3; System.arraycopy(s, 2 * k, o, 2 * i, 2); } }
                else if (pfx == 0xF3) { System.arraycopy(s, 0, o, 0, 8); for (int i = 0; i < 4; i++) { int k = (imm >> (2 * i)) & 3; System.arraycopy(s, 8 + 2 * k, o, 8 + 2 * i, 2); } }
                else throw new EmuException("MMX pshufw unsupported");
                put(rd(), o); return true; }
            case 0x71: case 0x72: case 0x73: c.modrm(); {
                int imm = c.fetch8(); int r = c.mRm, g = c.mReg;
                byte[] b = bytes(r);
                shiftImm(op, g, imm, b);
                put(r, b); return true; }
            default: break;
        }
        // packed integer operations (need 66 prefix)
        if (isIntOp(op)) {
            if (pfx != 0x66) throw new EmuException("MMX instruction 0F " + Integer.toHexString(op) + " unsupported");
            c.modrm();
            byte[] a = bytes(rd()), b = eBytes();
            if (op == 0xD1 || op == 0xD2 || op == 0xD3 || op == 0xE1 || op == 0xE2 || op == 0xF1 || op == 0xF2 || op == 0xF3) {
                int cnt = (int) Math.min(eLo() & 0xFFFFFFFFL, 255);
                shiftCnt(op, cnt, a);
                put(rd(), a);
            } else put(rd(), intOp(op, a, b));
            return true;
        }
        return false;
    }

    private static boolean isIntOp(int op) {
        if (op >= 0x60 && op <= 0x6D) return true;
        if (op >= 0x74 && op <= 0x76) return true;
        return op >= 0xD1 && op <= 0xFE && op != 0xD6 && op != 0xD7 && op != 0xE6 && op != 0xE7 && op != 0xF7;
    }

    // ---- byte-lane helpers ----
    private byte[] bytes(int r) {
        byte[] b = new byte[16];
        long l = lo(r), h = hi(r);
        for (int i = 0; i < 8; i++) { b[i] = (byte) (l >>> (8 * i)); b[8 + i] = (byte) (h >>> (8 * i)); }
        return b;
    }

    private byte[] eBytes() {
        byte[] b = new byte[16];
        long l = eLo(), h = eHi();
        for (int i = 0; i < 8; i++) { b[i] = (byte) (l >>> (8 * i)); b[8 + i] = (byte) (h >>> (8 * i)); }
        return b;
    }

    private void put(int r, byte[] b) {
        long l = 0, h = 0;
        for (int i = 7; i >= 0; i--) { l = (l << 8) | (b[i] & 0xFF); h = (h << 8) | (b[8 + i] & 0xFF); }
        set(r, l, h);
    }

    private static long lane(byte[] a, int i, int w, boolean signed) {
        long v = 0;
        for (int k = w - 1; k >= 0; k--) v = (v << 8) | (a[i * w + k] & 0xFF);
        if (signed && w < 8) { int sh = 64 - 8 * w; v = (v << sh) >> sh; }
        return v;
    }

    private static void setLane(byte[] a, int i, int w, long v) {
        for (int k = 0; k < w; k++) a[i * w + k] = (byte) (v >>> (8 * k));
    }

    private static long sat(long v, int w, boolean signed) {
        long min = signed ? -(1L << (8 * w - 1)) : 0, max = signed ? (1L << (8 * w - 1)) - 1 : (1L << (8 * w)) - 1;
        return v < min ? min : v > max ? max : v;
    }

    private byte[] intOp(int op, byte[] a, byte[] b) {
        byte[] o = new byte[16];
        int w;
        switch (op) {
            case 0xDB: for (int i = 0; i < 16; i++) o[i] = (byte) (a[i] & b[i]); return o;
            case 0xDF: for (int i = 0; i < 16; i++) o[i] = (byte) (~a[i] & b[i]); return o;
            case 0xEB: for (int i = 0; i < 16; i++) o[i] = (byte) (a[i] | b[i]); return o;
            case 0xEF: for (int i = 0; i < 16; i++) o[i] = (byte) (a[i] ^ b[i]); return o;
            case 0x60: case 0x61: case 0x62: case 0x6C: {
                w = op == 0x60 ? 1 : op == 0x61 ? 2 : op == 0x62 ? 4 : 8;
                for (int i = 0; i < 8 / w; i++) { System.arraycopy(a, i * w, o, 2 * i * w, w); System.arraycopy(b, i * w, o, (2 * i + 1) * w, w); }
                return o; }
            case 0x68: case 0x69: case 0x6A: case 0x6D: {
                w = op == 0x68 ? 1 : op == 0x69 ? 2 : op == 0x6A ? 4 : 8;
                for (int i = 0; i < 8 / w; i++) { System.arraycopy(a, 8 + i * w, o, 2 * i * w, w); System.arraycopy(b, 8 + i * w, o, (2 * i + 1) * w, w); }
                return o; }
            case 0x63: case 0x67: case 0x6B: {
                w = op == 0x6B ? 4 : 2; int ow = w / 2; boolean sg = op != 0x67; int n = 16 / w;
                for (int i = 0; i < n; i++) { setLane(o, i, ow, sat(lane(a, i, w, true), ow, sg)); setLane(o, n + i, ow, sat(lane(b, i, w, true), ow, sg)); }
                return o; }
            case 0x74: case 0x75: case 0x76: case 0x64: case 0x65: case 0x66: {
                w = (op & 3) == 0 || op == 0x74 || op == 0x64 ? 1 : (op == 0x75 || op == 0x65) ? 2 : 4;
                boolean gt = op >= 0x64 && op <= 0x66;
                for (int i = 0; i < 16 / w; i++) {
                    long x1 = lane(a, i, w, true), y = lane(b, i, w, true);
                    setLane(o, i, w, (gt ? x1 > y : x1 == y) ? -1L : 0);
                }
                return o; }
            case 0xFC: case 0xFD: case 0xFE: case 0xD4: case 0xF8: case 0xF9: case 0xFA: case 0xFB: {
                boolean sub = op >= 0xF8 && op != 0xFC && op != 0xFD && op != 0xFE && op != 0xD4;
                w = (op == 0xFC || op == 0xF8) ? 1 : (op == 0xFD || op == 0xF9) ? 2 : (op == 0xFE || op == 0xFA) ? 4 : 8;
                for (int i = 0; i < 16 / w; i++) setLane(o, i, w, sub ? lane(a, i, w, false) - lane(b, i, w, false) : lane(a, i, w, false) + lane(b, i, w, false));
                return o; }
            case 0xDC: case 0xDD: case 0xD8: case 0xD9: case 0xEC: case 0xED: case 0xE8: case 0xE9: {
                w = (op & 1) == 0 ? 1 : 2; boolean sg = op >= 0xE8 && op <= 0xED; boolean sub = op == 0xD8 || op == 0xD9 || op == 0xE8 || op == 0xE9;
                for (int i = 0; i < 16 / w; i++) {
                    long p = lane(a, i, w, sg), q = lane(b, i, w, sg);
                    setLane(o, i, w, sat(sub ? p - q : p + q, w, sg));
                }
                return o; }
            case 0xD5: for (int i = 0; i < 8; i++) setLane(o, i, 2, lane(a, i, 2, true) * lane(b, i, 2, true)); return o;
            case 0xE5: for (int i = 0; i < 8; i++) setLane(o, i, 2, (lane(a, i, 2, true) * lane(b, i, 2, true)) >> 16); return o;
            case 0xE4: for (int i = 0; i < 8; i++) setLane(o, i, 2, (lane(a, i, 2, false) * lane(b, i, 2, false)) >>> 16); return o;
            case 0xF4: for (int i = 0; i < 2; i++) setLane(o, i, 8, (lane(a, 2 * i, 4, false)) * (lane(b, 2 * i, 4, false))); return o;
            case 0xF5: for (int i = 0; i < 4; i++) setLane(o, i, 4, lane(a, 2 * i, 2, true) * lane(b, 2 * i, 2, true) + lane(a, 2 * i + 1, 2, true) * lane(b, 2 * i + 1, 2, true)); return o;
            case 0xDA: case 0xDE: for (int i = 0; i < 16; i++) { int p = a[i] & 0xFF, q = b[i] & 0xFF; o[i] = (byte) (op == 0xDA ? Math.min(p, q) : Math.max(p, q)); } return o;
            case 0xEA: case 0xEE: for (int i = 0; i < 8; i++) { long p = lane(a, i, 2, true), q = lane(b, i, 2, true); setLane(o, i, 2, op == 0xEA ? Math.min(p, q) : Math.max(p, q)); } return o;
            case 0xE0: for (int i = 0; i < 16; i++) o[i] = (byte) (((a[i] & 0xFF) + (b[i] & 0xFF) + 1) >> 1); return o;
            case 0xE3: for (int i = 0; i < 8; i++) setLane(o, i, 2, (lane(a, i, 2, false) + lane(b, i, 2, false) + 1) >> 1); return o;
            case 0xF6: {
                for (int h = 0; h < 2; h++) { long s = 0; for (int i = 0; i < 8; i++) s += Math.abs((a[8 * h + i] & 0xFF) - (b[8 * h + i] & 0xFF)); setLane(o, h, 8, s); }
                return o; }
            default: throw new EmuException("Unsupported SSE2 integer opcode 0F " + Integer.toHexString(op));
        }
    }

    private void shiftCnt(int op, int cnt, byte[] a) {
        int w = (op == 0xD1 || op == 0xE1 || op == 0xF1) ? 2 : (op == 0xD2 || op == 0xE2 || op == 0xF2) ? 4 : 8;
        boolean left = op >= 0xF1, arith = op == 0xE1 || op == 0xE2;
        for (int i = 0; i < 16 / w; i++) {
            long v = lane(a, i, w, arith);
            long r;
            if (left) r = cnt >= 8 * w ? 0 : v << cnt;
            else if (arith) r = v >> Math.min(cnt, 8 * w - 1);
            else r = cnt >= 8 * w ? 0 : lane(a, i, w, false) >>> cnt;
            setLane(a, i, w, r);
        }
    }

    private void shiftImm(int op, int g, int imm, byte[] b) {
        if (op == 0x73 && (g == 3 || g == 7)) {
            byte[] o = new byte[16];
            for (int i = 0; i < 16; i++) { int s = g == 3 ? i + imm : i - imm; if (s >= 0 && s < 16) o[i] = b[s]; }
            System.arraycopy(o, 0, b, 0, 16);
            return;
        }
        int w = op == 0x71 ? 2 : op == 0x72 ? 4 : 8;
        int code = g == 2 ? (w == 2 ? 0xD1 : w == 4 ? 0xD2 : 0xD3) : g == 4 ? (w == 2 ? 0xE1 : 0xE2) : g == 6 ? (w == 2 ? 0xF1 : w == 4 ? 0xF2 : 0xF3) : -1;
        if (code < 0) throw new EmuException("Unsupported SSE2 shift group");
        shiftCnt(code, imm, b);
    }
}
