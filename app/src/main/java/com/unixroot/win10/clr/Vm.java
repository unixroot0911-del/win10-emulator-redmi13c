package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

/** The CLR virtual machine: assembly loading, type/method resolution, invocation. */
public final class Vm {
    public interface Console { void write(String s); int read(); void messageBox(String title, String text); }

    public final Console console;
    public final File exeDir;
    public final HashMap<String, Image> images = new HashMap<>();
    final HashMap<String, ClrType> nativeTypes = new HashMap<>();
    public Image main;
    public final Bcl bcl;
    public final Interp interp;
    public volatile boolean stopped;
    public Object guiHost; // set by WinForms layer
    public java.io.File fsRoot;
    public String cwd = "C:\\";

    public Vm(File exeDir, Console c) {
        this.exeDir = exeDir; console = c;
        bcl = new Bcl(this);
        bcl.exts.add(new BclExt(this));
        interp = new Interp(this);
    }

    // ------------------------------------------------------------ loading
    public Image load(byte[] data, String name) {
        Image im = new Image(this, name, new Metadata(data));
        images.put(name.toLowerCase(), im);
        return im;
    }

    public Image findAssembly(String name) {
        Image im = images.get(name.toLowerCase());
        if (im != null) return im;
        if (images.containsKey("?" + name.toLowerCase())) return null;
        File[] cand = {new File(exeDir, name + ".dll"), new File(exeDir, name + ".exe")};
        for (File f : cand) {
            File g = resolveCI(f);
            if (g != null) {
                try { return load(Files.readAllBytes(g.toPath()), name); } catch (Exception e) { /* try next */ }
            }
        }
        images.put("?" + name.toLowerCase(), null);
        return null;
    }

    private static File resolveCI(File f) {
        if (f.isFile()) return f;
        File[] kids = f.getParentFile() == null ? null : f.getParentFile().listFiles();
        if (kids != null) for (File k : kids) if (k.getName().equalsIgnoreCase(f.getName()) && k.isFile()) return k;
        return null;
    }

    // ------------------------------------------------------------ signatures
    static final class Sig {
        final byte[] b; int p;
        Sig(byte[] b, int p) { this.b = b; this.p = p; }
        int u() {
            int x = b[p] & 0xFF;
            if ((x & 0x80) == 0) { p++; return x; }
            if ((x & 0xC0) == 0x80) { int v = (x & 0x3F) << 8 | b[p + 1] & 0xFF; p += 2; return v; }
            int v = (x & 0x1F) << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | b[p + 3] & 0xFF; p += 4; return v;
        }
        int peek() { return b[p] & 0xFF; }
        int next() { return b[p++] & 0xFF; }
    }

