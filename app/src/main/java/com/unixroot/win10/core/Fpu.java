package com.unixroot.win10.core;

/** x87 floating point unit (double precision internally, 80-bit load/store supported). */
public final class Fpu {
    private final Cpu c;
    private final double[] st = new double[8];
    private int top;
    public int fcw = 0x37F;
    private boolean c0, c1, c2, c3;

    Fpu(Cpu c) { this.c = c; }

    public double get(int i) { return st[(top + i) & 7]; }
    private void set(int i, double v) { st[(top + i) & 7] = v; }
    public void push(double v) { top = (top - 1) & 7; st[top] = v; }
    public double pop() { double v = st[top]; top = (top + 1) & 7; return v; }

    private int fsw() { return (top << 11) | (c0 ? 0x100 : 0) | (c1 ? 0x200 : 0) | (c2 ? 0x400 : 0) | (c3 ? 0x4000 : 0); }

    private double round(double v) {
        switch ((fcw >> 10) & 3) {
            case 0: return Math.rint(v);
            case 1: return Math.floor(v);
            case 2: return Math.ceil(v);
            default: return v < 0 ? Math.ceil(v) : Math.floor(v);
        }
    }

    private long toInt(double v, long min, long max, long indefinite) {
        if (Double.isNaN(v)) return indefinite;
        double r = round(v);
        if (r < min || r > max) return indefinite;
        return (long) r;
    }

