package com.unixroot.win10.clr;

import java.util.ArrayList;
import java.util.HashMap;

/** Runtime object model for the CLR interpreter. */
public final class Model {
    private Model() { }

    public static final class Image {
        public final Metadata md;
        public final String name;
        public final Vm vm;
        final HashMap<String, Integer> typeByName = new HashMap<>();
        final ClrType[] typeCache;
        final ClrMethod[] methodCache;
        final FieldInfo[] fieldCache;
        public Image(Vm vm, String name, Metadata md) {
            this.vm = vm; this.name = name; this.md = md;
            typeCache = new ClrType[md.count(Metadata.TYPEDEF) + 1];
            methodCache = new ClrMethod[md.count(Metadata.METHOD) + 1];
            fieldCache = new FieldInfo[md.count(Metadata.FIELD) + 1];
            int[][] nested = md.rows[Metadata.NESTED];
            int[] encl = new int[typeCache.length];
            if (nested != null) for (int[] r : nested) encl[r[0]] = r[1];
            for (int i = 1; i < typeCache.length; i++) typeByName.put(fullName(i, encl), i);
        }
        private String fullName(int row, int[] encl) {
            int[] r = md.row(Metadata.TYPEDEF, row);
            String n = md.string(r[1]), ns = md.string(r[2]);
            if (encl[row] != 0) return fullName(encl[row], encl) + "/" + n;
            return ns.isEmpty() ? n : ns + "." + n;
        }
    }

    public static final class FieldInfo {
        public String name;
        public ClrType owner;
        public boolean isStatic;
        public int slot;
        public byte[] sig;
        public Image img;
        public int row;
        public int flags;
        public Object makeDefault() { return Vm.defaultOf(img, sig, 1); }
    }

    public static final class ClrType {
        public String ns = "", name = "", full = "";
        public Image img;
        public int row;
        public ClrType base, declaring;
        public ArrayList<ClrType> ifaces = new ArrayList<>();
        public boolean valueType, isEnum, isInterface, isNative, isDelegate, inited;
        public int slots;
        public Object[] statics = new Object[0];
        public final HashMap<String, FieldInfo> fields = new HashMap<>();
        public ArrayList<FieldInfo> fieldList = new ArrayList<>();
        public ArrayList<ClrMethod> methods = new ArrayList<>();
        public ClrMethod cctor;
        public boolean isSubclassOf(ClrType o) {
            for (ClrType t = this; t != null; t = t.base) {
                if (t == o) return true;
                for (ClrType i : t.ifaces) if (i.isSubclassOf(o)) return true;
            }
            return false;
        }
        public boolean isSubclassOf(String fullName) {
            for (ClrType t = this; t != null; t = t.base) {
                if (t.full.equals(fullName)) return true;
                for (ClrType i : t.ifaces) if (i.isSubclassOf(fullName)) return true;
            }
            return false;
        }
        @Override public String toString() { return full; }
    }

    public static final class ClrMethod {
        public ClrType owner;
        public String name;
        public Image img;
        public int row;
        public int rva, flags, implFlags;
        public boolean isStatic, isVirtual, hasThis, retVoid, isNative, isAbstract, isCtor;
        public int nparams;
        public String sigText = "()";
        public byte[] sig;
        // parsed body
        public byte[] code;
        public int maxStack;
        public byte[] localsSig;
        public int nlocals;
        public Object[] localDefaults;
        public boolean initLocals;
        public int[][] clauses; // flags, tryStart, tryLen, handlerStart, handlerLen, classTokenOrFilter
        public String nativeKey, nativeKeyTyped;
        public String[] ptypes;
        public final java.util.HashMap<Integer, Object> cache = new java.util.HashMap<>();
        @Override public String toString() { return (owner != null ? owner.full : "?") + "::" + name + sigText; }
    }

    /** A managed object instance (class or struct). */
    public static final class ClrObject implements Cloneable {
        public final ClrType type;
        public Object[] f;
        public Object nativeState;
        public ClrObject(ClrType t) { type = t; f = new Object[t.slots]; }
        public ClrObject copy() {
            ClrObject c = new ClrObject(type);
            for (int i = 0; i < f.length; i++) c.f[i] = f[i] instanceof ClrObject && ((ClrObject) f[i]).type.valueType ? ((ClrObject) f[i]).copy() : f[i];
            c.nativeState = nativeState;
            return c;
        }
    }

    /** Boxed value type or enum value that must remember its type. */
    public static final class Boxed {
        public final ClrType type;
        public final Object v;
        public Boxed(ClrType t, Object v) { type = t; this.v = v; }
    }

    public abstract static class Ref {
        public abstract Object get();
        public abstract void set(Object v);
    }

    public static final class MethodPtr {
        public final ClrMethod m;
        public MethodPtr(ClrMethod m) { this.m = m; }
    }

    public static final class Delegate {
        public ArrayList<Object[]> targets = new ArrayList<>(); // {target, ClrMethod}
        public ClrType type;
        public Delegate(ClrType t, Object target, ClrMethod m) { type = t; targets.add(new Object[]{target, m}); }
        public Delegate(ClrType t) { type = t; }
        public Delegate combine(Delegate o) {
            Delegate d = new Delegate(type);
            d.targets.addAll(targets);
            if (o != null) d.targets.addAll(o.targets);
            return d;
        }
        public Delegate remove(Delegate o) {
            Delegate d = new Delegate(type);
            d.targets.addAll(targets);
            if (o != null) for (Object[] t : o.targets) {
                for (int i = d.targets.size() - 1; i >= 0; i--) {
                    Object[] x = d.targets.get(i);
                    if (x[0] == t[0] && x[1] == t[1]) { d.targets.remove(i); break; }
                }
            }
            return d.targets.isEmpty() ? null : d;
        }
    }

    /** Thrown to propagate a managed exception through Java frames. */
    public static final class ClrThrow extends RuntimeException {
        public final Object ex;
        public final java.util.ArrayList<String> frames = new java.util.ArrayList<>();
        public ClrThrow(Object ex) { super(null, null, false, false); this.ex = ex; }
        @Override public String getMessage() { return Vm.describe(ex); }
    }
}