    /** Reads one type from the signature and returns a short readable name. */
    String readType(Image img, Sig s) {
        int t = s.next();
        switch (t) {
            case 0x01: return "Void"; case 0x02: return "Boolean"; case 0x03: return "Char";
            case 0x04: return "SByte"; case 0x05: return "Byte"; case 0x06: return "Int16"; case 0x07: return "UInt16";
            case 0x08: return "Int32"; case 0x09: return "UInt32"; case 0x0A: return "Int64"; case 0x0B: return "UInt64";
            case 0x0C: return "Single"; case 0x0D: return "Double"; case 0x0E: return "String"; case 0x16: return "TypedReference";
            case 0x18: return "IntPtr"; case 0x19: return "UIntPtr"; case 0x1C: return "Object";
            case 0x0F: return readType(img, s) + "*";
            case 0x10: return readType(img, s) + "&";
            case 0x45: return readType(img, s);
            case 0x1D: return readType(img, s) + "[]";
            case 0x11: case 0x12: { ClrType ct = typeOfCoded(img, s.u()); return ct == null ? "?" : ct.name; }
            case 0x13: return "!" + s.u();
            case 0x1E: return "!!" + s.u();
            case 0x14: { String e = readType(img, s); int rank = s.u(); int ns = s.u(); for (int i = 0; i < ns; i++) s.u(); int nl = s.u(); for (int i = 0; i < nl; i++) s.u(); return e + "[" + rank + "]"; }
            case 0x15: { s.next(); ClrType ct = typeOfCoded(img, s.u()); int n = s.u(); StringBuilder sb = new StringBuilder(ct == null ? "?" : ct.name).append('<'); for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append(readType(img, s)); } return sb.append('>').toString(); }
            case 0x1F: case 0x20: s.u(); return readType(img, s);
            case 0x1B: { s.next(); int n = s.u(); readType(img, s); for (int i = 0; i < n; i++) readType(img, s); return "fnptr"; }
        }
        return "?";
    }

    void parseMethodSig(Image img, ClrMethod m, byte[] sig) {
        m.sig = sig;
        Sig s = new Sig(sig, 0);
        int fl = s.next();
        m.hasThis = (fl & 0x20) != 0;
        if ((fl & 0x10) != 0) s.u();
        int n = s.u();
        String ret = readType(img, s);
        m.retVoid = ret.equals("Void");
        m.nparams = n;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); if (s.peek() == 0x41) s.next(); sb.append(readType(img, s)); }
        m.sigText = sb.append(')').toString();
    }

    // ------------------------------------------------------------ types
    ClrType typeOfCoded(Image img, int coded) {
        int[] d = Metadata.decode(Metadata.C_TDR, coded);
        if (d[1] == 0) return null;
        switch (d[0]) {
            case Metadata.TYPEDEF: return typeDef(img, d[1]);
            case Metadata.TYPEREF: return typeRef(img, d[1]);
            case Metadata.TYPESPEC: return typeSpec(img, d[1]);
        }
        return null;
    }

    public ClrType typeDef(Image img, int row) {
        ClrType t = img.typeCache[row];
        if (t != null) return t;
        Metadata md = img.md;
        int[] r = md.row(Metadata.TYPEDEF, row);
        t = new ClrType();
        img.typeCache[row] = t;
        t.img = img; t.row = row;
        t.name = md.string(r[1]); t.ns = md.string(r[2]);
        t.full = t.ns.isEmpty() ? t.name : t.ns + "." + t.name;
        for (int[] n : img.md.rows[Metadata.NESTED] == null ? new int[0][] : img.md.rows[Metadata.NESTED])
            if (n[0] == row) { t.declaring = typeDef(img, n[1]); t.full = t.declaring.full + "/" + t.name; }
        t.isInterface = (r[0] & 0x20) != 0;
        if (r[3] != 0) t.base = typeOfCoded(img, r[3]);
        if (t.base != null) {
            t.valueType = t.base.full.equals("System.ValueType") || t.base.full.equals("System.Enum") || (t.base.valueType && !t.base.isInterface);
            t.isEnum = t.base.full.equals("System.Enum");
            t.isDelegate = t.base.isDelegate || t.base.full.equals("System.MulticastDelegate") || t.base.full.equals("System.Delegate");
            t.slots = t.base.slots;
        }
        if (img.md.rows[Metadata.IFACEIMPL] != null)
            for (int[] ii : img.md.rows[Metadata.IFACEIMPL]) if (ii[0] == row) { ClrType it = typeOfCoded(img, ii[1]); if (it != null) t.ifaces.add(it); }
        int fEnd = row < md.count(Metadata.TYPEDEF) ? md.row(Metadata.TYPEDEF, row + 1)[4] : md.count(Metadata.FIELD) + 1;
        int staticCount = 0;
        for (int f = r[4]; f < fEnd; f++) {
            FieldInfo fi = fieldDef(img, f, t);
            if (fi.isStatic) fi.slot = staticCount++; else fi.slot = t.slots++;
            t.fields.put(fi.name, fi);
            t.fieldList.add(fi);
        }
        t.statics = new Object[staticCount];
        int mEnd = row < md.count(Metadata.TYPEDEF) ? md.row(Metadata.TYPEDEF, row + 1)[5] : md.count(Metadata.METHOD) + 1;
        for (int m = r[5]; m < mEnd; m++) {
            ClrMethod cm = methodDef(img, m, t);
            t.methods.add(cm);
            if (cm.name.equals(".cctor")) t.cctor = cm;
        }
        return t;
    }

    FieldInfo fieldDef(Image img, int row, ClrType owner) {
        FieldInfo fi = img.fieldCache[row];
        if (fi != null) return fi;
        int[] r = img.md.row(Metadata.FIELD, row);
        fi = new FieldInfo();
        fi.img = img; fi.row = row; fi.flags = r[0]; fi.name = img.md.string(r[1]); fi.sig = img.md.blob(r[2]);
        fi.isStatic = (r[0] & 0x10) != 0;
        fi.owner = owner;
        img.fieldCache[row] = fi;
        return fi;
    }

    ClrMethod methodDef(Image img, int row, ClrType owner) {
        ClrMethod m = img.methodCache[row];
        if (m != null) return m;
        int[] r = img.md.row(Metadata.METHOD, row);
        m = new ClrMethod();
        img.methodCache[row] = m;
        m.img = img; m.row = row; m.owner = owner; m.rva = r[0]; m.implFlags = r[1]; m.flags = r[2];
        m.name = img.md.string(r[3]);
        m.isStatic = (r[2] & 0x10) != 0; m.isVirtual = (r[2] & 0x40) != 0; m.isAbstract = (r[2] & 0x400) != 0;
        m.isCtor = m.name.equals(".ctor") || m.name.equals(".cctor");
        parseMethodSig(img, m, img.md.blob(r[4]));
        return m;
    }

    public ClrMethod methodDefByRow(Image img, int row) {
        if (img.methodCache[row] != null) return img.methodCache[row];
        // find owning type: last typedef with first method <= row
        int owner = 1;
        for (int i = 1; i <= img.md.count(Metadata.TYPEDEF); i++) if (img.md.row(Metadata.TYPEDEF, i)[5] <= row) owner = i; else break;
        typeDef(img, owner);
        return img.methodCache[row] != null ? img.methodCache[row] : methodDef(img, row, typeDef(img, owner));
    }

    public FieldInfo fieldDefByRow(Image img, int row) {
        if (img.fieldCache[row] != null) return img.fieldCache[row];
        int owner = 1;
        for (int i = 1; i <= img.md.count(Metadata.TYPEDEF); i++) if (img.md.row(Metadata.TYPEDEF, i)[4] <= row) owner = i; else break;
        typeDef(img, owner);
        return img.fieldCache[row];
    }

    ClrType typeRef(Image img, int row) {
        int[] r = img.md.row(Metadata.TYPEREF, row);
        String name = img.md.string(r[1]), ns = img.md.string(r[2]);
        int[] scope = Metadata.decode(Metadata.C_RS, r[0]);
        if (scope[0] == Metadata.TYPEREF) {
            ClrType outer = typeRef(img, scope[1]);
            String full = outer.full + "/" + name;
            if (!outer.isNative) { Integer tr = outer.img.typeByName.get(full); if (tr != null) return typeDef(outer.img, tr); }
            return nativeType(full);
        }
        String full = ns.isEmpty() ? name : ns + "." + name;
        if (scope[0] == Metadata.ASSEMBLYREF) {
            String asm = img.md.string(img.md.row(Metadata.ASSEMBLYREF, scope[1])[6]);
            Image target = Bcl.isSystemAssembly(asm) ? null : findAssembly(asm);
            if (target != null) { Integer tr = target.typeByName.get(full); if (tr != null) return typeDef(target, tr); }
        } else if (scope[0] == Metadata.MODULE) {
            Integer tr = img.typeByName.get(full);
            if (tr != null) return typeDef(img, tr);
        }
        return nativeType(full);
    }

    ClrType typeSpec(Image img, int row) {
        byte[] b = img.md.blob(img.md.row(Metadata.TYPESPEC, row)[0]);
        Sig s = new Sig(b, 0);
        while (s.peek() == 0x1F || s.peek() == 0x20) { s.next(); s.u(); }
        int t = s.next();
        if (t == 0x15) { s.next(); return typeOfCoded(img, s.u()); }
        if (t == 0x11 || t == 0x12) return typeOfCoded(img, s.u());
        if (t == 0x1D || t == 0x14) return nativeType("System.Array");
        if (t == 0x1C) return nativeType("System.Object");
        return nativeType("System.Object");
    }

    public ClrType nativeType(String full) {
        ClrType t = nativeTypes.get(full);
        if (t != null) return t;
        t = new ClrType();
        nativeTypes.put(full, t);
        t.isNative = true; t.full = full; t.inited = true;
        int i = full.lastIndexOf('.');
        int sl = full.lastIndexOf('/');
        t.ns = i < 0 ? "" : full.substring(0, i);
        t.name = full.substring(Math.max(i, sl) + 1);
        String bn = Bcl.nativeBase(full);
        if (bn != null) t.base = nativeType(bn);
        t.valueType = Bcl.nativeIsValueType(full) || (t.base != null && t.base.valueType);
        t.isEnum = full.equals("System.Enum");
        t.isDelegate = full.equals("System.MulticastDelegate") || full.equals("System.Delegate") || (t.base != null && t.base.isDelegate);
        t.slots = Bcl.nativeSlots(full) + (t.base != null ? t.base.slots : 0);
        return t;
    }

    // ------------------------------------------------------------ member resolution
    /** Resolves a method token (MethodDef, MemberRef, MethodSpec). */
    public ClrMethod resolveMethod(Image img, int token) {
        int tab = token >>> 24, row = token & 0xFFFFFF;
        switch (tab) {
            case Metadata.METHOD: return methodDefByRow(img, row);
            case Metadata.MEMBERREF: return (ClrMethod) memberRef(img, row);
            case Metadata.METHODSPEC: {
                int[] d = Metadata.decode(Metadata.C_MDR, img.md.row(Metadata.METHODSPEC, row)[0]);
                return d[0] == Metadata.METHOD ? methodDefByRow(img, d[1]) : (ClrMethod) memberRef(img, d[1]);
            }
        }
        throw new IllegalStateException("bad method token " + Integer.toHexString(token));
    }

    public FieldInfo resolveField(Image img, int token) {
        int tab = token >>> 24, row = token & 0xFFFFFF;
        if (tab == Metadata.FIELD) return fieldDefByRow(img, row);
        return (FieldInfo) memberRef(img, row);
    }

    private final HashMap<Long, Object> memberCache = new HashMap<>();

    Object memberRef(Image img, int row) {
        long key = (long) System.identityHashCode(img) << 32 | row;
        Object c = memberCache.get(key);
        if (c != null) return c;
        int[] r = img.md.row(Metadata.MEMBERREF, row);
        String name = img.md.string(r[1]);
        byte[] sig = img.md.blob(r[2]);
        int[] p = Metadata.decode(Metadata.C_MRP, r[0]);
        ClrType owner;
        switch (p[0]) {
            case Metadata.TYPEDEF: owner = typeDef(img, p[1]); break;
            case Metadata.TYPEREF: owner = typeRef(img, p[1]); break;
            case Metadata.TYPESPEC: owner = typeSpec(img, p[1]); break;
            case Metadata.METHOD: return methodDefByRow(img, p[1]);
            default: owner = nativeType("System.Object");
        }
        Object res;
        if ((sig[0] & 0xFF) == 0x06) res = findField(owner, name, sig, img);
        else res = findMethod(owner, name, sig, img);
        memberCache.put(key, res);
        return res;
    }

    FieldInfo findField(ClrType owner, String name, byte[] sig, Image img) {
        for (ClrType t = owner; t != null; t = t.base) {
            if (!t.isNative) { FieldInfo f = t.fields.get(name); if (f != null) return f; }
        }
        FieldInfo fi = new FieldInfo();
        fi.name = name; fi.owner = owner; fi.sig = sig; fi.img = img; fi.slot = -1;
        fi.isStatic = true; // native fields are handled by Bcl by name
        return fi;
    }

    ClrMethod findMethod(ClrType owner, String name, byte[] sig, Image img) {
        ClrMethod probe = new ClrMethod();
        probe.name = name; probe.img = img;
        parseMethodSig(img, probe, sig);
        if (!owner.isNative) {
            for (ClrType t = owner; t != null && !t.isNative; t = t.base) {
                for (ClrMethod m : t.methods) if (m.name.equals(name) && m.nparams == probe.nparams && sigMatches(m, probe)) return m;
            }
            if (owner.isInterface) {
                for (ClrMethod m : owner.methods) if (m.name.equals(name) && m.nparams == probe.nparams) return m;
            }
        }
        probe.owner = owner; probe.isNative = true;
        probe.isCtor = name.equals(".ctor");
        probe.isStatic = !probe.hasThis;
        probe.nativeKey = owner.full + "::" + name;
        probe.nativeKeyTyped = probe.nativeKey + probe.sigText;
        return probe;
    }

    private static boolean sigMatches(ClrMethod a, ClrMethod b) {
        // generic parameters and cross-assembly names make exact text comparison unreliable; compare loosely
        if (a.sigText.equals(b.sigText)) return true;
        String[] x = a.sigText.split(","), y = b.sigText.split(",");
        if (x.length != y.length) return false;
        for (int i = 0; i < x.length; i++) {
            String p = x[i].replaceAll("[()]", ""), q = y[i].replaceAll("[()]", "");
            if (p.equals(q) || p.startsWith("!") || q.startsWith("!")) continue;
            if (p.contains("<") && q.contains("<") && p.substring(0, p.indexOf('<')).equals(q.substring(0, q.indexOf('<')))) continue;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ objects
    public ClrObject newObject(ClrType t) {
        ensureInit(t);
        ClrObject o = new ClrObject(t);
        for (ClrType c = t; c != null; c = c.base)
            if (!c.isNative) for (FieldInfo f : c.fieldList) if (!f.isStatic) o.f[f.slot] = f.makeDefault();
        return o;
    }

    public void ensureInit(ClrType t) {
        if (t.inited) return;
        t.inited = true;
        if (t.base != null) ensureInit(t.base);
        for (FieldInfo f : t.fieldList) if (f.isStatic) t.statics[f.slot] = f.makeDefault();
        if (t.cctor != null) interp.exec(t.cctor, new Object[0]);
    }

    static Object defaultOf(Image img, byte[] sig, int pos) {
        Sig s = new Sig(sig, pos);
        while (s.peek() == 0x1F || s.peek() == 0x20) { s.next(); s.u(); }
        int t = s.next();
        switch (t) {
            case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: case 0x09: case 0x18: case 0x19: return 0;
            case 0x0A: case 0x0B: return 0L;
            case 0x0C: case 0x0D: return 0.0;
            case 0x11: {
                ClrType ct = img.vm.typeOfCoded(img, s.u());
                return img.vm.defaultValue(ct);
            }
            case 0x15: {
                if (s.next() == 0x11) return img.vm.defaultValue(img.vm.typeOfCoded(img, s.u()));
                return null;
            }
        }
        return null;
    }

    public Object defaultValue(ClrType ct) {
        if (ct == null) return null;
        if (ct.isNative) return Bcl.nativeDefault(ct.full);
        if (ct.isEnum || (ct.base != null && ct.base.full.equals("System.Enum"))) {
            for (FieldInfo f : ct.fieldList) if (!f.isStatic) return f.makeDefault();
            return 0;
        }
        if (ct.valueType) return newObject(ct);
        return null;
    }

    // ------------------------------------------------------------ invocation
    public Object invoke(ClrMethod m, Object[] args) {
        if (stopped) throw new Interp.Stop();
        if (m.isNative) return bcl.call(m, args);
        return interp.exec(m, args);
    }

    /** Finds the implementation of declared method m for a receiver. */
    public ClrMethod virtualTarget(ClrMethod m, Object recv) {
        if (!(recv instanceof ClrObject)) return m;
        ClrType rt = ((ClrObject) recv).type;
        for (ClrType t = rt; t != null && !t.isNative; t = t.base) {
            for (ClrMethod c : t.methods) {
                if (c.isStatic || c.nparams != m.nparams) continue;
                if (c.name.equals(m.name) && Vm.sigLoose(c, m)) return c;
                if (c.name.endsWith("." + m.name) && Vm.sigLoose(c, m)) return c;
            }
        }
        return m;
    }

    static boolean sigLoose(ClrMethod a, ClrMethod b) { return sigMatches(a, b); }

    public Object callByName(Object recv, String name, Object... args) {
        if (recv instanceof ClrObject) {
            ClrType rt = ((ClrObject) recv).type;
            for (ClrType t = rt; t != null && !t.isNative; t = t.base)
                for (ClrMethod c : t.methods) if (c.name.equals(name) && c.nparams == args.length && !c.isStatic) {
                    Object[] a = new Object[args.length + 1];
                    a[0] = recv; System.arraycopy(args, 0, a, 1, args.length);
                    return invoke(c, a);
                }
        }
        return null;
    }

    // ------------------------------------------------------------ exceptions
    public ClrThrow makeThrow(String typeName, String msg) {
        ClrType t = nativeType(typeName);
        ClrObject o = newObject(t);
        if (o.f.length > 0) o.f[0] = msg;
        return new ClrThrow(o);
    }

    public static String describe(Object ex) {
        if (ex instanceof ClrObject) {
            ClrObject o = (ClrObject) ex;
            String msg = o.f.length > 0 && o.f[0] instanceof String ? (String) o.f[0] : null;
            return o.type.full + (msg != null ? ": " + msg : "");
        }
        return String.valueOf(ex);
    }

    /** Runs the entry point; returns exit code. */
    public int runMain(String[] args) {
        int tok = main.md.entryPointToken;
        if (tok == 0) throw new IllegalStateException("no entry point");
        ClrMethod m = resolveMethod(main, tok);
        ensureInit(m.owner);
        Object[] a = m.nparams == 0 ? new Object[0] : new Object[]{args};
        try {
            Object r = invoke(m, a);
            return r instanceof Integer ? (Integer) r : 0;
        } catch (Interp.Exit e) {
            return e.code;
        } catch (Interp.Stop e) {
            return -1;
        } catch (ClrThrow t) {
            console.write("\nUnhandled Exception: " + describe(t.ex) + "\n");
            for (String fr : t.frames) console.write("   at " + fr + "\n");
            return -532462766;
        }
    }

    public ArrayList<String> trace = new ArrayList<>();
}
