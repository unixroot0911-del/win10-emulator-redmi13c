package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;
import com.unixroot.win10.core.WinPath;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Native implementation of the parts of the .NET base class library that programs commonly use. */
public final class Bcl {
    private final Vm vm;
    public Bcl(Vm vm) { this.vm = vm; }

    /** When true, unimplemented .NET members are logged once and stubbed (default value) instead of throwing. */
    public boolean lenient;
    private final java.util.HashSet<String> stubbed = new java.util.HashSet<>();

    private boolean stub(String what) {
        if (!lenient) return false;
        if (stubbed.add(what)) vm.console.write("[compat] stubbed missing API: " + what + "\n");
        return true;
    }

    /** Default return value for a stubbed call, from the method signature's return type. */
    private static Object retDefault(ClrMethod m) {
        if (m.retVoid || m.sig == null || m.sig.length < 3) return null;
        int i = 1; if ((m.sig[0] & 0x10) != 0) i++;
        int pc = m.sig[i] & 255; i += (pc & 0x80) != 0 ? 2 : 1;
        while (i < m.sig.length && (m.sig[i] == 0x1F || m.sig[i] == 0x20)) i += 2;
        int t = i < m.sig.length ? m.sig[i] & 255 : 0x1C;
        if (t >= 2 && t <= 9 || t == 0x18 || t == 0x19) return 0;
        if (t == 0x0A || t == 0x0B) return 0L;
        if (t == 0x0C || t == 0x0D) return 0.0;
        return null;
    }

    /** Hook for additional native libraries (e.g. WinForms) tried before the built-in dispatch. */
    public interface Ext {
        /** Returns Ext.NONE when the call is not handled. */
        Object call(Vm vm, ClrMethod m, Object[] a);
        Object getStatic(Vm vm, FieldInfo f);
    }
    public static final Object NONE = new Object();
    public final ArrayList<Ext> exts = new ArrayList<>();
    public static final java.util.HashSet<String> OBJECT_BACKED = new java.util.HashSet<>();
    public static final HashMap<String, String> EXTRA_BASES = new HashMap<>();

    // ------------------------------------------------------------ type knowledge
    static boolean isSystemAssembly(String n) {
        return n.equals("mscorlib") || n.equals("System") || n.startsWith("System.") || n.equals("netstandard") || n.equals("Microsoft.CSharp")
                || n.equals("WindowsBase") || n.equals("PresentationFramework") || n.equals("Microsoft.VisualBasic") || n.equals("Accessibility");
    }

    private static final java.util.Set<String> VALUE_TYPES = new java.util.HashSet<>(Arrays.asList(
            "System.Boolean", "System.Char", "System.SByte", "System.Byte", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32",
            "System.Int64", "System.UInt64", "System.Single", "System.Double", "System.Decimal", "System.IntPtr", "System.UIntPtr",
            "System.DateTime", "System.TimeSpan", "System.Guid", "System.Void", "System.ValueType", "System.Enum", "System.Nullable`1",
            "System.Collections.Generic.KeyValuePair`2", "System.Drawing.Point", "System.Drawing.Size", "System.Drawing.Rectangle", "System.Drawing.Color"));

    static boolean nativeIsValueType(String full) {
        if (VALUE_TYPES.contains(full)) return true;
        if (full.endsWith("/Enumerator")) return true;
        return false;
    }

    static String nativeBase(String full) {
        if (full.equals("System.Object")) return null;
        String e = EXTRA_BASES.get(full);
        if (e != null) return e;
        switch (full) {
            case "System.Exception": return "System.Object";
            case "System.SystemException": case "System.ApplicationException": return "System.Exception";
            case "System.ArgumentNullException": case "System.ArgumentOutOfRangeException": return "System.ArgumentException";
            case "System.ObjectDisposedException": return "System.InvalidOperationException";
            case "System.DivideByZeroException": case "System.OverflowException": return "System.ArithmeticException";
            case "System.IO.FileNotFoundException": case "System.IO.DirectoryNotFoundException": case "System.IO.EndOfStreamException": return "System.IO.IOException";
            case "System.ValueType": case "System.Delegate": case "System.Array": case "System.String": return "System.Object";
            case "System.Enum": return "System.ValueType";
            case "System.MulticastDelegate": return "System.Delegate";
            case "System.EventHandler": case "System.Action": return "System.MulticastDelegate";
        }
        if (full.endsWith("Exception")) {
            if (full.startsWith("System.") && (full.equals("System.ArgumentException") || full.equals("System.InvalidOperationException") || full.equals("System.NotSupportedException")
                    || full.equals("System.NotImplementedException") || full.equals("System.NullReferenceException") || full.equals("System.IndexOutOfRangeException")
                    || full.equals("System.InvalidCastException") || full.equals("System.FormatException") || full.equals("System.ArithmeticException")
                    || full.equals("System.OutOfMemoryException") || full.equals("System.StackOverflowException") || full.equals("System.IO.IOException")
                    || full.equals("System.UnauthorizedAccessException") || full.equals("System.TimeoutException") || full.equals("System.MissingMethodException")
                    || full.equals("System.Collections.Generic.KeyNotFoundException") || full.equals("System.ArgumentException"))) return "System.SystemException";
            return "System.Exception";
        }
        if (full.startsWith("System.Func`") || full.startsWith("System.Action`") || full.startsWith("System.Predicate`") || full.startsWith("System.Comparison`")
                || full.endsWith("EventHandler") || full.startsWith("System.EventHandler`") || full.endsWith("ThreadStart") || full.endsWith("Callback") || full.endsWith("Handler")
                || full.equals("System.Converter`2") || full.equals("System.Threading.SendOrPostCallback") || full.equals("System.Windows.Threading.DispatcherOperationCallback")
                || full.equals("System.Threading.TimerCallback") || full.equals("System.AsyncCallback") || full.startsWith("System.Comparison`")) return "System.MulticastDelegate";
        if (full.equals("System.Collections.Generic.IEqualityComparer`1")) return "System.Object";
        return "System.Object";
    }

    static int nativeSlots(String full) { return full.equals("System.Exception") ? 3 : 0; }

    static Object nativeDefault(String full) {
        switch (full) {
            case "System.Boolean": case "System.Char": case "System.SByte": case "System.Byte": case "System.Int16": case "System.UInt16": case "System.Int32": case "System.UInt32":
            case "System.IntPtr": case "System.UIntPtr": return 0;
            case "System.Int64": case "System.UInt64": case "System.DateTime": case "System.TimeSpan": return 0L;
            case "System.Single": case "System.Double": return 0.0;
        }
        return null;
    }

    static boolean isObjectBacked(Vm vm, ClrType t) {
        if (OBJECT_BACKED.contains(t.full)) return true;
        for (ClrType c = t; c != null; c = c.base) if (c.full.equals("System.Exception") || OBJECT_BACKED.contains(c.full)) return true;
        return false;
    }

    public static boolean isInst(Vm vm, Object o, ClrType t) {
        if (o == null) return false;
        if (t == null) return true;
        String f = t.full;
        if (f.equals("System.Object")) return true;
        if (o instanceof ClrObject) return ((ClrObject) o).type.isSubclassOf(t);
        if (o instanceof Boxed) return ((Boxed) o).type.isSubclassOf(t) || f.equals("System.ValueType") || f.equals("System.Enum");
        if (o instanceof Delegate) return t.isDelegate || ((Delegate) o).type.isSubclassOf(t);
        if (o instanceof String) return f.equals("System.String") || f.equals("System.IComparable") || f.startsWith("System.Collections.Generic.IEnumerable") || f.equals("System.IConvertible") || f.equals("System.ICloneable");
        if (o instanceof Integer) return f.equals("System.Int32") || f.equals("System.ValueType") || f.equals("System.IComparable") || f.equals("System.Int16") || f.equals("System.Byte") || f.equals("System.UInt32") || f.equals("System.IFormattable");
        if (o instanceof Long) return f.equals("System.Int64") || f.equals("System.UInt64") || f.equals("System.ValueType") || f.equals("System.IComparable");
        if (o instanceof Double) return f.equals("System.Double") || f.equals("System.ValueType") || f.equals("System.IComparable");
        if (o instanceof Float) return f.equals("System.Single") || f.equals("System.ValueType");
        if (o instanceof Boolean) return f.equals("System.Boolean") || f.equals("System.ValueType");
        if (o instanceof Character) return f.equals("System.Char") || f.equals("System.ValueType");
        if (o instanceof ArrayList) return f.startsWith("System.Collections.Generic.List`") || f.startsWith("System.Collections.Generic.IList`") || f.startsWith("System.Collections.Generic.ICollection`") || f.startsWith("System.Collections.Generic.IEnumerable`") || f.equals("System.Collections.IEnumerable") || f.equals("System.Collections.IList") || f.equals("System.Collections.ArrayList") || f.equals("System.Collections.ICollection");
        if (o instanceof Map) return f.startsWith("System.Collections.Generic.Dictionary`") || f.startsWith("System.Collections.Generic.IDictionary`") || f.equals("System.Collections.Hashtable") || f.equals("System.Collections.IDictionary");
        if (o instanceof StringBuilder) return f.equals("System.Text.StringBuilder");
        if (o.getClass().isArray()) return f.equals("System.Array") || f.equals("System.Collections.IEnumerable") || f.startsWith("System.Collections.Generic.I");
        if (o instanceof ClrType) return f.equals("System.Type");
        return !t.isNative ? false : true;
    }

    // ------------------------------------------------------------ entry points
    private static final class StringBuilder { }