    private void cmp(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) { c0 = c2 = c3 = true; }
        else { c0 = a < b; c2 = false; c3 = a == b; }
    }

    private void cmpEflags(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) { c.zf = c.pf = c.cf = true; }
        else { c.zf = a == b; c.pf = false; c.cf = a < b; }
        c.of = c.sf = c.af = false;
    }

    private double arith(int op, double a, double b) {
        switch (op) {
            case 0: return a + b;
            case 1: return a * b;
            case 4: return a - b;
            case 5: return b - a;
            case 6: return a / b;
            default: return b / a;
        }
    }

    void exec(int op) {
        c.modrm();
        if (c.mMod == 3) reg(op, c.mReg, c.mRm); else mem(op, c.mReg);
    }

    private void mem(int op, int r) {
        int ea = c.ea;
        Memory m = c.mem;
        switch (op) {
            case 0xD8: case 0xDC: case 0xDA: case 0xDE: {
                double v = op == 0xD8 ? Float.intBitsToFloat(m.r32(ea)) : op == 0xDC ? Double.longBitsToDouble(m.r64(ea))
                        : op == 0xDA ? (double) m.r32(ea) : (double) (short) m.r16(ea);
                if (r == 2 || r == 3) { cmp(get(0), v); if (r == 3) pop(); }
                else set(0, arith(r, get(0), v));
                return;
            }
            case 0xD9:
                switch (r) {
                    case 0: push(Float.intBitsToFloat(m.r32(ea))); return;
                    case 2: m.w32(ea, Float.floatToRawIntBits((float) get(0))); return;
                    case 3: m.w32(ea, Float.floatToRawIntBits((float) pop())); return;
                    case 5: fcw = m.r16(ea); return;
                    case 7: m.w16(ea, fcw); return;
                    case 4: return; // fldenv
                    case 6: for (int i = 0; i < 28; i += 4) m.w32(ea + i, 0); m.w16(ea, fcw); m.w16(ea + 4, fsw()); return;
                    default: throw new EmuException("Unsupported x87 D9 /" + r);
                }
            case 0xDB:
                switch (r) {
                    case 0: push((double) m.r32(ea)); return;
                    case 1: m.w32(ea, (int) toIntTrunc(pop(), Integer.MIN_VALUE, Integer.MAX_VALUE, 0x80000000L)); return;
                    case 2: m.w32(ea, (int) toInt(get(0), Integer.MIN_VALUE, Integer.MAX_VALUE, 0x80000000L)); return;
                    case 3: m.w32(ea, (int) toInt(pop(), Integer.MIN_VALUE, Integer.MAX_VALUE, 0x80000000L)); return;
                    case 5: push(load80(ea)); return;
                    case 7: store80(ea, pop()); return;
                    default: throw new EmuException("Unsupported x87 DB /" + r);
                }
            case 0xDD:
                switch (r) {
                    case 0: push(Double.longBitsToDouble(m.r64(ea))); return;
                    case 1: m.w64(ea, toIntTrunc(pop(), Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE)); return;
                    case 2: m.w64(ea, Double.doubleToRawLongBits(get(0))); return;
                    case 3: m.w64(ea, Double.doubleToRawLongBits(pop())); return;
                    case 4: return; // frstor
                    case 6: for (int i = 0; i < 108; i += 4) m.w32(ea + i, 0); return; // fnsave
                    case 7: m.w16(ea, fsw()); return;
                    default: throw new EmuException("Unsupported x87 DD /" + r);
                }
            default: // DF
                switch (r) {
                    case 0: push((double) (short) m.r16(ea)); return;
                    case 1: m.w16(ea, (int) toIntTrunc(pop(), Short.MIN_VALUE, Short.MAX_VALUE, 0x8000L)); return;
                    case 2: m.w16(ea, (int) toInt(get(0), Short.MIN_VALUE, Short.MAX_VALUE, 0x8000L)); return;
                    case 3: m.w16(ea, (int) toInt(pop(), Short.MIN_VALUE, Short.MAX_VALUE, 0x8000L)); return;
                    case 5: push((double) m.r64(ea)); return;
                    case 7: m.w64(ea, toInt(pop(), Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE)); return;
                    default: throw new EmuException("Unsupported x87 DF /" + r + " (BCD)");
                }
        }
    }

    private long toIntTrunc(double v, long min, long max, long indef) {
        if (Double.isNaN(v)) return indef;
        double r = v < 0 ? Math.ceil(v) : Math.floor(v);
        if (r < min || r > max) return indef;
        return (long) r;
    }

    private void reg(int op, int r, int i) {
        int b = 0xC0 | (r << 3) | i;
        switch (op) {
            case 0xD8: {
                double v = get(i);
                if (r == 2 || r == 3) { cmp(get(0), v); if (r == 3) pop(); }
                else set(0, arith(r, get(0), v));
                return;
            }
            case 0xDC: case 0xDE: {
                double sti = get(i), s0 = get(0), res;
                switch (r) {
                    case 0: res = sti + s0; break;
                    case 1: res = sti * s0; break;
                    case 4: res = s0 - sti; break;
                    case 5: res = sti - s0; break;
                    case 6: res = s0 / sti; break;
                    case 7: res = sti / s0; break;
                    default:
                        if (op == 0xDE && b == 0xD9) { cmp(get(0), get(1)); pop(); pop(); return; }
                        throw new EmuException("Unsupported x87 " + Integer.toHexString(op) + " " + Integer.toHexString(b));
                }
                set(i, res);
                if (op == 0xDE) pop();
                return;
            }
            case 0xD9:
                if (b >= 0xC0 && b <= 0xC7) { push(get(i)); return; }
                if (b >= 0xC8 && b <= 0xCF) { double t = get(0); set(0, get(i)); set(i, t); return; }
                switch (b) {
                    case 0xD0: return;
                    case 0xE0: set(0, -get(0)); return;
                    case 0xE1: set(0, Math.abs(get(0))); return;
                    case 0xE4: cmp(get(0), 0.0); return;
                    case 0xE5: {
                        double v = get(0);
                        c1 = (Double.doubleToRawLongBits(v) < 0);
                        c0 = c2 = c3 = false;
                        if (Double.isNaN(v)) c0 = true; else if (Double.isInfinite(v)) { c0 = true; c2 = true; } else if (v == 0) c3 = true; else c2 = true;
                        return; }
                    case 0xE8: push(1.0); return;
                    case 0xE9: push(Math.log(10) / Math.log(2)); return;
                    case 0xEA: push(1 / Math.log(2)); return;
                    case 0xEB: push(Math.PI); return;
                    case 0xEC: push(Math.log10(2)); return;
                    case 0xED: push(Math.log(2)); return;
                    case 0xEE: push(0.0); return;
                    case 0xF0: set(0, Math.pow(2, get(0)) - 1); return;
                    case 0xF1: { double v = get(1) * (Math.log(get(0)) / Math.log(2)); pop(); set(0, v); return; }
                    case 0xF2: set(0, Math.tan(get(0))); push(1.0); c2 = false; return;
                    case 0xF3: { double v = Math.atan2(get(1), get(0)); pop(); set(0, v); return; }
                    case 0xF5: set(0, Math.IEEEremainder(get(0), get(1))); c2 = false; return;
                    case 0xF6: top = (top - 1) & 7; return;
                    case 0xF7: top = (top + 1) & 7; return;
                    case 0xF8: set(0, get(0) % get(1)); c2 = false; return;
                    case 0xF9: { double v = get(1) * (Math.log(get(0) + 1) / Math.log(2)); pop(); set(0, v); return; }
                    case 0xFA: set(0, Math.sqrt(get(0))); return;
                    case 0xFB: { double x = get(0); set(0, Math.sin(x)); push(Math.cos(x)); c2 = false; return; }
                    case 0xFC: set(0, round(get(0))); return;
                    case 0xFD: set(0, get(0) * Math.pow(2, (long) (get(1) < 0 ? Math.ceil(get(1)) : Math.floor(get(1))))); return;
                    case 0xFE: set(0, Math.sin(get(0))); c2 = false; return;
                    case 0xFF: set(0, Math.cos(get(0))); c2 = false; return;
                    default: throw new EmuException("Unsupported x87 D9 " + Integer.toHexString(b));
                }
            case 0xDA:
                if (b >= 0xC0 && b <= 0xDF) { boolean cond = r == 0 ? c.cf : r == 1 ? c.zf : r == 2 ? (c.cf || c.zf) : c.pf; if (cond) set(0, get(i)); return; }
                if (b == 0xE9) { cmp(get(0), get(1)); pop(); pop(); return; }
                throw new EmuException("Unsupported x87 DA " + Integer.toHexString(b));
            case 0xDB:
                if (b >= 0xC0 && b <= 0xDF) { boolean cond = r == 0 ? !c.cf : r == 1 ? !c.zf : r == 2 ? !(c.cf || c.zf) : !c.pf; if (cond) set(0, get(i)); return; }
                if (b == 0xE2) { c0 = c1 = c2 = c3 = false; return; }
                if (b == 0xE3) { fcw = 0x37F; top = 0; c0 = c1 = c2 = c3 = false; return; }
                if ((b >= 0xE8 && b <= 0xEF) || (b >= 0xF0 && b <= 0xF7)) { cmpEflags(get(0), get(i)); return; }
                throw new EmuException("Unsupported x87 DB " + Integer.toHexString(b));
            case 0xDD:
                if (b >= 0xC0 && b <= 0xC7) return; // ffree
                if (b >= 0xD0 && b <= 0xD7) { set(i, get(0)); return; }
                if (b >= 0xD8 && b <= 0xDF) { set(i, get(0)); pop(); return; }
                if (b >= 0xE0 && b <= 0xE7) { cmp(get(0), get(i)); return; }
                if (b >= 0xE8 && b <= 0xEF) { cmp(get(0), get(i)); pop(); return; }
                throw new EmuException("Unsupported x87 DD " + Integer.toHexString(b));
            default: // DF
                if (b == 0xE0) { c.r[Cpu.EAX] = (c.r[Cpu.EAX] & 0xFFFF0000) | fsw(); return; }
                if ((b >= 0xE8 && b <= 0xEF) || (b >= 0xF0 && b <= 0xF7)) { cmpEflags(get(0), get(i)); pop(); return; }
                throw new EmuException("Unsupported x87 DF " + Integer.toHexString(b));
        }
    }

    private void store80(int a, double v) {
        long bits = Double.doubleToRawLongBits(v);
        long sign = bits >>> 63;
        int e = (int) ((bits >>> 52) & 0x7FF);
        long m = bits & 0xFFFFFFFFFFFFFL;
        long mant;
        int exp;
        if (e == 0 && m == 0) { mant = 0; exp = 0; }
        else if (e == 0x7FF) { exp = 0x7FFF; mant = 0x8000000000000000L | (m << 11); }
        else if (e == 0) { exp = 0; mant = m << 11; }
        else { exp = e - 1023 + 16383; mant = 0x8000000000000000L | (m << 11); }
        c.mem.w64(a, mant);
        c.mem.w16(a + 8, (int) (sign << 15) | exp);
    }

    private double load80(int a) {
        long mant = c.mem.r64(a);
        int se = c.mem.r16(a + 8);
        int exp = se & 0x7FFF;
        boolean neg = (se & 0x8000) != 0;
        double v;
        if (exp == 0 && mant == 0) v = 0;
        else if (exp == 0x7FFF) v = (mant << 1) == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        else v = Math.scalb((double) (mant >>> 11), exp - 16383 - 52);
        return neg ? -v : v;
    }
}
