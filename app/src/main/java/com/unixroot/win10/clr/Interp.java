package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;

/** CIL interpreter. */
public final class Interp {
    public static final class Stop extends RuntimeException { Stop() { super(null, null, false, false); } }
    public static final class Exit extends RuntimeException { public final int code; public Exit(int c) { super(null, null, false, false); code = c; } }
    private static final Object FINALLY_DONE = new Object();

    private final Vm vm;
    public Interp(Vm vm) { this.vm = vm; }

    static final class Frame {
        ClrMethod m; Object[] args, locals, st; int sp, pc; Object curEx; byte[] code; Image img;
    }

    /** Reference to an element of an Object[] (locals, args, fields, statics, object arrays). */
    static final class ArrRef extends Ref {
        final Object[] a; final int i;
        ArrRef(Object[] a, int i) { this.a = a; this.i = i; }
        @Override public Object get() { return a[i]; }
        @Override public void set(Object v) { a[i] = v; }
    }
    /** Reference to an element of any Java array (typed arrays). */
    static final class ElemRef extends Ref {
        final Object arr; final int i;
        ElemRef(Object arr, int i) { this.arr = arr; this.i = i; }
        @Override public Object get() { return arrGet(arr, i, false); }
        @Override public void set(Object v) { arrSet(arr, i, v); }
    }
    static final class ValRef extends Ref {
        Object v;
        ValRef(Object v) { this.v = v; }
        @Override public Object get() { return v; }
        @Override public void set(Object x) { v = x; }
    }

    // ------------------------------------------------------------ arrays
    public static Object arrGet(Object arr, int i, boolean unsigned) {
        if (arr instanceof Object[]) return ((Object[]) arr)[i];
        if (arr instanceof int[]) return ((int[]) arr)[i];
        if (arr instanceof byte[]) return unsigned ? ((byte[]) arr)[i] & 0xFF : (int) ((byte[]) arr)[i];
        if (arr instanceof char[]) return (int) ((char[]) arr)[i];
        if (arr instanceof short[]) return unsigned ? ((short[]) arr)[i] & 0xFFFF : (int) ((short[]) arr)[i];
        if (arr instanceof long[]) return ((long[]) arr)[i];
        if (arr instanceof double[]) return ((double[]) arr)[i];
        if (arr instanceof float[]) return (double) ((float[]) arr)[i];
        if (arr instanceof boolean[]) return ((boolean[]) arr)[i] ? 1 : 0;
        throw new IllegalStateException("not an array");
    }

    public static void arrSet(Object arr, int i, Object v) {
        if (arr instanceof Object[]) { ((Object[]) arr)[i] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v; return; }
        if (arr instanceof int[]) { ((int[]) arr)[i] = ((Number) v).intValue(); return; }
        if (arr instanceof byte[]) { ((byte[]) arr)[i] = (byte) ((Number) v).intValue(); return; }
        if (arr instanceof char[]) { ((char[]) arr)[i] = v instanceof Character ? (Character) v : (char) ((Number) v).intValue(); return; }
        if (arr instanceof short[]) { ((short[]) arr)[i] = (short) ((Number) v).intValue(); return; }
        if (arr instanceof long[]) { ((long[]) arr)[i] = ((Number) v).longValue(); return; }
        if (arr instanceof double[]) { ((double[]) arr)[i] = ((Number) v).doubleValue(); return; }
        if (arr instanceof float[]) { ((float[]) arr)[i] = ((Number) v).floatValue(); return; }
        if (arr instanceof boolean[]) { ((boolean[]) arr)[i] = ((Number) v).intValue() != 0; return; }
        throw new IllegalStateException("not an array");
    }

    public static int arrLen(Object arr) { return java.lang.reflect.Array.getLength(arr); }

    Object newArray(ClrType et, int n) {
        if (n < 0) throw vm.makeThrow("System.OverflowException", "Arithmetic operation resulted in an overflow.");
        String f = et.full;
        if (et.isNative) {
            switch (f) {
                case "System.Byte": case "System.SByte": return new byte[n];
                case "System.Boolean": return new byte[n];
                case "System.Char": return new char[n];
                case "System.Int16": case "System.UInt16": return new short[n];
                case "System.Int32": case "System.UInt32": case "System.IntPtr": case "System.UIntPtr": return new int[n];
                case "System.Int64": case "System.UInt64": return new long[n];
                case "System.Single": return new float[n];
                case "System.Double": return new double[n];
            }
            Object[] r = new Object[n];
            Object d = Bcl.nativeDefault(f);
            if (d != null) java.util.Arrays.fill(r, d);
            return r;
        }
        if (et.isEnum) return new int[n];
        Object[] r = new Object[n];
        if (et.valueType) for (int i = 0; i < n; i++) r[i] = vm.newObject(et);
        return r;
    }