    public Object construct(ClrMethod ctor, Object[] a) {
        for (Ext e : exts) { Object r = e.call(vm, ctor, prepend(null, a)); if (r != NONE) return r; }
        String t = norm(ctor.owner.full);
        switch (t) {
            case "System.Object": return new Object();
            case "System.Text.StringBuilder": { java.lang.StringBuilder sb = new java.lang.StringBuilder(); if (a.length == 1 && a[0] instanceof String) sb.append((String) a[0]); return new SB(sb); }
            case "System.Collections.Generic.List": case "System.Collections.ArrayList": { ArrayList<Object> l = new ArrayList<>(); if (a.length == 1 && !(a[0] instanceof Integer)) for (Object o : iterable(a[0])) l.add(o); return l; }
            case "System.Collections.Generic.Dictionary": case "System.Collections.Hashtable": return new LinkedHashMap<Object, Object>();
            case "System.Collections.Generic.HashSet": { LinkedHashSet<Object> s = new LinkedHashSet<>(); if (a.length == 1 && !(a[0] instanceof Integer)) for (Object o : iterable(a[0])) s.add(o); return s; }
            case "System.Collections.Generic.Queue": return new java.util.ArrayDeque<Object>();
            case "System.Collections.Generic.Stack": return new Stk();
            case "System.Random": return a.length == 1 ? new java.util.Random((Integer) a[0]) : new java.util.Random();
            case "System.Diagnostics.Stopwatch": return new Watch();
            case "System.String": {
                if (a.length == 1 && a[0] instanceof char[]) return new String((char[]) a[0]);
                if (a.length == 2) { char[] c = new char[(Integer) a[1]]; Arrays.fill(c, (char) ((Number) a[0]).intValue()); return new String(c); }
                if (a.length == 3) return new String((char[]) a[0], (Integer) a[1], (Integer) a[2]);
                return "";
            }
            case "System.IO.StringWriter": return new SB(new java.lang.StringBuilder());
            case "System.Collections.Generic.KeyValuePair": return new KVP(a[0], a[1]);
            case "System.DateTime": return a.length == 1 ? (Object) (Long) a[0] : (Object) 0L;
        }
        if (stub("new " + ctor.owner.full + ctor.sigText)) return vm.newObject(vm.nativeType(ctor.owner.full));
        throw vm.makeThrow("System.NotSupportedException", "Unimplemented .NET type: new " + ctor.owner.full + ctor.sigText);
    }

    private static Object[] prepend(Object first, Object[] a) {
        Object[] r = new Object[a.length + 1];
        r[0] = first; System.arraycopy(a, 0, r, 1, a.length);
        return r;
    }

    static String norm(String full) {
        int i = full.indexOf('`');
        return i < 0 ? full : full.substring(0, i);
    }

    // wrapper types
    static final class SB { final java.lang.StringBuilder b; SB(java.lang.StringBuilder b) { this.b = b; } @Override public String toString() { return b.toString(); } }
    static final class KVP { final Object k, v; KVP(Object k, Object v) { this.k = k; this.v = v; } }
    static final class Stk { final java.util.ArrayDeque<Object> d = new java.util.ArrayDeque<>(); }
    static final class Watch { long start, acc; boolean run; }
    static final class Enm { final Iterator<?> it; Object cur; Enm(Iterator<?> it) { this.it = it; } }
    public static final class EnmFactory { public Object of(Iterator<?> it) { return new Enm(it); } }

    public Object getStatic(FieldInfo f) {
        for (Ext e : exts) { Object r = e.getStatic(vm, f); if (r != NONE) return r; }
        String t = norm(f.owner.full), n = f.name;
        switch (t + "::" + n) {
            case "System.String::Empty": return "";
            case "System.Int32::MaxValue": return Integer.MAX_VALUE;
            case "System.Int32::MinValue": return Integer.MIN_VALUE;
            case "System.Int64::MaxValue": return Long.MAX_VALUE;
            case "System.Int64::MinValue": return Long.MIN_VALUE;
            case "System.UInt32::MaxValue": return -1;
            case "System.Byte::MaxValue": return 255;
            case "System.Int16::MaxValue": return 32767;
            case "System.Int16::MinValue": return -32768;
            case "System.Char::MaxValue": return 65535;
            case "System.Double::MaxValue": return Double.MAX_VALUE;
            case "System.Double::MinValue": return -Double.MAX_VALUE;
            case "System.Double::Epsilon": return Double.MIN_VALUE;
            case "System.Double::NaN": return Double.NaN;
            case "System.Double::PositiveInfinity": return Double.POSITIVE_INFINITY;
            case "System.Double::NegativeInfinity": return Double.NEGATIVE_INFINITY;
            case "System.Math::PI": return Math.PI;
            case "System.Math::E": return Math.E;
            case "System.Environment::NewLine": return "\r\n";
            case "System.IO.Path::DirectorySeparatorChar": return (int) '\\';
            case "System.EventArgs::Empty": return vm.newObject(vm.nativeType("System.EventArgs"));
            case "System.Type::EmptyTypes": return new Object[0];
            case "System.Boolean::TrueString": return "True";
            case "System.Boolean::FalseString": return "False";
            case "System.Threading.Timeout::Infinite": return -1;
            case "System.StringComparison::Ordinal": return 4;
            case "System.StringComparison::OrdinalIgnoreCase": return 5;
            case "System.StringComparison::InvariantCultureIgnoreCase": return 3;
            case "System.StringComparison::CurrentCultureIgnoreCase": return 1;
            case "System.StringComparison::InvariantCulture": return 2;
            case "System.StringComparison::CurrentCulture": return 0;
            case "System.Globalization.CultureInfo::InvariantCulture": case "System.Globalization.CultureInfo::CurrentCulture": return "inv";
        }
        if (stub("field " + f.owner.full + "::" + n)) return 0;
        throw vm.makeThrow("System.NotSupportedException", "Unimplemented .NET field: " + f.owner.full + "::" + n);
    }

    public void setStatic(FieldInfo f, Object v) { /* ignore writes to native statics */ }
    public Object getInstanceField(Object o, FieldInfo f) {
        if (o instanceof KVP) return f.name.equals("key") ? ((KVP) o).k : ((KVP) o).v;
        throw vm.makeThrow("System.NotSupportedException", "Unimplemented .NET field: " + f.owner.full + "::" + f.name);
    }
    public void setInstanceField(Object o, FieldInfo f, Object v) { if (stub("field store " + f.name)) return; throw vm.makeThrow("System.NotSupportedException", "Unimplemented .NET field store: " + f.name); }

    // ------------------------------------------------------------ call dispatch
    public Object call(ClrMethod m, Object[] a) {
        for (Ext e : exts) { Object r = e.call(vm, m, a); if (r != NONE) return r; }
        if (m.ptypes == null) {
            String s = m.sigText.substring(1, m.sigText.length() - 1);
            m.ptypes = s.isEmpty() ? new String[0] : s.split(",(?![^<]*>)");
        }
        if (m.hasThis) {
            Object recv = a[0] instanceof Ref ? ((Ref) a[0]).get() : a[0];
            a[0] = recv;
            if (m.name.equals(".ctor")) {
                if (recv instanceof ClrObject) { ctorOnObject(m, (ClrObject) recv, a); return null; }
                return null;
            }
            if (recv instanceof ClrObject) return onObject(m, (ClrObject) recv, a);
            return onNative(m, recv, a);
        }
        return onStatic(m, a);
    }

    private void ctorOnObject(ClrMethod m, ClrObject o, Object[] a) {
        for (ClrType t = m.owner; t != null; t = t.base) {
            if (t.full.equals("System.Exception")) {
                if (a.length > 1 && a[1] instanceof String) o.f[0] = a[1];
                if (a.length > 2) o.f[1] = a[2];
                return;
            }
        }
    }

    // --------------------------------------------------- ClrObject receivers
    private Object onObject(ClrMethod m, ClrObject o, Object[] a) {
        String n = m.name;
        boolean exc = o.type.isSubclassOf("System.Exception");
        if (exc) {
            switch (n) {
                case "get_Message": return o.f[0] != null ? o.f[0] : defaultMessage(o);
                case "get_InnerException": return o.f[1];
                case "get_StackTrace": return o.f[2] != null ? o.f[2] : "";
                case "get_Source": return "";
                case "get_HResult": return -2146233088;
                case "GetBaseException": return o;
                case "ToString": return o.type.full + ": " + (o.f[0] != null ? o.f[0] : defaultMessage(o));
            }
        }
        switch (n) {
            case "ToString": return o.type.full;
            case "GetHashCode": return System.identityHashCode(o);
            case "Equals": return o == a[1] ? 1 : 0;
            case "GetType": return o.type;
            case "MemberwiseClone": return o.copy();
            case "Dispose": case "Finalize": case "Close": return null;
            case "get_Message": return "";
        }
        return unimpl(m);
    }

    private static String defaultMessage(ClrObject o) { return "Exception of type '" + o.type.full + "' was thrown."; }

    private Object unimpl(ClrMethod m) {
        if (stub(m.owner.full + "::" + m.name + m.sigText)) return retDefault(m);
        throw vm.makeThrow("System.NotSupportedException", "Unimplemented .NET API: " + m.owner.full + "::" + m.name + m.sigText);
    }

    // --------------------------------------------------- Java receivers
    private Object onNative(ClrMethod m, Object r, Object[] a) {
        String n = m.name;
        if (r instanceof String) return onString(m, (String) r, a);
        if (r instanceof SB) return onSb(m, (SB) r, a);
        if (r instanceof ArrayList) return onList(m, (ArrayList<Object>) r, a);
        if (r instanceof Map) return onDict(m, (Map<Object, Object>) r, a);
        if (r instanceof java.util.Set) return onSet(m, (java.util.Set<Object>) r, a);
        if (r instanceof Enm) return onEnum(m, (Enm) r, a);
        if (r instanceof KVP) { if (n.equals("get_Key")) return ((KVP) r).k; if (n.equals("get_Value")) return ((KVP) r).v; }
        if (r instanceof java.util.ArrayDeque) return onQueue(m, (java.util.ArrayDeque<Object>) r, a);
        if (r instanceof Stk) return onStack(m, (Stk) r, a);
        if (r instanceof java.util.Random) return onRandom(m, (java.util.Random) r, a);
        if (r instanceof Watch) return onWatch(m, (Watch) r, a);
        if (r instanceof ClrType) return onType(m, (ClrType) r, a);
        if (r instanceof Delegate) {
            switch (n) { case "Invoke": return vm.interp.invokeDelegate((Delegate) r, Arrays.copyOfRange(a, 1, a.length)); case "get_Target": return ((Delegate) r).targets.get(0)[0]; case "GetInvocationList": { ArrayList<Object> l = new ArrayList<>(); for (Object[] t : ((Delegate) r).targets) l.add(t[0]); return l.toArray(); } }
        }
        if (r != null && r.getClass().isArray()) return onArray(m, r, a);
        // value types and generic object methods
        switch (n) {
            case "ToString": return str(r, m.owner.full, a.length > 1 ? a[1] : null);
            case "GetHashCode": return r == null ? 0 : r.hashCode();
            case "Equals": return valueEquals(r, a[1]) ? 1 : 0;
            case "CompareTo": return compare(r, a[1]);
            case "GetType": return typeOfValue(r);
            case "Dispose": case "Close": case "Flush": return null;
            case "GetTypeCode": return 0;
        }
        if (r instanceof Integer || r instanceof Long || r instanceof Double || r instanceof Character || r instanceof Boolean || r instanceof Float || r instanceof Boxed) {
            if (n.equals("HasValue") || n.equals("get_HasValue")) return 1;
        }
        return unimpl(m);
    }