    // ------------------------------------------------------------ method preparation
    private void prepare(ClrMethod m) {
        if (m.code != null) return;
        Metadata md = m.img.md;
        if (m.rva == 0) throw vm.makeThrow("System.MissingMethodException", "No body for " + m);
        byte[] img = md.img;
        int p = md.methodBodyOffset(m.rva);
        int b0 = img[p] & 0xFF;
        int codeSize;
        int localTok = 0;
        boolean more = false;
        if ((b0 & 3) == 2) {
            codeSize = b0 >> 2; m.maxStack = 8; p += 1;
        } else {
            int flags = (b0 | (img[p + 1] & 0xFF) << 8) & 0xFFF;
            int hdr = (img[p + 1] & 0xFF) >> 4;
            m.maxStack = (img[p + 2] & 0xFF) | (img[p + 3] & 0xFF) << 8;
            codeSize = (img[p + 4] & 0xFF) | (img[p + 5] & 0xFF) << 8 | (img[p + 6] & 0xFF) << 16 | (img[p + 7] & 0xFF) << 24;
            localTok = (img[p + 8] & 0xFF) | (img[p + 9] & 0xFF) << 8 | (img[p + 10] & 0xFF) << 16 | (img[p + 11] & 0xFF) << 24;
            m.initLocals = (flags & 0x10) != 0;
            more = (flags & 0x8) != 0;
            p += hdr * 4;
        }
        byte[] code = new byte[codeSize];
        System.arraycopy(img, p, code, 0, codeSize);
        int q = p + codeSize;
        java.util.ArrayList<int[]> cl = new java.util.ArrayList<>();
        while (more) {
            q = (q + 3) & ~3;
            int kind = img[q] & 0xFF;
            boolean fat = (kind & 0x40) != 0;
            int size = fat ? ((img[q + 1] & 0xFF) | (img[q + 2] & 0xFF) << 8 | (img[q + 3] & 0xFF) << 16) : (img[q + 1] & 0xFF);
            if ((kind & 1) != 0) {
                int n = fat ? (size - 4) / 24 : (size - 4) / 12;
                int o = q + 4;
                for (int i = 0; i < n; i++) {
                    if (fat) {
                        cl.add(new int[]{i32(img, o), i32(img, o + 4), i32(img, o + 8), i32(img, o + 12), i32(img, o + 16), i32(img, o + 20)});
                        o += 24;
                    } else {
                        cl.add(new int[]{u16(img, o), u16(img, o + 2), img[o + 4] & 0xFF, u16(img, o + 5), img[o + 7] & 0xFF, i32(img, o + 8)});
                        o += 12;
                    }
                }
            }
            more = (kind & 0x80) != 0;
            q += size;
        }
        m.clauses = cl.toArray(new int[0][]);
        if (localTok != 0) {
            byte[] sig = md.blob(md.row(Metadata.STANDALONESIG, localTok & 0xFFFFFF)[0]);
            Vm.Sig s = new Vm.Sig(sig, 0);
            s.next();
            int n = s.u();
            m.nlocals = n;
            m.localDefaults = new Object[n];
            for (int i = 0; i < n; i++) {
                int pos = s.p;
                m.localDefaults[i] = Vm.defaultOf(m.img, sig, pos);
                if (s.peek() == 0x45) s.next();
                vm.readType(m.img, s);
            }
        } else m.localDefaults = new Object[0];
        m.code = code;
    }

    private static int i32(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24; }
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }

    // ------------------------------------------------------------ execution
    private final ThreadLocal<int[]> depthTl = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[1]; } };

    public Object exec(ClrMethod m, Object[] args) {
        prepare(m);
        Frame f = new Frame();
        f.m = m; f.img = m.img; f.code = m.code;
        f.args = args;
        f.locals = new Object[m.nlocals];
        for (int i = 0; i < m.nlocals; i++) {
            Object d = m.localDefaults[i];
            f.locals[i] = d instanceof ClrObject ? ((ClrObject) d).copy() : d;
        }
        f.st = new Object[m.maxStack + 4];
        final int[] depth = depthTl.get();
        if (++depth[0] > 600) { depth[0] = 0; throw vm.makeThrow("System.StackOverflowException", "Stack overflow"); }
        try {
            return runProtected(f, 0);
        } catch (ClrThrow t) {
            if (t.frames.size() < 12) t.frames.add(m.owner.full + "::" + m.name);
            throw t;
        } finally { depth[0]--; }
    }

    private ClrThrow toThrow(Throwable t) {
        if (t instanceof ClrThrow) return (ClrThrow) t;
        if (t instanceof ArithmeticException) return vm.makeThrow("System.DivideByZeroException", "Attempted to divide by zero.");
        if (t instanceof NullPointerException) {
            ClrThrow ct = vm.makeThrow("System.NullReferenceException", "Object reference not set to an instance of an object.");
            for (StackTraceElement e : t.getStackTrace()) if (e.getClassName().startsWith("com.unixroot.win10.clr") && !e.getClassName().contains("Interp")) { ct.frames.add("[native] " + e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber()); break; }
            return ct;
        }
        if (t instanceof ArrayIndexOutOfBoundsException) return vm.makeThrow("System.IndexOutOfRangeException", "Index was outside the bounds of the array.");
        if (t instanceof ClassCastException) return vm.makeThrow("System.InvalidCastException", "Specified cast is not valid.");
        if (t instanceof NegativeArraySizeException) return vm.makeThrow("System.OverflowException", "Arithmetic operation resulted in an overflow.");
        if (t instanceof StackOverflowError) return vm.makeThrow("System.StackOverflowException", "Stack overflow");
        if (t instanceof OutOfMemoryError) return vm.makeThrow("System.OutOfMemoryException", "Out of memory");
        if (t instanceof java.io.IOException) return vm.makeThrow("System.IO.IOException", String.valueOf(t.getMessage()));
        if (t instanceof NumberFormatException) return vm.makeThrow("System.FormatException", "Input string was not in a correct format.");
        if (t instanceof IndexOutOfBoundsException) return vm.makeThrow("System.ArgumentOutOfRangeException", String.valueOf(t.getMessage()));
        if (t instanceof UnsupportedOperationException || t instanceof IllegalStateException) return vm.makeThrow("System.NotSupportedException", String.valueOf(t.getMessage()));
        return vm.makeThrow("System.Exception", t.toString());
    }

    private Object runProtected(Frame f, int startPc) {
        int pc = startPc;
        while (true) {
            try {
                return loop(f, pc, false);
            } catch (Stop | Exit s) {
                throw s;
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                ClrThrow t = toThrow(e);
                int handler = dispatch(f, t);
                if (handler < 0) throw t;
                pc = handler;
                f.sp = 0;
                f.st[f.sp++] = t.ex;
                f.curEx = t.ex;
            }
        }
    }

    private boolean covers(int[] c, int pc) { return pc >= c[1] && pc < c[1] + c[2]; }

    /** Finds a catch handler for the exception thrown at f.pc, running finally/fault blocks on the way. Returns handler pc or -1. */
    private int dispatch(Frame f, ClrThrow t) {
        int[][] cl = f.m.clauses;
        int pc = f.pc;
        for (int[] c : cl) {
            if (!covers(c, pc)) continue;
            if (c[0] == 0) {
                ClrType ct = vm.typeOfCoded(f.img, tokenToCoded(f.img, c[5]));
                if (Bcl.isInst(vm, t.ex, ct)) return c[3];
            } else if (c[0] == 1) {
                f.sp = 0; f.st[f.sp++] = t.ex;
                Object r = loop(f, c[5], true);
                if (r instanceof Integer && (Integer) r != 0) return c[3];
            } else {
                f.sp = 0;
                try { loop(f, c[3], true); } catch (ClrThrow inner) { throw inner; }
            }
        }
        return -1;
    }

    private static int tokenToCoded(Image img, int token) {
        // TypeDefOrRef coded index from a metadata token
        int tab = token >>> 24, row = token & 0xFFFFFF;
        int tag = tab == Metadata.TYPEDEF ? 0 : tab == Metadata.TYPEREF ? 1 : 2;
        return row << 2 | tag;
    }

    private void runFinallies(Frame f, int from, int target) {
        for (int[] c : f.m.clauses) {
            if (c[0] != 2) continue;
            boolean in = covers(c, from);
            if (!in) for (int[] o : f.m.clauses) if (o != c && o[0] != 2 && o[1] == c[1] && o[2] == c[2] && from >= o[3] && from < o[3] + o[4]) in = true;
            if (!in) continue;
            if (target >= c[1] && target < c[1] + c[2]) continue;
            int sp = f.sp;
            f.sp = 0;
            loop(f, c[3], true);
            f.sp = sp;
        }
    }

    // ------------------------------------------------------------ main loop
    private Object loop(Frame f, int pc, boolean sub) {
        final byte[] code = f.code;
        Object[] st = f.st;
        int sp = f.sp;
        int tick = 0;
        while (true) {
            f.pc = pc; f.sp = sp;
            int op = code[pc++] & 0xFF;
            switch (op) {
                case 0x00: case 0x01: break;
                case 0x02: st[sp++] = f.args[0]; break;
                case 0x03: st[sp++] = f.args[1]; break;
                case 0x04: st[sp++] = f.args[2]; break;
                case 0x05: st[sp++] = f.args[3]; break;
                case 0x06: st[sp++] = fix(f.locals[0]); break;
                case 0x07: st[sp++] = fix(f.locals[1]); break;
                case 0x08: st[sp++] = fix(f.locals[2]); break;
                case 0x09: st[sp++] = fix(f.locals[3]); break;
                case 0x0A: f.locals[0] = store(st[--sp]); break;
                case 0x0B: f.locals[1] = store(st[--sp]); break;
                case 0x0C: f.locals[2] = store(st[--sp]); break;
                case 0x0D: f.locals[3] = store(st[--sp]); break;
                case 0x0E: st[sp++] = fix(f.args[code[pc++] & 0xFF]); break;
                case 0x0F: { int i = code[pc++] & 0xFF; st[sp++] = addr(f.args, i); break; }
                case 0x10: f.args[code[pc++] & 0xFF] = store(st[--sp]); break;
                case 0x11: st[sp++] = fix(f.locals[code[pc++] & 0xFF]); break;
                case 0x12: { int i = code[pc++] & 0xFF; st[sp++] = addr(f.locals, i); break; }
                case 0x13: f.locals[code[pc++] & 0xFF] = store(st[--sp]); break;
                case 0x14: st[sp++] = null; break;
                case 0x15: st[sp++] = -1; break;
                case 0x16: case 0x17: case 0x18: case 0x19: case 0x1A: case 0x1B: case 0x1C: case 0x1D: case 0x1E: st[sp++] = op - 0x16; break;
                case 0x1F: st[sp++] = (int) code[pc++]; break;
                case 0x20: st[sp++] = i32(code, pc); pc += 4; break;
                case 0x21: st[sp++] = (long) i32(code, pc) & 0xFFFFFFFFL | (long) i32(code, pc + 4) << 32; pc += 8; break;
                case 0x22: st[sp++] = (double) Float.intBitsToFloat(i32(code, pc)); pc += 4; break;
                case 0x23: st[sp++] = Double.longBitsToDouble((long) i32(code, pc) & 0xFFFFFFFFL | (long) i32(code, pc + 4) << 32); pc += 8; break;
                case 0x25: st[sp] = st[sp - 1]; sp++; break;
                case 0x26: sp--; break;
                case 0x27: throw new UnsupportedOperationException("jmp");
                case 0x28: case 0x6F: case 0x73: {
                    int tok = i32(code, pc); pc += 4;
                    f.sp = sp;
                    sp = doCall(f, op, tok, st, sp);
                    break;
                }
                case 0x29: throw new UnsupportedOperationException("calli");
                case 0x2A: {
                    if (f.m.retVoid) return null;
                    Object r = st[--sp];
                    return r instanceof ClrObject && ((ClrObject) r).type.valueType ? ((ClrObject) r).copy() : r;
                }
                case 0x2B: pc += code[pc] + 1; break;
                case 0x2C: { int v = code[pc++]; if (!truth(st[--sp])) pc += v; break; }
                case 0x2D: { int v = code[pc++]; if (truth(st[--sp])) pc += v; break; }
                case 0x2E: case 0x2F: case 0x30: case 0x31: case 0x32: case 0x33: case 0x34: case 0x35: case 0x36: case 0x37: {
                    int v = code[pc++];
                    Object b = st[--sp], a = st[--sp];
                    if (branch(op - 0x2E, a, b)) { pc += v; }
                    break;
                }
                case 0x38: { int v = i32(code, pc); pc += 4 + v; break; }
                case 0x39: { int v = i32(code, pc); pc += 4; if (!truth(st[--sp])) pc += v; break; }
                case 0x3A: { int v = i32(code, pc); pc += 4; if (truth(st[--sp])) pc += v; break; }
                case 0x3B: case 0x3C: case 0x3D: case 0x3E: case 0x3F: case 0x40: case 0x41: case 0x42: case 0x43: case 0x44: {
                    int v = i32(code, pc); pc += 4;
                    Object b = st[--sp], a = st[--sp];
                    if (branch(op - 0x3B, a, b)) pc += v;
                    break;
                }
                case 0x45: {
                    int n = i32(code, pc); pc += 4;
                    int v = (Integer) st[--sp];
                    int base = pc + n * 4;
                    if (v >= 0 && v < n) pc = base + i32(code, pc + v * 4); else pc = base;
                    break;
                }
                case 0x46: st[sp - 1] = (int) ((Number) deref(st[sp - 1])).byteValue(); break;
                case 0x47: st[sp - 1] = ((Number) deref(st[sp - 1])).intValue() & 0xFF; break;
                case 0x48: st[sp - 1] = (int) ((Number) deref(st[sp - 1])).shortValue(); break;
                case 0x49: st[sp - 1] = ((Number) deref(st[sp - 1])).intValue() & 0xFFFF; break;
                case 0x4A: case 0x4B: case 0x4D: st[sp - 1] = ((Number) deref(st[sp - 1])).intValue(); break;
                case 0x4C: st[sp - 1] = ((Number) deref(st[sp - 1])).longValue(); break;
                case 0x4E: case 0x4F: st[sp - 1] = ((Number) deref(st[sp - 1])).doubleValue(); break;
                case 0x50: st[sp - 1] = deref(st[sp - 1]); break;
                case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56: case 0x57: case 0xDF: {
                    Object v = st[--sp];
                    Object a = st[--sp];
                    if (a instanceof Ref) ((Ref) a).set(narrowStore(op, v)); else throw new IllegalStateException("stind target");
                    break;
                }
                case 0x58: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(0, a, b); break; }
                case 0x59: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(1, a, b); break; }
                case 0x5A: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(2, a, b); break; }
                case 0x5B: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(3, a, b); break; }
                case 0x5C: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(4, a, b); break; }
                case 0x5D: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(5, a, b); break; }
                case 0x5E: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(6, a, b); break; }
                case 0x5F: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(7, a, b); break; }
                case 0x60: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(8, a, b); break; }
                case 0x61: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = arith(9, a, b); break; }
                case 0x62: { int n = (Integer) st[--sp]; Object a = st[sp - 1]; st[sp - 1] = a instanceof Long ? (Object) ((Long) a << n) : (Object) ((Integer) a << n); break; }
                case 0x63: { int n = (Integer) st[--sp]; Object a = st[sp - 1]; st[sp - 1] = a instanceof Long ? (Object) ((Long) a >> n) : (Object) ((Integer) a >> n); break; }
                case 0x64: { int n = (Integer) st[--sp]; Object a = st[sp - 1]; st[sp - 1] = a instanceof Long ? (Object) ((Long) a >>> n) : (Object) ((Integer) a >>> n); break; }
                case 0x65: { Object a = st[sp - 1]; st[sp - 1] = a instanceof Integer ? (Object) (-(Integer) a) : a instanceof Long ? (Object) (-(Long) a) : (Object) (-(Double) a); break; }
                case 0x66: { Object a = st[sp - 1]; st[sp - 1] = a instanceof Integer ? (Object) (~(Integer) a) : (Object) (~(Long) a); break; }
                case 0x67: st[sp - 1] = (int) (byte) toInt(st[sp - 1]); break;
                case 0x68: st[sp - 1] = (int) (short) toInt(st[sp - 1]); break;
                case 0x69: case 0x6D: case 0xD3: case 0xE0: case 0xD4: case 0xD5: st[sp - 1] = toInt(st[sp - 1]); break;
                case 0x6A: { Object a = st[sp - 1]; st[sp - 1] = a instanceof Double ? (Object) (long) (double) (Double) a : (Object) ((Number) a).longValue(); break; }
                case 0x6E: { Object a = st[sp - 1]; st[sp - 1] = a instanceof Integer ? (Object) ((long) (Integer) a & 0xFFFFFFFFL) : a instanceof Double ? (Object) (long) (double) (Double) a : a; break; }
                case 0x6B: st[sp - 1] = (double) (float) ((Number) st[sp - 1]).doubleValue(); break;
                case 0x6C: st[sp - 1] = ((Number) st[sp - 1]).doubleValue(); break;
                case 0x76: { Object a = st[sp - 1]; st[sp - 1] = a instanceof Integer ? (double) ((long) (Integer) a & 0xFFFFFFFFL) : a instanceof Long ? unsignedToDouble((Long) a) : a; break; }
                case 0x70: throw new UnsupportedOperationException("cpobj");
                case 0x71: { Object a = st[--sp]; Object v = deref(a); st[sp++] = v instanceof ClrObject ? ((ClrObject) v).copy() : v; pc += 4; break; }
                case 0x72: { int tok = i32(code, pc); pc += 4; Object s = f.m.cache.get(tok); if (s == null) { s = f.img.md.userString(tok & 0xFFFFFF); f.m.cache.put(tok, s); } st[sp++] = s; break; }
                case 0x74: case 0x75: {
                    int tok = i32(code, pc); pc += 4;
                    ClrType t = typeTok(f, tok);
                    Object o = st[sp - 1];
                    boolean ok = o == null || Bcl.isInst(vm, o, t);
                    if (op == 0x74) { if (!ok) throw vm.makeThrow("System.InvalidCastException", "Unable to cast object to type '" + t.full + "'."); }
                    else if (!ok || o == null) st[sp - 1] = null;
                    break;
                }
                case 0x79: { int tok = i32(code, pc); pc += 4; Object o = st[sp - 1]; st[sp - 1] = unboxAddr(o); break; }
                case 0x7A: { Object o = st[--sp]; if (o == null) throw vm.makeThrow("System.NullReferenceException", "Object reference not set to an instance of an object."); throw new ClrThrow(o); }
                case 0x7B: case 0x7C: {
                    int tok = i32(code, pc); pc += 4;
                    FieldInfo fi = fieldTok(f, tok);
                    Object o = deref(st[sp - 1]);
                    if (fi.slot < 0 && !fi.isStatic || o instanceof ClrObject == false) {
                        if (o == null) throw vm.makeThrow("System.NullReferenceException", "Object reference not set to an instance of an object.");
                        st[sp - 1] = vm.bcl.getInstanceField(o, fi);
                        break;
                    }
                    ClrObject co = (ClrObject) o;
                    if (op == 0x7B) { Object v = co.f[fi.slot]; st[sp - 1] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v; }
                    else { Object v = co.f[fi.slot]; st[sp - 1] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? v : new ArrRef(co.f, fi.slot); }
                    break;
                }
                case 0x7D: {
                    int tok = i32(code, pc); pc += 4;
                    FieldInfo fi = fieldTok(f, tok);
                    Object v = st[--sp];
                    Object o = deref(st[--sp]);
                    if (o == null) throw vm.makeThrow("System.NullReferenceException", "Object reference not set to an instance of an object.");
                    if (o instanceof ClrObject && fi.slot >= 0) ((ClrObject) o).f[fi.slot] = store(v); else vm.bcl.setInstanceField(o, fi, v);
                    break;
                }
                case 0x7E: case 0x7F: {
                    int tok = i32(code, pc); pc += 4;
                    FieldInfo fi = fieldTok(f, tok);
                    if (fi.owner.isNative || fi.slot < 0) { st[sp++] = vm.bcl.getStatic(fi); break; }
                    vm.ensureInit(fi.owner);
                    Object v = fi.owner.statics[fi.slot];
                    if (op == 0x7E) st[sp++] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v;
                    else st[sp++] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? v : new ArrRef(fi.owner.statics, fi.slot);
                    break;
                }
                case 0x80: {
                    int tok = i32(code, pc); pc += 4;
                    FieldInfo fi = fieldTok(f, tok);
                    Object v = st[--sp];
                    if (fi.owner.isNative || fi.slot < 0) { vm.bcl.setStatic(fi, v); break; }
                    vm.ensureInit(fi.owner);
                    fi.owner.statics[fi.slot] = store(v);
                    break;
                }
                case 0x81: { Object v = st[--sp]; Object a = st[--sp]; if (a instanceof Ref) ((Ref) a).set(store(v)); else if (a instanceof ClrObject && v instanceof ClrObject) { ((ClrObject) a).f = ((ClrObject) v).copy().f; } pc += 4; break; }
                case 0x82: case 0x83: case 0x84: case 0x85: case 0x86: case 0x87: case 0x88: case 0x89: case 0x8A: case 0x8B:
                case 0xB3: case 0xB4: case 0xB5: case 0xB6: case 0xB7: case 0xB8: case 0xB9: case 0xBA: {
                    Object a = st[sp - 1];
                    switch (op) {
                        case 0x82: case 0xB3: st[sp - 1] = (int) (byte) toInt(a); break;
                        case 0x86: case 0xB4: st[sp - 1] = toInt(a) & 0xFF; break;
                        case 0x83: case 0xB5: st[sp - 1] = (int) (short) toInt(a); break;
                        case 0x87: case 0xB6: st[sp - 1] = toInt(a) & 0xFFFF; break;
                        case 0x84: case 0x88: case 0x8A: case 0x8B: case 0xB7: case 0xB8: st[sp - 1] = toInt(a); break;
                        default: st[sp - 1] = a instanceof Integer ? (Object) (long) (Integer) a : a instanceof Double ? (Object) (long) (double) (Double) a : a;
                    }
                    break;
                }
                case 0x8C: { int tok = i32(code, pc); pc += 4; st[sp - 1] = box(f, tok, st[sp - 1]); break; }
                case 0x8D: { int tok = i32(code, pc); pc += 4; int n = (Integer) st[sp - 1]; st[sp - 1] = newArray(typeTok(f, tok), n); break; }
                case 0x8E: { Object a = st[sp - 1]; if (a == null) throw new NullPointerException(); st[sp - 1] = arrLen(a); break; }
                case 0x8F: { pc += 4; int i = (Integer) st[--sp]; Object a = st[sp - 1]; if (a instanceof Object[] && ((Object[]) a)[i] instanceof ClrObject && ((ClrObject) ((Object[]) a)[i]).type.valueType) st[sp - 1] = ((Object[]) a)[i]; else { arrLen(a); if (i < 0 || i >= arrLen(a)) throw new ArrayIndexOutOfBoundsException(); st[sp - 1] = a instanceof Object[] ? new ArrRef((Object[]) a, i) : new ElemRef(a, i); } break; }
                case 0x90: case 0x92: case 0x94: case 0x96: case 0x97: case 0x98: case 0x99: case 0x9A: case 0x91: case 0x93: case 0x95: {
                    int i = (Integer) st[--sp]; Object a = st[sp - 1];
                    Object v = arrGet(a, i, op == 0x91 || op == 0x93 || op == 0x95);
                    if (op == 0x95 && v instanceof Integer) v = v; // uint32 kept as int bits
                    if (op == 0x96 || op == 0x97) v = op == 0x97 ? (Object) ((Number) v).intValue() : v;
                    st[sp - 1] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v;
                    break;
                }
                case 0xA3: { int tok = i32(code, pc); pc += 4; int i = (Integer) st[--sp]; Object a = st[sp - 1]; Object v = arrGet(a, i, !typeTok(f, tok).full.equals("System.SByte") && !typeTok(f, tok).full.equals("System.Int16")); st[sp - 1] = v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v; break; }
                case 0x9B: case 0x9C: case 0x9D: case 0x9E: case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA4: {
                    if (op == 0xA4) pc += 4;
                    Object v = st[--sp]; int i = (Integer) st[--sp]; Object a = st[--sp];
                    if (a instanceof Object[] && v != null && op == 0xA2) checkArrayStore((Object[]) a, v);
                    arrSet(a, i, v);
                    break;
                }
                case 0xA5: { int tok = i32(code, pc); pc += 4; st[sp - 1] = unboxAny(f, tok, st[sp - 1]); break; }
                case 0xC3: break;
                case 0xD0: { int tok = i32(code, pc); pc += 4; st[sp++] = ldtoken(f, tok); break; }
                case 0xD1: st[sp - 1] = toInt(st[sp - 1]) & 0xFFFF; break;
                case 0xD2: st[sp - 1] = toInt(st[sp - 1]) & 0xFF; break;
                case 0xD6: case 0xD7: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = ovf(0, op == 0xD7, a, b); break; }
                case 0xD8: case 0xD9: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = ovf(2, op == 0xD9, a, b); break; }
                case 0xDA: case 0xDB: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = ovf(1, op == 0xDB, a, b); break; }
                case 0xDC: f.sp = sp; return FINALLY_DONE;
                case 0xDD: case 0xDE: {
                    int off = op == 0xDE ? code[pc++] : i32(code, pc);
                    if (op == 0xDD) pc += 4;
                    int target = pc + off;
                    f.sp = sp;
                    runFinallies(f, f.pc, target);
                    sp = 0;
                    if (sub) { f.sp = 0; return FINALLY_DONE; }
                    pc = target;
                    break;
                }
                case 0xFE: {
                    int o2 = code[pc++] & 0xFF;
                    switch (o2) {
                        case 0x01: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = cmp(a, b, false) == 0 ? 1 : 0; break; }
                        case 0x02: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = cmp(a, b, false) == 1 ? 1 : 0; break; }
                        case 0x03: { Object b = st[--sp], a = st[sp - 1]; int c = cmp(a, b, true); st[sp - 1] = c == 1 || c == 2 ? 1 : 0; break; }
                        case 0x04: { Object b = st[--sp], a = st[sp - 1]; st[sp - 1] = cmp(a, b, false) == -1 ? 1 : 0; break; }
                        case 0x05: { Object b = st[--sp], a = st[sp - 1]; int c = cmp(a, b, true); st[sp - 1] = c == -1 || c == 2 ? 1 : 0; break; }
                        case 0x06: { int tok = i32(code, pc); pc += 4; st[sp++] = new MethodPtr(vm.resolveMethod(f.img, tok)); break; }
                        case 0x07: { int tok = i32(code, pc); pc += 4; Object o = st[sp - 1]; ClrMethod m = vm.resolveMethod(f.img, tok); st[sp - 1] = new MethodPtr(vm.virtualTarget(m, o)); break; }
                        case 0x09: st[sp++] = fix(f.args[u16(code, pc)]); pc += 2; break;
                        case 0x0A: st[sp++] = addr(f.args, u16(code, pc)); pc += 2; break;
                        case 0x0B: f.args[u16(code, pc)] = store(st[--sp]); pc += 2; break;
                        case 0x0C: st[sp++] = fix(f.locals[u16(code, pc)]); pc += 2; break;
                        case 0x0D: st[sp++] = addr(f.locals, u16(code, pc)); pc += 2; break;
                        case 0x0E: f.locals[u16(code, pc)] = store(st[--sp]); pc += 2; break;
                        case 0x11: { Object r = st[--sp]; f.sp = sp; return r; }
                        case 0x12: pc += 1; break;
                        case 0x13: case 0x14: case 0x1E: break;
                        case 0x15: {
                            int tok = i32(code, pc); pc += 4;
                            Object a = st[--sp];
                            ClrType t = typeTokOrNull(f, tok);
                            if (a instanceof ClrObject) { ClrObject o = (ClrObject) a; ClrObject fresh = vm.newObject(o.type); o.f = fresh.f; }
                            else if (a instanceof Ref) ((Ref) a).set(vm.defaultValue(t));
                            break;
                        }
                        case 0x16: pc += 4; break;
                        case 0x1A: { Object ex = f.curEx; if (ex == null) throw vm.makeThrow("System.InvalidOperationException", "rethrow outside catch"); throw new ClrThrow(ex); }
                        case 0x1C: { int tok = i32(code, pc); pc += 4; st[sp++] = sizeOf(f, tok); break; }
                        default: throw new UnsupportedOperationException("opcode FE " + Integer.toHexString(o2) + " in " + f.m);
                    }
                    break;
                }
                default: throw new UnsupportedOperationException("opcode " + Integer.toHexString(op) + " in " + f.m);
            }
            if ((++tick & 0xFFFF) == 0 && vm.stopped) throw new Stop();
        }
    }

    // ------------------------------------------------------------ helpers
    private static Object fix(Object v) { return v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v; }
    private static Object store(Object v) { return v instanceof ClrObject && ((ClrObject) v).type.valueType ? ((ClrObject) v).copy() : v; }
    private static Object addr(Object[] arr, int i) { Object v = arr[i]; return v instanceof ClrObject && ((ClrObject) v).type.valueType ? v : new ArrRef(arr, i); }
    static Object deref(Object o) { return o instanceof Ref ? ((Ref) o).get() : o; }
    private static boolean truth(Object o) { return o instanceof Integer ? (Integer) o != 0 : o instanceof Long ? (Long) o != 0 : o != null && !(o instanceof Boolean && !(Boolean) o); }
    private static int i32(byte[] b, int o, int dummy) { return 0; }
    private static int toInt(Object o) { return o instanceof Integer ? (Integer) o : o instanceof Long ? (int) (long) (Long) o : o instanceof Double ? d2i((Double) o) : o instanceof Character ? (Character) o : o instanceof Boolean ? ((Boolean) o ? 1 : 0) : ((Number) o).intValue(); }
    private static int d2i(double d) { return (int) d; }
    private static double unsignedToDouble(long l) { return l >= 0 ? (double) l : (double) (l >>> 1 | l & 1) * 2.0; }

    private static Object narrowStore(int op, Object v) {
        switch (op) {
            case 0x52: return (int) (byte) toInt(v);
            case 0x53: return (int) (short) toInt(v);
            case 0x54: case 0xDF: return toInt(v);
            case 0x55: return v instanceof Long ? v : (Object) ((Number) v).longValue();
            case 0x56: return (double) (float) ((Number) v).doubleValue();
            case 0x57: return ((Number) v).doubleValue();
        }
        return v;
    }

    private static void checkArrayStore(Object[] a, Object v) { }

    /** cmp result: -1 less, 0 equal, 1 greater, 2 unordered (NaN). Unsigned applies to integers. */
    static int cmp(Object a, Object b, boolean un) {
        if (a instanceof Integer && b instanceof Integer) { int x = (Integer) a, y = (Integer) b; return un ? Integer.compareUnsigned(x, y) < 0 ? -1 : x == y ? 0 : 1 : x < y ? -1 : x == y ? 0 : 1; }
        if (a instanceof Double || b instanceof Double) {
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            if (Double.isNaN(x) || Double.isNaN(y)) return 2;
            return x < y ? -1 : x == y ? 0 : 1;
        }
        if ((a instanceof Long || a instanceof Integer) && (b instanceof Long || b instanceof Integer)) {
            long x = ((Number) a).longValue(), y = ((Number) b).longValue();
            if (a instanceof Integer && un) x &= 0xFFFFFFFFL;
            if (b instanceof Integer && un) y &= 0xFFFFFFFFL;
            return un ? Long.compareUnsigned(x, y) < 0 ? -1 : x == y ? 0 : 1 : x < y ? -1 : x == y ? 0 : 1;
        }
        if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
        if (a instanceof Ref && b instanceof Ref) return a == b ? 0 : 1;
        return a == b || a.equals(b) && !(a instanceof ClrObject) ? 0 : 1;
    }

    private static boolean branch(int kind, Object a, Object b) {
        // kinds: 0 beq,1 bge,2 bgt,3 ble,4 blt,5 bne.un,6 bge.un,7 bgt.un,8 ble.un,9 blt.un
        boolean un = kind >= 5;
        int c = cmp(a, b, un && kind != 5);
        if (c == 2) return kind == 5 || kind >= 6; // unordered: true for .un variants
        switch (kind) {
            case 0: return c == 0;
            case 1: case 6: return c >= 0;
            case 2: case 7: return c > 0;
            case 3: case 8: return c <= 0;
            case 4: case 9: return c < 0;
            case 5: return c != 0;
        }
        return false;
    }

    private Object arith(int k, Object a, Object b) {
        if (a instanceof Ref || b instanceof Ref) { a = a instanceof Ref ? ((Ref) a).get() : a; b = b instanceof Ref ? ((Ref) b).get() : b; }
        if (a instanceof Integer && b instanceof Integer) {
            int x = (Integer) a, y = (Integer) b;
            switch (k) {
                case 0: return x + y; case 1: return x - y; case 2: return x * y;
                case 3: return x / y; case 4: return Integer.divideUnsigned(x, y);
                case 5: return x % y; case 6: return Integer.remainderUnsigned(x, y);
                case 7: return x & y; case 8: return x | y; case 9: return x ^ y;
            }
        }
        if (a instanceof Double || b instanceof Double) {
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            switch (k) { case 0: return x + y; case 1: return x - y; case 2: return x * y; case 3: return x / y; case 5: return x % y; }
        }
        long x = ((Number) a).longValue(), y = ((Number) b).longValue();
        boolean intResult = a instanceof Integer && b instanceof Integer;
        switch (k) {
            case 0: return x + y; case 1: return x - y; case 2: return x * y;
            case 3: return x / y; case 4: return Long.divideUnsigned(x, y);
            case 5: return x % y; case 6: return Long.remainderUnsigned(x, y);
            case 7: return x & y; case 8: return x | y; case 9: return x ^ y;
        }
        throw new IllegalStateException("arith");
    }

    private Object ovf(int k, boolean un, Object a, Object b) {
        if (a instanceof Integer && b instanceof Integer) {
            long x = un ? (Integer) a & 0xFFFFFFFFL : (Integer) a, y = un ? (Integer) b & 0xFFFFFFFFL : (Integer) b;
            long r = k == 0 ? x + y : k == 1 ? x - y : x * y;
            if (un ? (r < 0 || r > 0xFFFFFFFFL) : (r < Integer.MIN_VALUE || r > Integer.MAX_VALUE)) throw vm.makeThrow("System.OverflowException", "Arithmetic operation resulted in an overflow.");
            return (int) r;
        }
        if (a instanceof Double || b instanceof Double) return arith(k, a, b);
        long x = ((Number) a).longValue(), y = ((Number) b).longValue();
        try {
            return k == 0 ? Math.addExact(x, y) : k == 1 ? Math.subtractExact(x, y) : Math.multiplyExact(x, y);
        } catch (ArithmeticException e) { throw vm.makeThrow("System.OverflowException", "Arithmetic operation resulted in an overflow."); }
    }

    private Object unboxAddr(Object o) {
        if (o instanceof ClrObject) return o;
        return new ValRef(unboxValue(o));
    }

    static Object unboxValue(Object o) {
        if (o instanceof Boxed) return ((Boxed) o).v;
        if (o instanceof Character) return (int) (Character) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof Float) return (double) (Float) o;
        if (o instanceof Byte || o instanceof Short) return ((Number) o).intValue();
        return o;
    }

    private Object unboxAny(Frame f, int tok, Object o) {
        ClrType t = typeTokOrNull(f, tok);
        if (o == null) {
            if (t != null && t.valueType) throw vm.makeThrow("System.NullReferenceException", "Object reference not set to an instance of an object.");
            return null;
        }
        if (t != null && !t.valueType) return o;
        if (o instanceof ClrObject) return ((ClrObject) o).copy();
        return unboxValue(o);
    }

    private Object box(Frame f, int tok, Object v) {
        ClrType t = typeTokOrNull(f, tok);
        if (t == null || !t.valueType) return v;
        if (v instanceof ClrObject) return ((ClrObject) v).copy();
        if (t.isNative) {
            switch (t.full) {
                case "System.Boolean": return ((Number) v).intValue() != 0;
                case "System.Char": return (char) ((Number) v).intValue();
                case "System.Single": return (float) ((Number) v).doubleValue();
                case "System.Int64": case "System.UInt64": return ((Number) v).longValue();
                case "System.Double": return ((Number) v).doubleValue();
            }
            return v;
        }
        if (t.isEnum) return new Boxed(t, v);
        return v;
    }

    private Object ldtoken(Frame f, int tok) {
        int tab = tok >>> 24;
        if (tab == Metadata.TYPEDEF || tab == Metadata.TYPEREF || tab == Metadata.TYPESPEC) return typeTok(f, tok);
        if (tab == Metadata.FIELD || tab == Metadata.MEMBERREF) {
            try { return vm.resolveField(f.img, tok); } catch (RuntimeException e) { return vm.resolveMethod(f.img, tok); }
        }
        return vm.resolveMethod(f.img, tok);
    }

    private int sizeOf(Frame f, int tok) {
        ClrType t = typeTokOrNull(f, tok);
        if (t == null) return 4;
        switch (t.full) { case "System.Byte": case "System.SByte": case "System.Boolean": return 1; case "System.Int16": case "System.UInt16": case "System.Char": return 2; case "System.Int64": case "System.UInt64": case "System.Double": return 8; }
        return 4;
    }

    private ClrType typeTok(Frame f, int tok) { ClrType t = typeTokOrNull(f, tok); return t == null ? vm.nativeType("System.Object") : t; }

    private ClrType typeTokOrNull(Frame f, int tok) {
        Object c = f.m.cache.get(tok);
        if (c instanceof ClrType) return (ClrType) c;
        ClrType t = vm.typeOfCoded(f.img, tokenToCoded(f.img, tok));
        if (tok >>> 24 == Metadata.TYPESPEC) {
            byte[] b = f.img.md.blob(f.img.md.row(Metadata.TYPESPEC, tok & 0xFFFFFF)[0]);
            if ((b[0] & 0xFF) == 0x13 || (b[0] & 0xFF) == 0x1E) t = null; // generic variable: erased
        }
        if (t != null) f.m.cache.put(tok, t);
        return t;
    }

    private FieldInfo fieldTok(Frame f, int tok) {
        Object c = f.m.cache.get(tok);
        if (c != null) return (FieldInfo) c;
        FieldInfo fi = vm.resolveField(f.img, tok);
        f.m.cache.put(tok, fi);
        return fi;
    }

    // ------------------------------------------------------------ calls
    private int doCall(Frame f, int op, int tok, Object[] st, int sp) {
        ClrMethod m = (ClrMethod) f.m.cache.get(tok);
        if (m == null) { m = vm.resolveMethod(f.img, tok); f.m.cache.put(tok, m); }
        int n = m.nparams;
        if (op == 0x73) { // newobj
            Object[] a = new Object[n];
            for (int i = n - 1; i >= 0; i--) a[i] = st[--sp];
            st[sp++] = newObj(m, a);
            return sp;
        }
        int total = n + (m.hasThis ? 1 : 0);
        Object[] a = new Object[total];
        for (int i = total - 1; i >= 0; i--) a[i] = st[--sp];
        Object r = invokeCall(m, a, op == 0x6F);
        if (!m.retVoid) st[sp++] = r;
        return sp;
    }

    public Object invokeCall(ClrMethod m, Object[] a, boolean virt) {
        if (m.hasThis) {
            Object recv = a[0];
            if (recv instanceof Ref && !(m.owner != null && m.owner.valueType && !m.owner.isNative)) { recv = ((Ref) recv).get(); a[0] = recv; }
            if (recv == null) throw vm.makeThrow("System.NullReferenceException", "Object reference not set to an instance of an object.");
            if (recv instanceof Delegate && m.name.equals("Invoke")) return invokeDelegate((Delegate) recv, java.util.Arrays.copyOfRange(a, 1, a.length));
            if (virt) {
                ClrMethod t = vm.virtualTarget(m, recv);
                if (t != m) m = t;
            }
        } else if (m.owner != null && !m.owner.isNative) vm.ensureInit(m.owner);
        if (m.isNative || m.owner == null || (m.rva == 0 && m.owner.isDelegate)) {
            if (m.owner != null && m.owner.isDelegate && m.name.equals("Invoke") && a[0] instanceof Delegate) return invokeDelegate((Delegate) a[0], java.util.Arrays.copyOfRange(a, 1, a.length));
        }
        return vm.invoke(m, a);
    }

    public Object invokeDelegate(Delegate d, Object[] args) {
        Object r = null;
        for (Object[] t : d.targets) {
            ClrMethod m = (ClrMethod) t[1];
            Object target = t[0];
            Object[] full;
            if (m.isStatic || target == null && !m.hasThis) full = args;
            else { full = new Object[args.length + 1]; full[0] = target; System.arraycopy(args, 0, full, 1, args.length); }
            if (target != null && m.hasThis) m = vm.virtualTarget(m, target);
            r = vm.invoke(m, full);
        }
        return r;
    }

    private Object newObj(ClrMethod ctor, Object[] a) {
        ClrType t = ctor.owner;
        if (t.isDelegate) {
            Object tgt = a[0];
            return new Delegate(t, tgt, ((MethodPtr) a[1]).m);
        }
        if (t.isNative && !Bcl.isObjectBacked(vm, t)) return vm.bcl.construct(ctor, a);
        ClrObject o = vm.newObject(t);
        Object[] full = new Object[a.length + 1];
        full[0] = o; System.arraycopy(a, 0, full, 1, a.length);
        vm.invoke(ctor, full);
        return o;
    }
}