    private ClrType typeOfValue(Object r) {
        if (r instanceof String) return vm.nativeType("System.String");
        if (r instanceof Integer) return vm.nativeType("System.Int32");
        if (r instanceof Long) return vm.nativeType("System.Int64");
        if (r instanceof Double) return vm.nativeType("System.Double");
        if (r instanceof Boolean) return vm.nativeType("System.Boolean");
        if (r instanceof Character) return vm.nativeType("System.Char");
        if (r instanceof Boxed) return ((Boxed) r).type;
        if (r instanceof ArrayList) return vm.nativeType("System.Collections.Generic.List`1");
        if (r instanceof Map) return vm.nativeType("System.Collections.Generic.Dictionary`2");
        if (r != null && r.getClass().isArray()) return vm.nativeType("System.Array");
        return vm.nativeType("System.Object");
    }

    private Object onType(ClrMethod m, ClrType t, Object[] a) {
        switch (m.name) {
            case "get_Name": return t.name;
            case "get_FullName": return t.full.replace('/', '+');
            case "get_Namespace": return t.ns;
            case "ToString": return t.full;
            case "get_BaseType": return t.base;
            case "get_IsEnum": return t.isEnum ? 1 : 0;
            case "get_IsValueType": return t.valueType ? 1 : 0;
            case "GetHashCode": return t.full.hashCode();
            case "Equals": return t == a[1] ? 1 : 0;
            case "get_IsInterface": return t.isInterface ? 1 : 0;
            case "get_Assembly": return t.img != null ? t.img.name : "mscorlib";
        }
        return unimpl(m);
    }

    // ---- strings
    private static int cmpMode(Object o) { return o instanceof Integer ? (Integer) o : 0; }

    private Object onString(ClrMethod m, String s, Object[] a) {
        String n = m.name;
        switch (n) {
            case "get_Length": return s.length();
            case "get_Chars": return (int) s.charAt((Integer) a[1]);
            case "Substring": return a.length == 2 ? s.substring((Integer) a[1]) : s.substring((Integer) a[1], (Integer) a[1] + (Integer) a[2]);
            case "IndexOf": case "LastIndexOf": {
                Object x = a[1];
                String needle = x instanceof String ? (String) x : String.valueOf((char) ((Number) x).intValue());
                boolean ic = a.length > 2 && a[a.length - 1] instanceof Integer && (cmpMode(a[a.length - 1]) == 1 || cmpMode(a[a.length - 1]) == 3 || cmpMode(a[a.length - 1]) == 5) && m.ptypes[m.ptypes.length - 1].equals("StringComparison");
                String h = s; if (ic) { h = s.toLowerCase(); needle = needle.toLowerCase(); }
                int start = a.length > 2 && m.ptypes.length > 1 && m.ptypes[1].equals("Int32") ? (Integer) a[2] : -1;
                if (n.equals("IndexOf")) return h.indexOf(needle, Math.max(0, start));
                return start >= 0 ? h.lastIndexOf(needle, start) : h.lastIndexOf(needle);
            }
            case "IndexOfAny": { char[] cs = (char[]) a[1]; int best = -1; for (int i = 0; i < s.length(); i++) { for (char c : cs) if (s.charAt(i) == c) { best = i; break; } if (best >= 0) break; } return best; }
            case "Contains": return a[1] instanceof String ? (s.contains((String) a[1]) ? 1 : 0) : (s.indexOf((char) ((Number) a[1]).intValue()) >= 0 ? 1 : 0);
            case "StartsWith": case "EndsWith": {
                String p = a[1] instanceof String ? (String) a[1] : String.valueOf((char) ((Number) a[1]).intValue());
                boolean ic = a.length > 2 && a[2] instanceof Integer && (cmpMode(a[2]) == 1 || cmpMode(a[2]) == 3 || cmpMode(a[2]) == 5);
                String h = ic ? s.toLowerCase() : s; if (ic) p = p.toLowerCase();
                return (n.equals("StartsWith") ? h.startsWith(p) : h.endsWith(p)) ? 1 : 0;
            }
            case "ToUpper": case "ToUpperInvariant": return s.toUpperCase();
            case "ToLower": case "ToLowerInvariant": return s.toLowerCase();
            case "Trim": case "TrimStart": case "TrimEnd": {
                String chars = " \t\r\n\f\u000B\u00A0";
                if (a.length > 1 && a[1] instanceof char[]) chars = new String((char[]) a[1]);
                else if (a.length > 1 && a[1] instanceof Integer) chars = String.valueOf((char) (int) (Integer) a[1]);
                int i = 0, j = s.length();
                if (!n.equals("TrimEnd")) while (i < j && chars.indexOf(s.charAt(i)) >= 0) i++;
                if (!n.equals("TrimStart")) while (j > i && chars.indexOf(s.charAt(j - 1)) >= 0) j--;
                return s.substring(i, j);
            }
            case "Replace": {
                if (a[1] instanceof String) return s.replace((String) a[1], a[2] == null ? "" : (String) a[2]);
                return s.replace((char) ((Number) a[1]).intValue(), (char) ((Number) a[2]).intValue());
            }
            case "Split": return split(s, a, m);
            case "Equals": {
                Object o = a[1];
                if (!(o instanceof String)) return 0;
                if (a.length > 2 && a[2] instanceof Integer && (cmpMode(a[2]) == 1 || cmpMode(a[2]) == 3 || cmpMode(a[2]) == 5)) return s.equalsIgnoreCase((String) o) ? 1 : 0;
                return s.equals(o) ? 1 : 0;
            }
            case "CompareTo": return Integer.signum(s.compareTo((String) a[1]));
            case "ToString": case "Clone": case "Normalize": return s;
            case "GetHashCode": return s.hashCode();
            case "PadLeft": case "PadRight": {
                int w = (Integer) a[1]; char c = a.length > 2 ? (char) ((Number) a[2]).intValue() : ' ';
                StringBuilder0 sb = new StringBuilder0();
                int pad = Math.max(0, w - s.length());
                String p = repeat(c, pad);
                return n.equals("PadLeft") ? p + s : s + p;
            }
            case "Insert": return s.substring(0, (Integer) a[1]) + a[2] + s.substring((Integer) a[1]);
            case "Remove": return a.length == 2 ? s.substring(0, (Integer) a[1]) : s.substring(0, (Integer) a[1]) + s.substring((Integer) a[1] + (Integer) a[2]);
            case "ToCharArray": return s.toCharArray();
            case "GetEnumerator": return new Enm(toCharList(s).iterator());
            case "Concat": return s + str(a[1]);
            case "Format": return format(s, Arrays.copyOfRange(a, 1, a.length));
            case "GetType": return vm.nativeType("System.String");
            case "Split0": break;
        }
        return unimpl(m);
    }

    private static final class StringBuilder0 { }
    private static String repeat(char c, int n) { char[] x = new char[Math.max(0, n)]; Arrays.fill(x, c); return new String(x); }
    private static List<Object> toCharList(String s) { ArrayList<Object> l = new ArrayList<>(); for (char c : s.toCharArray()) l.add(c); return l; }

    private Object split(String s, Object[] a, ClrMethod m) {
        ArrayList<String> parts = new ArrayList<>();
        Object sep = a.length > 1 ? a[1] : null;
        boolean removeEmpty = false;
        for (int i = 2; i < a.length; i++) if (a[i] instanceof Integer && (m.ptypes[i - 1].equals("StringSplitOptions"))) removeEmpty = ((Integer) a[i] & 1) != 0;
        ArrayList<String> seps = new ArrayList<>();
        if (sep instanceof char[]) for (char c : (char[]) sep) seps.add(String.valueOf(c));
        else if (sep instanceof Object[]) for (Object o : (Object[]) sep) seps.add((String) o);
        else if (sep instanceof String) seps.add((String) sep);
        else if (sep instanceof Integer) seps.add(String.valueOf((char) (int) (Integer) sep));
        if (seps.isEmpty() || (seps.size() == 1 && seps.get(0).isEmpty())) { for (String t : s.split("\\s+")) parts.add(t); }
        else {
            int i = 0, st = 0;
            while (i <= s.length()) {
                String hit = null;
                if (i < s.length()) for (String sp : seps) if (!sp.isEmpty() && s.startsWith(sp, i)) { hit = sp; break; }
                if (hit != null) { parts.add(s.substring(st, i)); i += hit.length(); st = i; }
                else i++;
            }
            parts.add(s.substring(st));
        }
        if (removeEmpty) parts.removeIf(String::isEmpty);
        return parts.toArray(new Object[0]);
    }

    private Object onSb(ClrMethod m, SB sb, Object[] a) {
        java.lang.StringBuilder b = sb.b;
        switch (m.name) {
            case "Append": {
                if (a.length == 2) {
                    String pt = m.ptypes[0];
                    if (pt.equals("Char")) b.append((char) ((Number) a[1]).intValue());
                    else if (pt.equals("Char[]")) b.append((char[]) a[1]);
                    else b.append(strTyped(a[1], pt));
                } else if (a.length == 3) { b.append(str(a[1]).repeat(Math.max(0, (Integer) a[2]))); if (m.ptypes[0].equals("Char")) { b.setLength(b.length() - str(a[1]).repeat(Math.max(0, (Integer) a[2])).length()); b.append(repeat((char) ((Number) a[1]).intValue(), (Integer) a[2])); } }
                else if (a.length == 4) b.append(((String) a[1]), (Integer) a[2], (Integer) a[2] + (Integer) a[3]);
                return sb;
            }
            case "AppendLine": if (a.length > 1) b.append(str(a[1])); b.append("\r\n"); return sb;
            case "AppendFormat": b.append(format((String) a[1], Arrays.copyOfRange(a, 2, a.length))); return sb;
            case "ToString": return a.length > 1 ? b.substring((Integer) a[1], (Integer) a[1] + (Integer) a[2]) : b.toString();
            case "get_Length": return b.length();
            case "set_Length": b.setLength((Integer) a[1]); return null;
            case "Clear": b.setLength(0); return sb;
            case "Insert": b.insert((Integer) a[1], strTyped(a[2], m.ptypes[1])); return sb;
            case "Remove": b.delete((Integer) a[1], (Integer) a[1] + (Integer) a[2]); return sb;
            case "get_Chars": return (int) b.charAt((Integer) a[1]);
            case "set_Chars": b.setCharAt((Integer) a[1], (char) ((Number) a[2]).intValue()); return null;
            case "Replace": { String f = (String) a[1]; String t = (String) a[2]; String x = b.toString().replace(f, t == null ? "" : t); b.setLength(0); b.append(x); return sb; }
            case "Write": b.append(str(a[1])); return null;
            case "WriteLine": if (a.length > 1) b.append(str(a[1])); b.append("\r\n"); return null;
            case "GetHashCode": return System.identityHashCode(sb);
            case "Equals": return sb == a[1] ? 1 : 0;
        }
        return unimpl(m);
    }

    private Iterable<Object> iterable(Object o) {
        if (o instanceof Iterable) return (Iterable<Object>) o;
        if (o instanceof Map) return new ArrayList<Object>(((Map<Object, Object>) o).entrySet().stream().map(e -> (Object) new KVP(e.getKey(), e.getValue())).collect(java.util.stream.Collectors.toList()));
        if (o != null && o.getClass().isArray()) { ArrayList<Object> l = new ArrayList<>(); for (int i = 0; i < Interp.arrLen(o); i++) l.add(Interp.arrGet(o, i, true)); return l; }
        if (o instanceof String) return toCharList((String) o);
        if (o instanceof ClrObject) { ArrayList<Object> l = new ArrayList<>(); Object e = vm.callByName(o, "GetEnumerator"); while ((Integer) vm.callByName(e, "MoveNext") != 0) l.add(vm.callByName(e, "get_Current")); return l; }
        throw vm.makeThrow("System.NotSupportedException", "Cannot enumerate " + o);
    }

    private int cmpObjs(Object x, Object y, Object comparer) {
        if (comparer instanceof Delegate) return (Integer) vm.interp.invokeDelegate((Delegate) comparer, new Object[]{x, y});
        if (comparer instanceof ClrObject) return (Integer) vm.callByName(comparer, "Compare", x, y);
        if (x instanceof ClrObject) return (Integer) vm.callByName(x, "CompareTo", y);
        return compare(x, y);
    }

    private Object onList(ClrMethod m, ArrayList<Object> l, Object[] a) {
        switch (m.name) {
            case "Add": l.add(a[1]); return m.retVoid ? null : (Object) (l.size() - 1);
            case "get_Item": if ((Integer) a[1] < 0 || (Integer) a[1] >= l.size()) throw vm.makeThrow("System.ArgumentOutOfRangeException", "Index was out of range. Must be non-negative and less than the size of the collection."); return l.get((Integer) a[1]);
            case "set_Item": l.set((Integer) a[1], a[2]); return null;
            case "get_Count": return l.size();
            case "get_Capacity": return l.size();
            case "Clear": l.clear(); return null;
            case "Contains": return containsObj(l, a[1]) ? 1 : 0;
            case "IndexOf": for (int i = 0; i < l.size(); i++) if (valueEquals(l.get(i), a[1])) return i; return -1;
            case "Remove": for (int i = 0; i < l.size(); i++) if (valueEquals(l.get(i), a[1])) { l.remove(i); return 1; } return 0;
            case "RemoveAt": l.remove((int) (Integer) a[1]); return null;
            case "RemoveRange": for (int k = 0; k < (Integer) a[2]; k++) l.remove((int) (Integer) a[1]); return null;
            case "Insert": l.add((Integer) a[1], a[2]); return null;
            case "AddRange": for (Object o : iterable(a[1])) l.add(o); return null;
            case "ToArray": return l.toArray(new Object[0]);
            case "Reverse": java.util.Collections.reverse(l); return null;
            case "Sort": { Object c = a.length > 1 ? a[a.length - 1] : null; final Object cc = c instanceof Delegate || c instanceof ClrObject ? c : null; l.sort((x, y) -> cmpObjs(x, y, cc)); return null; }
            case "GetEnumerator": return new Enm(new ArrayList<>(l).iterator());
            case "Find": for (Object o : l) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) return o; return null;
            case "FindIndex": for (int i = 0; i < l.size(); i++) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{l.get(i)}))) return i; return -1;
            case "FindAll": { ArrayList<Object> r = new ArrayList<>(); for (Object o : l) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) r.add(o); return r; }
            case "Exists": for (Object o : l) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) return 1; return 0;
            case "TrueForAll": for (Object o : l) if (!truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) return 0; return 1;
            case "RemoveAll": { int c = 0; for (int i = l.size() - 1; i >= 0; i--) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{l.get(i)}))) { l.remove(i); c++; } return c; }
            case "ForEach": for (Object o : new ArrayList<>(l)) vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}); return null;
            case "GetRange": return new ArrayList<>(l.subList((Integer) a[1], (Integer) a[1] + (Integer) a[2]));
            case "CopyTo": for (int i = 0; i < l.size(); i++) Interp.arrSet(a[1], (a.length > 2 ? (Integer) a[2] : 0) + i, l.get(i)); return null;
            case "get_IsReadOnly": return 0;
            case "GetHashCode": return System.identityHashCode(l);
            case "Equals": return l == a[1] ? 1 : 0;
            case "Dispose": return null;
        }
        return unimpl(m);
    }

    private boolean containsObj(java.util.Collection<Object> c, Object x) { for (Object o : c) if (valueEquals(o, x)) return true; return false; }
    private static boolean truth(Object o) { return o instanceof Integer ? (Integer) o != 0 : o instanceof Boolean ? (Boolean) o : o != null; }

    private Object onDict(ClrMethod m, Map<Object, Object> d, Object[] a) {
        switch (m.name) {
            case "Add": if (d.containsKey(key(a[1]))) throw vm.makeThrow("System.ArgumentException", "An item with the same key has already been added."); d.put(key(a[1]), a[2]); return null;
            case "get_Item": { Object k = key(a[1]); if (!d.containsKey(k)) throw vm.makeThrow("System.Collections.Generic.KeyNotFoundException", "The given key was not present in the dictionary."); return d.get(k); }
            case "set_Item": d.put(key(a[1]), a[2]); return null;
            case "ContainsKey": return d.containsKey(key(a[1])) ? 1 : 0;
            case "ContainsValue": return d.containsValue(a[1]) ? 1 : 0;
            case "TryGetValue": { Object k = key(a[1]); if (d.containsKey(k)) { ((Ref) a[2]).set(d.get(k)); return 1; } ((Ref) a[2]).set(null); return 0; }
            case "Remove": return d.remove(key(a[1])) != null || false ? 1 : 0;
            case "get_Count": return d.size();
            case "Clear": d.clear(); return null;
            case "get_Keys": return new ArrayList<Object>(d.keySet());
            case "get_Values": return new ArrayList<Object>(d.values());
            case "GetEnumerator": { ArrayList<Object> l = new ArrayList<>(); for (Map.Entry<Object, Object> e : d.entrySet()) l.add(new KVP(e.getKey(), e.getValue())); return new Enm(l.iterator()); }
            case "Dispose": return null;
        }
        return unimpl(m);
    }

    private static Object key(Object k) { return k instanceof Boxed ? ((Boxed) k).v : k; }

    private Object onSet(ClrMethod m, java.util.Set<Object> s, Object[] a) {
        switch (m.name) {
            case "Add": return s.add(a[1]) ? 1 : 0;
            case "Contains": return s.contains(a[1]) ? 1 : 0;
            case "Remove": return s.remove(a[1]) ? 1 : 0;
            case "get_Count": return s.size();
            case "Clear": s.clear(); return null;
            case "GetEnumerator": return new Enm(new ArrayList<>(s).iterator());
            case "UnionWith": for (Object o : iterable(a[1])) s.add(o); return null;
        }
        return unimpl(m);
    }

    private Object onEnum(ClrMethod m, Enm e, Object[] a) {
        switch (m.name) {
            case "MoveNext": if (e.it.hasNext()) { e.cur = e.it.next(); return 1; } return 0;
            case "get_Current": return e.cur;
            case "Dispose": case "Reset": return null;
        }
        return unimpl(m);
    }

    private Object onQueue(ClrMethod m, java.util.ArrayDeque<Object> q, Object[] a) {
        switch (m.name) {
            case "Enqueue": q.addLast(a[1]); return null;
            case "Dequeue": if (q.isEmpty()) throw vm.makeThrow("System.InvalidOperationException", "Queue empty."); return q.pollFirst();
            case "Peek": if (q.isEmpty()) throw vm.makeThrow("System.InvalidOperationException", "Queue empty."); return q.peekFirst();
            case "get_Count": return q.size();
            case "Clear": q.clear(); return null;
            case "Contains": return containsObj(q, a[1]) ? 1 : 0;
            case "GetEnumerator": return new Enm(new ArrayList<>(q).iterator());
            case "ToArray": return q.toArray(new Object[0]);
        }
        return unimpl(m);
    }

    private Object onStack(ClrMethod m, Stk s, Object[] a) {
        switch (m.name) {
            case "Push": s.d.addFirst(a[1]); return null;
            case "Pop": if (s.d.isEmpty()) throw vm.makeThrow("System.InvalidOperationException", "Stack empty."); return s.d.pollFirst();
            case "Peek": if (s.d.isEmpty()) throw vm.makeThrow("System.InvalidOperationException", "Stack empty."); return s.d.peekFirst();
            case "get_Count": return s.d.size();
            case "Clear": s.d.clear(); return null;
            case "Contains": return containsObj(s.d, a[1]) ? 1 : 0;
            case "GetEnumerator": return new Enm(new ArrayList<>(s.d).iterator());
            case "ToArray": return s.d.toArray(new Object[0]);
        }
        return unimpl(m);
    }

    private Object onRandom(ClrMethod m, java.util.Random r, Object[] a) {
        switch (m.name) {
            case "Next": return a.length == 1 ? (Object) (r.nextInt() & 0x7FFFFFFF) : a.length == 2 ? (Object) r.nextInt(Math.max(1, (Integer) a[1])) : (Object) ((Integer) a[1] + r.nextInt(Math.max(1, (Integer) a[2] - (Integer) a[1])));
            case "NextDouble": return r.nextDouble();
            case "NextBytes": { byte[] b = (byte[]) a[1]; r.nextBytes(b); return null; }
        }
        return unimpl(m);
    }

    private Object onWatch(ClrMethod m, Watch w, Object[] a) {
        switch (m.name) {
            case "Start": if (!w.run) { w.start = System.nanoTime(); w.run = true; } return null;
            case "Stop": if (w.run) { w.acc += System.nanoTime() - w.start; w.run = false; } return null;
            case "Reset": w.acc = 0; w.run = false; return null;
            case "Restart": w.acc = 0; w.start = System.nanoTime(); w.run = true; return null;
            case "get_ElapsedMilliseconds": return (w.acc + (w.run ? System.nanoTime() - w.start : 0)) / 1000000L;
            case "get_ElapsedTicks": return (w.acc + (w.run ? System.nanoTime() - w.start : 0)) / 100L;
            case "get_IsRunning": return w.run ? 1 : 0;
        }
        return unimpl(m);
    }

    private Object onArray(ClrMethod m, Object arr, Object[] a) {
        switch (m.name) {
            case "get_Length": case "get_Count": return Interp.arrLen(arr);
            case "get_LongLength": return (long) Interp.arrLen(arr);
            case "GetLength": return Interp.arrLen(arr);
            case "GetValue": return Interp.arrGet(arr, (Integer) a[1], true);
            case "SetValue": Interp.arrSet(arr, (Integer) a[2], a[1]); return null;
            case "Clone": { int n = Interp.arrLen(arr); Object c = java.lang.reflect.Array.newInstance(arr.getClass().getComponentType(), n); System.arraycopy(arr, 0, c, 0, n); return c; }
            case "GetEnumerator": return new Enm(iterable(arr).iterator());
            case "GetType": return vm.nativeType("System.Array");
            case "GetHashCode": return System.identityHashCode(arr);
            case "Equals": return arr == a[1] ? 1 : 0;
            case "CopyTo": System.arraycopy(arr, 0, a[1], (Integer) a[2], Interp.arrLen(arr)); return null;
            case "get_Item": return Interp.arrGet(arr, (Integer) a[1], true);
            case "ToString": return "System.Array";
        }
        return unimpl(m);
    }

    // ------------------------------------------------------------ statics
    private File path(String p) { return WinPath.resolve(vm.fsRoot, vm.cwd, p); }

    private Object onStatic(ClrMethod m, Object[] a) {
        String t = norm(m.owner.full), n = m.name;
        String key = t + "::" + n;
        switch (key) {
            // --- Console
            case "System.Console::WriteLine": vm.console.write((a.length == 0 ? "" : fmtArg(m, a, 0)) + "\n"); return null;
            case "System.Console::Write": vm.console.write(fmtArg(m, a, 0)); return null;
            case "System.Console::ReadLine": { StringBuilder0 x = null; java.lang.StringBuilder sb = new java.lang.StringBuilder(); int c; while ((c = vm.console.read()) >= 0) { if (c == '\n') return sb.toString(); if (c != '\r') sb.append((char) c); } return sb.length() == 0 ? null : sb.toString(); }
            case "System.Console::Read": return vm.console.read();
            case "System.Console::ReadKey": return 0;
            case "System.Console::set_Title": case "System.Console::Clear": case "System.Console::set_ForegroundColor": case "System.Console::set_BackgroundColor": case "System.Console::ResetColor": case "System.Console::set_OutputEncoding": case "System.Console::set_CursorVisible": return null;
            case "System.Console::get_Out": case "System.Console::get_Error": return ConsoleOut.INSTANCE;
            case "System.Console::get_ForegroundColor": return 7;
            case "System.Console::get_WindowWidth": return 80;
            // --- Environment
            case "System.Environment::Exit": throw new Interp.Exit((Integer) a[0]);
            case "System.Environment::get_CurrentDirectory": return vm.cwd;
            case "System.Environment::get_TickCount": return (int) System.currentTimeMillis();
            case "System.Environment::get_MachineName": return "WIN10-EMU";
            case "System.Environment::get_UserName": return "User";
            case "System.Environment::get_OSVersion": return "Microsoft Windows NT 10.0.19045.0";
            case "System.Environment::get_Is64BitOperatingSystem": case "System.Environment::get_Is64BitProcess": return 0;
            case "System.Environment::GetCommandLineArgs": return new Object[]{vm.main.name};
            case "System.Environment::GetFolderPath": return "C:\\Users\\Public";
            case "System.Environment::GetEnvironmentVariable": return null;
            case "System.Environment::get_ProcessorCount": return 4;
            case "System.GC::Collect": case "System.GC::SuppressFinalize": case "System.GC::WaitForPendingFinalizers": return null;
            case "System.GC::GetTotalMemory": return 1L << 20;
            case "System.Threading.Thread::Sleep": { try { Thread.sleep(Math.max(0, (Integer) a[0])); } catch (InterruptedException e) { throw new Interp.Stop(); } return null; }
            case "System.Threading.Monitor::Enter": case "System.Threading.Monitor::Exit": return null;
            case "System.Threading.Interlocked::Increment": { Ref r = (Ref) a[0]; Object v = r.get(); Object nv = v instanceof Long ? (Object) ((Long) v + 1) : (Object) ((Integer) v + 1); r.set(nv); return nv; }
            case "System.Threading.Interlocked::Decrement": { Ref r = (Ref) a[0]; Object v = r.get(); Object nv = v instanceof Long ? (Object) ((Long) v - 1) : (Object) ((Integer) v - 1); r.set(nv); return nv; }
            case "System.Threading.Interlocked::Exchange": { Ref r = (Ref) a[0]; Object old = r.get(); r.set(a[1]); return old; }
            case "System.Threading.Interlocked::CompareExchange": { Ref r = (Ref) a[0]; Object old = r.get(); if (valueEquals(old, a[2])) r.set(a[1]); return old; }
            // --- Object
            case "System.Object::ReferenceEquals": return a[0] == a[1] || (a[0] instanceof String && a[0].equals(a[1])) ? 1 : 0;
            case "System.Object::Equals": return valueEquals(a[0], a[1]) ? 1 : 0;
            case "System.Type::GetTypeFromHandle": return a[0];
            case "System.Type::op_Equality": return a[0] == a[1] ? 1 : 0;
            case "System.Type::op_Inequality": return a[0] != a[1] ? 1 : 0;
            case "System.Delegate::Combine": { if (a[0] == null) return a[1]; if (a[1] == null) return a[0]; return ((Delegate) a[0]).combine((Delegate) a[1]); }
            case "System.Delegate::Remove": { if (a[0] == null) return null; return ((Delegate) a[0]).remove((Delegate) a[1]); }
            case "System.Activator::CreateInstance": throw vm.makeThrow("System.NotSupportedException", "Activator.CreateInstance is not supported");
            // --- String
            case "System.String::Concat": { java.lang.StringBuilder sb = new java.lang.StringBuilder(); if (a.length == 1 && a[0] != null && a[0].getClass().isArray()) { for (Object o : iterable(a[0])) sb.append(str(o)); } else for (int i = 0; i < a.length; i++) sb.append(fmtArg(m, a, i)); return sb.toString(); }
            case "System.String::Format": { if (a[0] instanceof String && !(a.length == 2 && a[1] instanceof Object[] && false)) { Object[] rest = Arrays.copyOfRange(a, 1, a.length); if (rest.length == 1 && rest[0] instanceof Object[] && m.ptypes[m.ptypes.length - 1].equals("Object[]")) rest = (Object[]) rest[0]; return format((String) a[0], rest); } return format((String) a[1], Arrays.copyOfRange(a, 2, a.length)); }
            case "System.String::IsNullOrEmpty": return a[0] == null || ((String) a[0]).isEmpty() ? 1 : 0;
            case "System.String::IsNullOrWhiteSpace": return a[0] == null || ((String) a[0]).trim().isEmpty() ? 1 : 0;
            case "System.String::Join": { String sep = a[0] instanceof String ? (String) a[0] : String.valueOf((char) ((Number) a[0]).intValue()); java.lang.StringBuilder sb = new java.lang.StringBuilder(); boolean first = true; Iterable<Object> it = a.length == 2 ? iterable(a[1]) : Arrays.asList(a).subList(1, a.length); for (Object o : it) { if (!first) sb.append(sep); first = false; sb.append(str(o)); } return sb.toString(); }
            case "System.String::Compare": { String x = (String) a[0], y = (String) a[1]; boolean ic = a.length > 2 && ((a[2] instanceof Integer && ((Integer) a[2] == 1 || (Integer) a[2] == 3 || (Integer) a[2] == 5) && m.ptypes[2].equals("StringComparison")) || (m.ptypes.length > 2 && m.ptypes[2].equals("Boolean") && (Integer) a[2] != 0)); if (x == null || y == null) return x == y ? 0 : x == null ? -1 : 1; return Integer.signum(ic ? x.compareToIgnoreCase(y) : x.compareTo(y)); }
            case "System.String::CompareOrdinal": return Integer.signum(((String) a[0]).compareTo((String) a[1]));
            case "System.String::Equals": { if (a[0] == null || a[1] == null) return a[0] == a[1] ? 1 : 0; boolean ic = a.length > 2 && (Integer) a[2] != 0 && ((Integer) a[2] == 1 || (Integer) a[2] == 3 || (Integer) a[2] == 5); return (ic ? ((String) a[0]).equalsIgnoreCase((String) a[1]) : a[0].equals(a[1])) ? 1 : 0; }
            case "System.String::op_Equality": return a[0] == null || a[1] == null ? (a[0] == a[1] ? 1 : 0) : a[0].equals(a[1]) ? 1 : 0;
            case "System.String::op_Inequality": return a[0] == null || a[1] == null ? (a[0] == a[1] ? 0 : 1) : a[0].equals(a[1]) ? 0 : 1;
            case "System.String::Copy": case "System.String::Intern": return a[0];
            // --- Char
            case "System.Char::IsDigit": return Character.isDigit(ch(a, 0)) ? 1 : 0;
            case "System.Char::IsLetter": return Character.isLetter(ch(a, 0)) ? 1 : 0;
            case "System.Char::IsLetterOrDigit": return Character.isLetterOrDigit(ch(a, 0)) ? 1 : 0;
            case "System.Char::IsWhiteSpace": return Character.isWhitespace(ch(a, 0)) || ch(a, 0) == 0xA0 ? 1 : 0;
            case "System.Char::IsUpper": return Character.isUpperCase(ch(a, 0)) ? 1 : 0;
            case "System.Char::IsLower": return Character.isLowerCase(ch(a, 0)) ? 1 : 0;
            case "System.Char::IsPunctuation": return ".,;:!?'\"()[]{}-_/\\@#%&*".indexOf(ch(a, 0)) >= 0 ? 1 : 0;
            case "System.Char::ToUpper": case "System.Char::ToUpperInvariant": return (int) Character.toUpperCase(ch(a, 0));
            case "System.Char::ToLower": case "System.Char::ToLowerInvariant": return (int) Character.toLowerCase(ch(a, 0));
            case "System.Char::ToString": return String.valueOf(ch(a, 0));
            case "System.Char::GetNumericValue": return (double) Character.getNumericValue(ch(a, 0));
            // --- numbers
            case "System.Int32::Parse": case "System.Int32::TryParse": case "System.Int64::Parse": case "System.Int64::TryParse": case "System.Double::Parse": case "System.Double::TryParse":
            case "System.Single::Parse": case "System.Single::TryParse": case "System.UInt32::Parse": case "System.UInt32::TryParse": case "System.Byte::Parse": case "System.Byte::TryParse": case "System.Int16::Parse": case "System.Boolean::Parse": case "System.Boolean::TryParse": case "System.Decimal::Parse": case "System.Decimal::TryParse":
                return parse(t, n.startsWith("Try"), a);
            case "System.Int32::Equals": case "System.Int64::Equals": return valueEquals(a[0], a[1]) ? 1 : 0;
            case "System.Double::IsNaN": return Double.isNaN((Double) a[0]) ? 1 : 0;
            case "System.Double::IsInfinity": return Double.isInfinite((Double) a[0]) ? 1 : 0;
            case "System.Double::IsPositiveInfinity": return (Double) a[0] == Double.POSITIVE_INFINITY ? 1 : 0;
            case "System.Double::IsNegativeInfinity": return (Double) a[0] == Double.NEGATIVE_INFINITY ? 1 : 0;
            // --- Math
            case "System.Math::Abs": return a[0] instanceof Integer ? (Object) Math.abs((Integer) a[0]) : a[0] instanceof Long ? (Object) Math.abs((Long) a[0]) : (Object) Math.abs(((Number) a[0]).doubleValue());
            case "System.Math::Max": return minmax(a, true);
            case "System.Math::Min": return minmax(a, false);
            case "System.Math::Sqrt": return Math.sqrt(d(a[0]));
            case "System.Math::Pow": return Math.pow(d(a[0]), d(a[1]));
            case "System.Math::Floor": return Math.floor(d(a[0]));
            case "System.Math::Ceiling": return Math.ceil(d(a[0]));
            case "System.Math::Truncate": return d(a[0]) < 0 ? Math.ceil(d(a[0])) : Math.floor(d(a[0]));
            case "System.Math::Round": { double v = d(a[0]); int dg = a.length > 1 && m.ptypes[1].equals("Int32") ? (Integer) a[1] : 0; if (dg == 0) return Math.rint(v); BigDecimal bd = new BigDecimal(Double.toString(v)).setScale(dg, java.math.RoundingMode.HALF_EVEN); return bd.doubleValue(); }
            case "System.Math::Sin": return Math.sin(d(a[0]));
            case "System.Math::Cos": return Math.cos(d(a[0]));
            case "System.Math::Tan": return Math.tan(d(a[0]));
            case "System.Math::Asin": return Math.asin(d(a[0]));
            case "System.Math::Acos": return Math.acos(d(a[0]));
            case "System.Math::Atan": return Math.atan(d(a[0]));
            case "System.Math::Atan2": return Math.atan2(d(a[0]), d(a[1]));
            case "System.Math::Log": return a.length == 2 ? Math.log(d(a[0])) / Math.log(d(a[1])) : Math.log(d(a[0]));
            case "System.Math::Log10": return Math.log10(d(a[0]));
            case "System.Math::Exp": return Math.exp(d(a[0]));
            case "System.Math::Sign": { double v = d(a[0]); return v > 0 ? 1 : v < 0 ? -1 : 0; }
            case "System.Math::DivRem": { int x = (Integer) a[0], y = (Integer) a[1]; ((Ref) a[2]).set(x % y); return x / y; }
            // --- Convert
            case "System.Convert::ToInt32": return convertInt(a[0]);
            case "System.Convert::ToInt64": return a[0] instanceof String ? Long.parseLong((String) a[0]) : ((Number) unb(a[0])).longValue();
            case "System.Convert::ToDouble": return a[0] instanceof String ? Double.parseDouble((String) a[0]) : ((Number) unb(a[0])).doubleValue();
            case "System.Convert::ToString": return a.length == 2 && a[1] instanceof Integer && m.ptypes[1].equals("Int32") ? Integer.toString((Integer) a[0], (Integer) a[1]) : fmtArg(m, a, 0);
            case "System.Convert::ToBoolean": return a[0] instanceof String ? ("true".equalsIgnoreCase((String) a[0]) ? 1 : 0) : ((Number) unb(a[0])).doubleValue() != 0 ? 1 : 0;
            case "System.Convert::ToChar": return a[0] instanceof String ? (int) ((String) a[0]).charAt(0) : ((Number) unb(a[0])).intValue();
            case "System.Convert::ToByte": return (Integer) convertInt(a[0]) & 0xFF;
            case "System.Convert::ToBase64String": return java.util.Base64.getEncoder().encodeToString((byte[]) a[0]);
            case "System.Convert::FromBase64String": return java.util.Base64.getDecoder().decode((String) a[0]);
            // --- Array
            case "System.Array::Copy": { Object src = a[0], dst = a.length == 3 ? a[1] : a[2]; int si = a.length == 3 ? 0 : (Integer) a[1], di = a.length == 3 ? 0 : (Integer) a[3]; int len = (Integer) a[a.length - 1]; System.arraycopy(src, si, dst, di, len); return null; }
            case "System.Array::Reverse": { Object arr = a[0]; int n2 = Interp.arrLen(arr); for (int i = 0; i < n2 / 2; i++) { Object x = Interp.arrGet(arr, i, true), y = Interp.arrGet(arr, n2 - 1 - i, true); Interp.arrSet(arr, i, y); Interp.arrSet(arr, n2 - 1 - i, x); } return null; }
            case "System.Array::Sort": { Object arr = a[0]; int n2 = Interp.arrLen(arr); ArrayList<Object> l = new ArrayList<>(); for (int i = 0; i < n2; i++) l.add(Interp.arrGet(arr, i, true)); final Object cc = a.length > 1 && (a[a.length - 1] instanceof Delegate || a[a.length - 1] instanceof ClrObject) ? a[a.length - 1] : null; l.sort((x, y) -> cmpObjs(x, y, cc)); for (int i = 0; i < n2; i++) Interp.arrSet(arr, i, l.get(i)); return null; }
            case "System.Array::IndexOf": { Object arr = a[0]; for (int i = 0; i < Interp.arrLen(arr); i++) if (valueEquals(Interp.arrGet(arr, i, true), a[1])) return i; return -1; }
            case "System.Array::Clear": { Object arr = a[0]; for (int i = 0; i < (Integer) a[2]; i++) { Object z = Interp.arrGet(arr, (Integer) a[1] + i, true); Interp.arrSet(arr, (Integer) a[1] + i, z instanceof Double ? (Object) 0.0 : z instanceof Long ? (Object) 0L : z instanceof Integer ? (Object) 0 : null); } return null; }
            case "System.Array::Resize": { Ref r = (Ref) a[0]; Object old = r.get(); int nn = (Integer) a[1]; Object nw = old == null ? new Object[nn] : java.lang.reflect.Array.newInstance(old.getClass().getComponentType(), nn); if (old != null) System.arraycopy(old, 0, nw, 0, Math.min(nn, Interp.arrLen(old))); r.set(nw); return null; }
            case "System.Array::Empty": return new Object[0];
            case "System.Array::Exists": { Object arr = a[0]; for (int i = 0; i < Interp.arrLen(arr); i++) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{Interp.arrGet(arr, i, true)}))) return 1; return 0; }
            case "System.Buffer::BlockCopy": System.arraycopy(a[0], (Integer) a[1], a[2], (Integer) a[3], (Integer) a[4]); return null;
            case "System.Runtime.CompilerServices.RuntimeHelpers::InitializeArray": {
                Object arr = a[0]; FieldInfo fi = (FieldInfo) a[1];
                int len = Interp.arrLen(arr);
                int es = arr instanceof byte[] ? 1 : arr instanceof char[] || arr instanceof short[] ? 2 : arr instanceof long[] || arr instanceof double[] ? 8 : 4;
                byte[] d = fi.img.md.fieldRvaData(fi.row, len * es);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < len; i++) {
                    if (arr instanceof byte[]) ((byte[]) arr)[i] = d[i];
                    else if (arr instanceof char[]) ((char[]) arr)[i] = bb.getChar(i * 2);
                    else if (arr instanceof short[]) ((short[]) arr)[i] = bb.getShort(i * 2);
                    else if (arr instanceof int[]) ((int[]) arr)[i] = bb.getInt(i * 4);
                    else if (arr instanceof long[]) ((long[]) arr)[i] = bb.getLong(i * 8);
                    else if (arr instanceof float[]) ((float[]) arr)[i] = bb.getFloat(i * 4);
                    else if (arr instanceof double[]) ((double[]) arr)[i] = bb.getDouble(i * 8);
                }
                return null;
            }
            // --- IO
            case "System.IO.File::Exists": return path((String) a[0]).isFile() ? 1 : 0;
            case "System.IO.File::ReadAllText": try { return new String(Files.readAllBytes(path((String) a[0].toString()).toPath()), StandardCharsets.UTF_8); } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.FileNotFoundException", "Could not find file '" + a[0] + "'."); }
            case "System.IO.File::ReadAllBytes": try { return Files.readAllBytes(path((String) a[0]).toPath()); } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.FileNotFoundException", "Could not find file '" + a[0] + "'."); }
            case "System.IO.File::ReadAllLines": try { return Files.readAllLines(path((String) a[0]).toPath(), StandardCharsets.UTF_8).toArray(new Object[0]); } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.FileNotFoundException", "Could not find file '" + a[0] + "'."); }
            case "System.IO.File::WriteAllText": try { Files.write(path((String) a[0]).toPath(), ((String) a[1]).getBytes(StandardCharsets.UTF_8)); return null; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
            case "System.IO.File::WriteAllBytes": try { Files.write(path((String) a[0]).toPath(), (byte[]) a[1]); return null; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
            case "System.IO.File::AppendAllText": try { Files.write(path((String) a[0]).toPath(), ((String) a[1]).getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); return null; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
            case "System.IO.File::Delete": path((String) a[0]).delete(); return null;
            case "System.IO.Directory::Exists": return path((String) a[0]).isDirectory() ? 1 : 0;
            case "System.IO.Directory::CreateDirectory": path((String) a[0]).mkdirs(); return a[0];
            case "System.IO.Directory::GetCurrentDirectory": return vm.cwd;
            case "System.IO.Directory::GetFiles": case "System.IO.Directory::GetDirectories": {
                File dir = path((String) a[0]); ArrayList<Object> r = new ArrayList<>();
                File[] k = dir.listFiles();
                if (k != null) for (File f : k) if (f.isDirectory() == n.equals("GetDirectories")) r.add(WinPath.toWin(vm.fsRoot, f));
                return r.toArray(new Object[0]);
            }
            case "System.IO.Path::Combine": { String r = (String) a[0]; for (int i = 1; i < a.length; i++) { String p = (String) a[i]; r = p.matches("^[A-Za-z]:.*") ? p : r.endsWith("\\") || r.isEmpty() ? r + p : r + "\\" + p; } return r; }
            case "System.IO.Path::GetFileName": { String p = (String) a[0]; return p.substring(Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/')) + 1); }
            case "System.IO.Path::GetFileNameWithoutExtension": { String p = (String) a[0]; p = p.substring(Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/')) + 1); int i = p.lastIndexOf('.'); return i < 0 ? p : p.substring(0, i); }
            case "System.IO.Path::GetExtension": { String p = (String) a[0]; int i = p.lastIndexOf('.'); return i < 0 || i < p.lastIndexOf('\\') ? "" : p.substring(i); }
            case "System.IO.Path::GetDirectoryName": { String p = (String) a[0]; int i = p.lastIndexOf('\\'); return i < 0 ? "" : p.substring(0, i); }
            case "System.IO.Path::GetFullPath": return WinPath.absolute(vm.cwd, (String) a[0]);
            case "System.IO.Path::GetTempPath": return "C:\\Windows\\Temp\\";
            case "System.DateTime::get_Now": case "System.DateTime::get_UtcNow": return System.currentTimeMillis() * 10000L + 621355968000000000L;
            case "System.Diagnostics.Stopwatch::StartNew": { Watch w = new Watch(); w.start = System.nanoTime(); w.run = true; return w; }
            case "System.Diagnostics.Debug::WriteLine": case "System.Diagnostics.Debug::Write": case "System.Diagnostics.Debug::Assert": return null;
            case "System.Diagnostics.Process::Start": return null;
            case "System.GC::KeepAlive": return null;
            case "System.Collections.Generic.EqualityComparer::get_Default": return null;
            case "System.Linq.Enumerable::ToList": case "System.Linq.Enumerable::ToArray": { ArrayList<Object> l = new ArrayList<>(); for (Object o : iterable(a[0])) l.add(o); return n.equals("ToList") ? l : l.toArray(); }
            case "System.Linq.Enumerable::Count": { int c = 0; for (Object o : iterable(a[0])) if (a.length < 2 || truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) c++; return c; }
            case "System.Linq.Enumerable::Any": { for (Object o : iterable(a[0])) if (a.length < 2 || truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) return 1; return 0; }
            case "System.Linq.Enumerable::First": case "System.Linq.Enumerable::FirstOrDefault": { for (Object o : iterable(a[0])) if (a.length < 2 || truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) return o; if (n.equals("First")) throw vm.makeThrow("System.InvalidOperationException", "Sequence contains no elements"); return null; }
            case "System.Linq.Enumerable::Where": { ArrayList<Object> r = new ArrayList<>(); for (Object o : iterable(a[0])) if (truth(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}))) r.add(o); return r; }
            case "System.Linq.Enumerable::Select": { ArrayList<Object> r = new ArrayList<>(); for (Object o : iterable(a[0])) r.add(vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o})); return r; }
            case "System.Linq.Enumerable::Sum": { double s = 0; boolean isInt = true; for (Object o : iterable(a[0])) { Object v = a.length > 1 ? vm.interp.invokeDelegate((Delegate) a[1], new Object[]{o}) : o; if (v instanceof Double) isInt = false; s += ((Number) v).doubleValue(); } return isInt ? (Object) (int) s : (Object) s; }
            case "System.Linq.Enumerable::OrderBy": { ArrayList<Object> r = new ArrayList<>(); for (Object o : iterable(a[0])) r.add(o); final Delegate kf = (Delegate) a[1]; r.sort((x, y) -> compare(vm.interp.invokeDelegate(kf, new Object[]{x}), vm.interp.invokeDelegate(kf, new Object[]{y}))); return r; }
            case "System.Linq.Enumerable::Reverse": { ArrayList<Object> r = new ArrayList<>(); for (Object o : iterable(a[0])) r.add(o); java.util.Collections.reverse(r); return r; }
            case "System.Linq.Enumerable::Contains": { for (Object o : iterable(a[0])) if (valueEquals(o, a[1])) return 1; return 0; }
        }
        // enum helpers / unknown
        if (key.equals("System.Enum::ToObject")) return a[1];
        if (stub(m.owner.full + "::" + n + m.sigText)) return retDefault(m);
        throw vm.makeThrow("System.NotSupportedException", "Unimplemented .NET API: " + m.owner.full + "::" + n + m.sigText);
    }

    /** Minimal TextWriter for Console.Out / Console.Error. */
    public static final class ConsoleOut { static final ConsoleOut INSTANCE = new ConsoleOut(); }

    private static char ch(Object[] a, int i) {
        Object o = a[i];
        if (o instanceof String) return ((String) o).charAt((Integer) a[i + 1]);
        return (char) ((Number) o).intValue();
    }
    private static double d(Object o) { return ((Number) unb(o)).doubleValue(); }
    private static Object unb(Object o) { return o instanceof Character ? (Object) (int) (Character) o : Interp.unboxValue(o); }

    private Object minmax(Object[] a, boolean max) {
        if (a[0] instanceof Integer && a[1] instanceof Integer) return max ? Math.max((Integer) a[0], (Integer) a[1]) : Math.min((Integer) a[0], (Integer) a[1]);
        if (a[0] instanceof Double || a[1] instanceof Double) return max ? Math.max(d(a[0]), d(a[1])) : Math.min(d(a[0]), d(a[1]));
        long x = ((Number) a[0]).longValue(), y = ((Number) a[1]).longValue();
        return max ? Math.max(x, y) : Math.min(x, y);
    }

    private Object convertInt(Object o) {
        if (o instanceof String) { try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException e) { throw vm.makeThrow("System.FormatException", "Input string was not in a correct format."); } }
        Object v = unb(o);
        if (v instanceof Double) return (int) Math.rint((Double) v);
        return ((Number) v).intValue();
    }

    private Object parse(String type, boolean tryMode, Object[] a) {
        String s = (String) a[0];
        Object res;
        try {
            if (s == null) throw new NumberFormatException();
            String t = s.trim();
            switch (type) {
                case "System.Int32": case "System.Int16": case "System.Byte": res = Integer.parseInt(t.startsWith("+") ? t.substring(1) : t); break;
                case "System.UInt32": res = (int) Long.parseLong(t); break;
                case "System.Int64": res = Long.parseLong(t); break;
                case "System.Boolean": if (t.equalsIgnoreCase("true")) res = 1; else if (t.equalsIgnoreCase("false")) res = 0; else throw new NumberFormatException(); break;
                default: if (t.isEmpty() || t.endsWith("d") || t.endsWith("f") || t.contains("x")) throw new NumberFormatException(); res = Double.parseDouble(t);
            }
        } catch (NumberFormatException e) {
            if (tryMode) { Ref r = (Ref) a[a.length - 1]; r.set(type.equals("System.Int64") ? (Object) 0L : type.contains("Double") || type.contains("Single") || type.contains("Decimal") ? (Object) 0.0 : (Object) 0); return 0; }
            throw vm.makeThrow(s == null ? "System.ArgumentNullException" : "System.FormatException", s == null ? "Value cannot be null." : "Input string was not in a correct format.");
        }
        if (tryMode) { ((Ref) a[a.length - 1]).set(res); return 1; }
        return res;
    }

    // ------------------------------------------------------------ formatting
    Object[] argsOf(Object[] a, int from) { return Arrays.copyOfRange(a, from, a.length); }

    String fmtArg(ClrMethod m, Object[] a, int i) {
        if (i >= a.length) return "";
        String pt = m.ptypes != null && i < m.ptypes.length ? m.ptypes[i] : "Object";
        if (a.length == 1 && pt.equals("Char[]") && a[0] instanceof char[]) return new String((char[]) a[0]);
        return strTyped(a[i], pt);
    }

    String strTyped(Object v, String pt) {
        if (pt.equals("Boolean") && v instanceof Integer) return (Integer) v != 0 ? "True" : "False";
        if (pt.equals("Char") && v instanceof Integer) return String.valueOf((char) (int) (Integer) v);
        if (pt.equals("UInt32") && v instanceof Integer) return Long.toString((Integer) v & 0xFFFFFFFFL);
        if (pt.equals("UInt64") && v instanceof Long) return Long.toUnsignedString((Long) v);
        if (pt.equals("Single") && v instanceof Double) return fmtFloat((float) (double) (Double) v);
        if (pt.equals("Char[]") && v instanceof char[]) return new String((char[]) v);
        return str(v);
    }

    public String str(Object v) { return str(v, null, null); }

    String str(Object v, String ownerType, Object fmt) {
        if (v == null) return "";
        if (ownerType != null) {
            if (ownerType.equals("System.Boolean") && v instanceof Integer) v = (Integer) v != 0;
            else if (ownerType.equals("System.Char") && v instanceof Integer) v = (char) (int) (Integer) v;
            else if (ownerType.equals("System.Single") && v instanceof Double) v = (float) (double) (Double) v;
            else if (ownerType.equals("System.UInt32") && v instanceof Integer) v = (Integer) v & 0xFFFFFFFFL;
        }
        if (fmt instanceof String && !((String) fmt).isEmpty()) { String r = formatSpec(v, (String) fmt); if (r != null) return r; }
        if (v instanceof String) return (String) v;
        if (v instanceof Integer || v instanceof Long) return v.toString();
        if (v instanceof Double) return fmtDouble((Double) v);
        if (v instanceof Float) return fmtFloat((Float) v);
        if (v instanceof Boolean) return (Boolean) v ? "True" : "False";
        if (v instanceof Character) return v.toString();
        if (v instanceof Boxed) return enumName((Boxed) v);
        if (v instanceof SB) return v.toString();
        if (v instanceof char[]) return "System.Char[]";
        if (v instanceof ClrObject) {
            ClrObject o = (ClrObject) v;
            for (ClrType t = o.type; t != null && !t.isNative; t = t.base)
                for (ClrMethod mm : t.methods) if (mm.name.equals("ToString") && mm.nparams == 0 && !mm.isStatic) return String.valueOf(vm.invoke(mm, new Object[]{o}));
            if (o.type.isSubclassOf("System.Exception")) return o.type.full + ": " + (o.f[0] != null ? o.f[0] : defaultMessage(o));
            return o.type.full;
        }
        if (v instanceof ClrType) return ((ClrType) v).full;
        if (v instanceof ArrayList) return "System.Collections.Generic.List`1";
        return v.getClass().isArray() ? "System.Array" : v.toString();
    }

    private String enumName(Boxed b) {
        for (FieldInfo f : b.type.fieldList) {
            if (f.isStatic && (f.flags & 0x8000) != 0) {
                Object c = constantOf(f);
                if (c != null && valueEquals(c, b.v)) return f.name;
            }
        }
        return String.valueOf(b.v);
    }

    private Object constantOf(FieldInfo f) {
        Metadata md = f.img.md;
        for (int[] r : md.rows[Metadata.CONSTANT] == null ? new int[0][] : md.rows[Metadata.CONSTANT]) {
            int[] p = Metadata.decode(Metadata.C_HC, r[1]);
            if (p[0] == Metadata.FIELD && p[1] == f.row) {
                byte[] b = md.blob(r[2]);
                int t = r[0] & 0xFF;
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                switch (t) {
                    case 0x02: case 0x04: case 0x05: return (int) (b[0] & 0xFF);
                    case 0x03: case 0x06: case 0x07: return (int) bb.getShort(0);
                    case 0x08: case 0x09: return bb.getInt(0);
                    case 0x0A: case 0x0B: return bb.getLong(0);
                    case 0x0E: return new String(b, StandardCharsets.UTF_16LE);
                }
            }
        }
        return null;
    }

    public static String fmtDouble(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return (1 / d < 0) ? "-0" : "0";
        double ab = Math.abs(d);
        String s = Double.toString(d);
        if (ab >= 1e15 || ab < 1e-4) {
            BigDecimal bd = new BigDecimal(s);
            String u = bd.unscaledValue().abs().toString();
            int exp = u.length() - 1 - bd.scale();
            String digits = u.replaceAll("0+$", "");
            if (digits.isEmpty()) digits = "0";
            String mant = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
            return (d < 0 ? "-" : "") + mant + "E" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
        }
        return new BigDecimal(s).stripTrailingZeros().toPlainString();
    }

    public static String fmtFloat(float f) {
        if (Float.isNaN(f)) return "NaN";
        if (Float.isInfinite(f)) return f > 0 ? "Infinity" : "-Infinity";
        if (f == 0) return "0";
        float ab = Math.abs(f);
        if (ab >= 1e15f || ab < 1e-4f) return fmtDouble(Double.parseDouble(Float.toString(f)));
        return new BigDecimal(Float.toString(f)).stripTrailingZeros().toPlainString();
    }

    /** Composite format string: {index[,align][:spec]}. */
    public String format(String f, Object[] args) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == '{') {
                if (i + 1 < f.length() && f.charAt(i + 1) == '{') { sb.append('{'); i++; continue; }
                int e = f.indexOf('}', i);
                if (e < 0) throw vm.makeThrow("System.FormatException", "Input string was not in a correct format.");
                String body = f.substring(i + 1, e);
                String spec = null; int align = 0;
                int colon = body.indexOf(':');
                if (colon >= 0) { spec = body.substring(colon + 1); body = body.substring(0, colon); }
                int comma = body.indexOf(',');
                if (comma >= 0) { align = Integer.parseInt(body.substring(comma + 1).trim()); body = body.substring(0, comma); }
                int idx = Integer.parseInt(body.trim());
                if (idx >= args.length) throw vm.makeThrow("System.FormatException", "Index (zero based) must be greater than or equal to zero and less than the size of the argument list.");
                String s = str(args[idx], null, spec);
                if (align > 0) s = repeat(' ', align - s.length()) + s; else if (align < 0) s = s + repeat(' ', -align - s.length());
                sb.append(s);
                i = e;
            } else if (c == '}' && i + 1 < f.length() && f.charAt(i + 1) == '}') { sb.append('}'); i++; }
            else sb.append(c);
        }
        return sb.toString();
    }

    private String formatSpec(Object v, String spec) {
        if (spec.isEmpty()) return null;
        char k = Character.toUpperCase(spec.charAt(0));
        String digs = spec.substring(1);
        boolean simple = spec.length() == 1 || digs.matches("\\d+");
        if (v instanceof Boxed) v = ((Boxed) v).v;
        if (v instanceof Character) v = (int) (Character) v;
        if (simple && (v instanceof Number)) {
            int p = digs.isEmpty() ? -1 : Integer.parseInt(digs);
            Number n = (Number) v;
            boolean isInt = v instanceof Integer || v instanceof Long;
            switch (k) {
                case 'D': { String s = isInt ? Long.toString(Math.abs(n.longValue())) : null; if (s == null) return null; while (p > 0 && s.length() < p) s = "0" + s; return (n.longValue() < 0 ? "-" : "") + s; }
                case 'X': { String s = v instanceof Long ? Long.toHexString((Long) v) : Integer.toHexString(n.intValue()); if (spec.charAt(0) == 'x') { } else s = s.toUpperCase(); while (p > 0 && s.length() < p) s = "0" + s; return s; }
                case 'F': case 'N': {
                    int dec = p < 0 ? 2 : p;
                    BigDecimal bd = new BigDecimal(isInt ? n.toString() : Double.toString(n.doubleValue())).setScale(dec, java.math.RoundingMode.HALF_EVEN);
                    // .NET rounds half away from zero for formatting
                    bd = new BigDecimal(isInt ? n.toString() : Double.toString(n.doubleValue())).setScale(dec, java.math.RoundingMode.HALF_UP);
                    String s = bd.toPlainString();
                    if (k == 'N') { String ip = s, fp = ""; int dot = s.indexOf('.'); if (dot >= 0) { ip = s.substring(0, dot); fp = s.substring(dot); } boolean neg = ip.startsWith("-"); if (neg) ip = ip.substring(1); java.lang.StringBuilder g = new java.lang.StringBuilder(); for (int i = 0; i < ip.length(); i++) { if (i > 0 && (ip.length() - i) % 3 == 0) g.append(','); g.append(ip.charAt(i)); } s = (neg ? "-" : "") + g + fp; }
                    return s;
                }
                case 'P': { int dec = p < 0 ? 2 : p; return new BigDecimal(Double.toString(n.doubleValue() * 100)).setScale(dec, java.math.RoundingMode.HALF_UP).toPlainString() + " %"; }
                case 'E': { int dec = p < 0 ? 6 : p; String s = String.format("%." + dec + "E", n.doubleValue()); return s.replaceAll("E([+-])(\\d)$", "E$10$2").replaceAll("E([+-])(\\d\\d)$", "E$10$2"); }
                case 'G': return null;
                case 'C': { return "$" + new BigDecimal(Double.toString(n.doubleValue())).setScale(p < 0 ? 2 : p, java.math.RoundingMode.HALF_UP).toPlainString(); }
            }
            return null;
        }
        if (v instanceof Number && !simple) {
            // custom numeric format: 0, #, ., ,
            double dv = ((Number) v).doubleValue();
            int dot = spec.indexOf('.');
            String ip = dot < 0 ? spec : spec.substring(0, dot), fp = dot < 0 ? "" : spec.substring(dot + 1);
            int dec = 0; for (char c : fp.toCharArray()) if (c == '0' || c == '#') dec++;
            BigDecimal bd = new BigDecimal(Double.toString(Math.abs(dv))).setScale(dec, java.math.RoundingMode.HALF_UP);
            String s = bd.toPlainString();
            String si = s, sf = ""; int sd = s.indexOf('.'); if (sd >= 0) { si = s.substring(0, sd); sf = s.substring(sd + 1); }
            int minInt = 0; for (char c : ip.toCharArray()) if (c == '0') minInt++;
            while (si.length() < minInt) si = "0" + si;
            if (si.equals("0") && minInt == 0) si = "";
            if (ip.contains(",")) { java.lang.StringBuilder g = new java.lang.StringBuilder(); for (int i = 0; i < si.length(); i++) { if (i > 0 && (si.length() - i) % 3 == 0) g.append(','); g.append(si.charAt(i)); } si = g.toString(); }
            int minF = 0; for (char c : fp.toCharArray()) if (c == '0') minF++;
            while (sf.length() > minF && sf.endsWith("0")) sf = sf.substring(0, sf.length() - 1);
            String out = si + (sf.isEmpty() ? "" : "." + sf);
            return (dv < 0 && !out.replace("0", "").replace(".", "").isEmpty() ? "-" : "") + out;
        }
        return null;
    }

    // ------------------------------------------------------------ equality / compare
    public boolean valueEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Boxed) a = ((Boxed) a).v;
        if (b instanceof Boxed) b = ((Boxed) b).v;
        if (a instanceof ClrObject) {
            Object r = vm.callByName(a, "Equals", b);
            return r instanceof Integer && (Integer) r != 0;
        }
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
            return ((Number) a).longValue() == ((Number) b).longValue();
        }
        return a.equals(b);
    }

    public int compare(Object a, Object b) {
        if (a instanceof Boxed) a = ((Boxed) a).v;
        if (b instanceof Boxed) b = ((Boxed) b).v;
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        if (a instanceof String && b instanceof String) return Integer.signum(((String) a).compareTo((String) b));
        if (a instanceof Character) a = (int) (Character) a;
        if (b instanceof Character) b = (int) (Character) b;
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            return Long.compare(((Number) a).longValue(), ((Number) b).longValue());
        }
        if (a instanceof ClrObject) return (Integer) vm.callByName(a, "CompareTo", b);
        return 0;
    }
}
