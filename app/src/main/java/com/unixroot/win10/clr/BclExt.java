package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;
import com.unixroot.win10.core.WinPath;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** More of the base class library: threads, streams, text readers/writers, encodings, regex, reflection stubs. */
public final class BclExt implements Bcl.Ext {
    private final Vm vm;
    public BclExt(Vm vm) { this.vm = vm; }

    // ---- object kinds
    static final class Thr { Delegate d; String name = ""; Thread t; volatile boolean started; boolean bg; int id; Object arg; }
    static final class Strm {
        byte[] buf = new byte[0]; int len; int pos; File file; boolean write, read = true, closed;
        void ensure(int n) { if (n > buf.length) buf = Arrays.copyOf(buf, Math.max(n, buf.length * 2 + 16)); }
        void put(byte[] b, int off, int cnt) { ensure(pos + cnt); System.arraycopy(b, off, buf, pos, cnt); pos += cnt; if (pos > len) len = pos; }
        void flush() { if (file != null && write) try { Files.write(file.toPath(), Arrays.copyOf(buf, len)); } catch (java.io.IOException e) { /* ignore */ } }
    }
    static final class Rdr { final Strm s; final String text; int p; Rdr(Strm s, Charset cs) { this.s = s; text = new String(s.buf, 0, s.len, cs); p = new String(s.buf, 0, Math.min(s.pos, s.len), cs).length(); } }
    static final class Wtr { final Strm s; final Charset cs; final boolean owns; Wtr(Strm s, Charset cs, boolean o) { this.s = s; this.cs = cs; owns = o; } }
    static final class Enc { final Charset cs; Enc(Charset cs) { this.cs = cs; } }
    static final class Rx { final Pattern p; Rx(Pattern p) { this.p = p; } }
    static final class Mt { final Matcher m; final String in; boolean ok; Mt(Matcher m, String in) { this.m = m; this.in = in; } }
    static final class Grp { final String v; final boolean ok; final int idx; Grp(String v, boolean ok, int idx) { this.v = v; this.ok = ok; this.idx = idx; } }
    static final class AsmObj { final Image img; AsmObj(Image i) { img = i; } }
    static final class Info { final String kind; final File f; final String win; Info(String k, File f, String win) { kind = k; this.f = f; this.win = win; } }
    static final class Req { final String url; String method = "GET"; byte[] body; java.util.HashMap<String, String> headers = new java.util.HashMap<>(); int status; Strm resp; String ctype = ""; Req(String u) { url = u; } }
    static final class Misc { final String kind; Misc(String k) { kind = k; } }

    private final java.util.IdentityHashMap<Thread, Thr> threads = new java.util.IdentityHashMap<>();
    private int nextId = 2;

    private File path(Object p) { return WinPath.resolve(vm.fsRoot, vm.cwd, (String) p); }
    private static Object bool(boolean b) { return b ? 1 : 0; }
    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    @Override public Object getStatic(Vm vm, FieldInfo f) {
        String k = f.owner.full + "::" + f.name;
        switch (k) {
            case "System.Text.Encoding::UTF8": return new Enc(StandardCharsets.UTF_8);
            case "System.Text.Encoding::ASCII": return new Enc(StandardCharsets.US_ASCII);
            case "System.Text.Encoding::Unicode": return new Enc(StandardCharsets.UTF_16LE);
            case "System.Text.Encoding::UTF32": return new Enc(Charset.forName("UTF-32LE"));
            case "System.Text.Encoding::Default": case "System.Text.Encoding::Latin1": return new Enc(StandardCharsets.ISO_8859_1);
            case "System.IO.Stream::Null": return new Strm();
            case "System.IO.FileMode::CreateNew": return 1; case "System.IO.FileMode::Create": return 2; case "System.IO.FileMode::Open": return 3;
            case "System.IO.FileMode::OpenOrCreate": return 4; case "System.IO.FileMode::Truncate": return 5; case "System.IO.FileMode::Append": return 6;
            case "System.IO.FileAccess::Read": return 1; case "System.IO.FileAccess::Write": return 2; case "System.IO.FileAccess::ReadWrite": return 3;
            case "System.IO.FileShare::Read": return 1; case "System.IO.FileShare::None": return 0; case "System.IO.FileShare::ReadWrite": return 3;
            case "System.IO.SeekOrigin::Begin": return 0; case "System.IO.SeekOrigin::Current": return 1; case "System.IO.SeekOrigin::End": return 2;
            case "System.IO.SearchOption::TopDirectoryOnly": return 0; case "System.IO.SearchOption::AllDirectories": return 1;
            case "System.Text.RegularExpressions.RegexOptions::IgnoreCase": return 1; case "System.Text.RegularExpressions.RegexOptions::Multiline": return 2;
            case "System.Text.RegularExpressions.RegexOptions::Compiled": return 8; case "System.Text.RegularExpressions.RegexOptions::None": return 0;
            case "System.StringSplitOptions::RemoveEmptyEntries": return 1; case "System.StringSplitOptions::None": return 0;
            case "System.Environment+SpecialFolder::Desktop": case "System.Environment/SpecialFolder::Desktop": return 0;
            case "System.AppDomain::CurrentDomain": return new Misc("AppDomain");
            case "System.Globalization.CultureInfo::InvariantCulture": return new Misc("Culture");
        }
        if (f.owner.full.startsWith("System.Environment/SpecialFolder")) return 0;
        if (f.owner.full.equals("System.Globalization.NumberStyles") || f.owner.full.startsWith("System.Globalization.")) return 0;
        if (f.owner.full.equals("System.IO.FileAttributes") || f.owner.full.equals("System.Threading.ThreadPriority") || f.owner.full.equals("System.Diagnostics.ProcessWindowStyle")) return 0;
        return Bcl.NONE;
    }

    @Override public Object call(Vm vm, ClrMethod m, Object[] a) {
        String owner = m.owner == null ? "" : m.owner.full, n = m.name;
        Object recv = m.hasThis && a.length > 0 ? (a[0] instanceof Ref ? ((Ref) a[0]).get() : a[0]) : null;
        if (m.ptypes == null) {
            String s = m.sigText.substring(1, m.sigText.length() - 1);
            m.ptypes = s.isEmpty() ? new String[0] : s.split(",(?![^<]*>)");
        }
        if (n.equals(".ctor")) { if (a[0] == null) return construct(m, owner, a); return recv instanceof ClrObject ? Bcl.NONE : Bcl.NONE; }
        if (!m.hasThis) return statics(m, owner, n, a);
        if (recv instanceof Thr) return onThread((Thr) recv, n, a);
        if (recv instanceof Strm) return onStream((Strm) recv, n, a);
        if (recv instanceof Rdr) return onReader((Rdr) recv, n, a);
        if (recv instanceof Wtr) return onWriter((Wtr) recv, n, a);
        if (recv instanceof Enc) return onEnc(((Enc) recv).cs, n, a);
        if (recv instanceof Rx) return onRegex((Rx) recv, n, a);
        if (recv instanceof Mt) return onMatch((Mt) recv, n, a);
        if (recv instanceof Grp) { Grp g = (Grp) recv; switch (n) { case "get_Value": return g.v; case "get_Success": return bool(g.ok); case "get_Index": return g.idx; case "get_Length": return g.v.length(); case "ToString": return g.v; } return Bcl.NONE; }
        if (recv instanceof AsmObj) return onAsm((AsmObj) recv, n, a);
        if (recv instanceof Info) return onInfo((Info) recv, n, a);
        if (recv instanceof Req) return onReq((Req) recv, n, a);
        if (recv instanceof Misc && ((Misc) recv).kind.equals("Net")) return onNet((Misc) recv, n, a);
        if (recv instanceof Misc && ((Misc) recv).kind.equals("Headers")) return null;
        if (recv instanceof Misc) return onMisc((Misc) recv, n, a);
        return Bcl.NONE;
    }

    private Object construct(ClrMethod m, String owner, Object[] a) {
        switch (owner) {
            case "System.Threading.Thread": { Thr t = new Thr(); t.d = (Delegate) a[1]; t.id = nextId++; return t; }
            case "System.IO.MemoryStream": { Strm s = new Strm(); s.write = false; if (a.length > 1 && a[1] instanceof byte[]) { s.buf = ((byte[]) a[1]).clone(); s.len = s.buf.length; } return s; }
            case "System.IO.FileStream": return openFile(path(a[1]), num(a[2]), a.length > 3 ? num(a[3]) : 3);
            case "System.IO.StreamReader": {
                Charset cs = StandardCharsets.UTF_8;
                for (int i = 1; i < a.length; i++) if (a[i] instanceof Enc) cs = ((Enc) a[i]).cs;
                Strm s = a[1] instanceof String ? openFile(path(a[1]), 3, 1) : (Strm) a[1];
                return new Rdr(s, cs);
            }
            case "System.IO.StreamWriter": {
                Charset cs = StandardCharsets.UTF_8; boolean app = false;
                for (int i = 2; i < a.length; i++) { if (a[i] instanceof Enc) cs = ((Enc) a[i]).cs; else if (a[i] instanceof Integer && m.ptypes[i - 1].equals("Boolean")) app = (Integer) a[i] != 0; }
                Strm s = a[1] instanceof String ? openFile(path(a[1]), app ? 6 : 2, 2) : (Strm) a[1];
                return new Wtr(s, cs, a[1] instanceof String);
            }
            case "System.IO.StringReader": { Strm s = new Strm(); s.buf = ((String) a[1]).getBytes(StandardCharsets.UTF_8); s.len = s.buf.length; return new Rdr(s, StandardCharsets.UTF_8); }
            case "System.Text.RegularExpressions.Regex": return new Rx(Pattern.compile(convertPattern((String) a[1]), a.length > 2 ? flags(num(a[2])) : 0));
            case "System.IO.FileInfo": return new Info("file", path(a[1]), (String) a[1]);
            case "System.IO.DirectoryInfo": return new Info("dir", path(a[1]), (String) a[1]);
            case "System.Diagnostics.Process": case "System.Diagnostics.ProcessStartInfo": return new Misc("Process");
            case "System.Text.UTF8Encoding": return new Enc(StandardCharsets.UTF_8);
            case "System.Globalization.CultureInfo": return new Misc("Culture");
            case "System.Version": { Misc v = new Misc("Version"); return v; }
            case "System.Net.WebClient": return new Misc("Net");
        }
        return Bcl.NONE;
    }

    private static int flags(int o) { return ((o & 1) != 0 ? Pattern.CASE_INSENSITIVE : 0) | ((o & 2) != 0 ? Pattern.MULTILINE : 0) | ((o & 16) != 0 ? Pattern.DOTALL : 0); }
    private static String convertPattern(String p) { return p.replace("(?<", "(?<").replace("\\z", "\\z"); }

    private Strm openFile(File f, int mode, int access) {
        Strm s = new Strm(); s.file = f; s.write = (access & 2) != 0;
        boolean exists = f.isFile();
        if ((mode == 3 || mode == 5) && !exists && mode == 3) throw vm.makeThrow("System.IO.FileNotFoundException", "Could not find file '" + f.getName() + "'.");
        if (exists && mode != 2 && mode != 5) try { s.buf = Files.readAllBytes(f.toPath()); s.len = s.buf.length; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
        if (mode == 6) s.pos = s.len;
        if (s.write && (mode == 2 || mode == 1 || !exists)) { if (f.getParentFile() != null) f.getParentFile().mkdirs(); s.flush(); }
        return s;
    }

    // ---- statics
    private Object statics(ClrMethod m, String owner, String n, Object[] a) {
        switch (owner + "::" + n) {
            case "System.Threading.Thread::get_CurrentThread": { Thr t = threads.get(Thread.currentThread()); if (t == null) { t = new Thr(); t.id = 1; t.name = "Main"; t.t = Thread.currentThread(); threads.put(Thread.currentThread(), t); } return t; }
            case "System.Threading.Thread::set_CurrentCulture": case "System.Threading.Thread::set_CurrentUICulture": return null;
            case "System.Threading.Thread::Yield": case "System.Threading.Thread::SpinWait": case "System.Threading.Thread::MemoryBarrier": return null;
            case "System.Globalization.CultureInfo::get_CurrentCulture": case "System.Globalization.CultureInfo::get_CurrentUICulture": case "System.Globalization.CultureInfo::get_InvariantCulture": case "System.Globalization.CultureInfo::get_InstalledUICulture": return new Misc("Culture");
            case "System.Globalization.CultureInfo::set_DefaultThreadCurrentCulture": case "System.Globalization.CultureInfo::set_DefaultThreadCurrentUICulture": return null;
            case "System.Text.Encoding::get_UTF8": return new Enc(StandardCharsets.UTF_8);
            case "System.Text.Encoding::get_ASCII": return new Enc(StandardCharsets.US_ASCII);
            case "System.Text.Encoding::get_Unicode": return new Enc(StandardCharsets.UTF_16LE);
            case "System.Text.Encoding::get_Default": return new Enc(StandardCharsets.ISO_8859_1);
            case "System.Text.Encoding::GetEncoding": return new Enc(StandardCharsets.UTF_8);
            case "System.AppDomain::get_CurrentDomain": return new Misc("AppDomain");
            case "System.Reflection.Assembly::GetExecutingAssembly": case "System.Reflection.Assembly::GetEntryAssembly": case "System.Reflection.Assembly::GetCallingAssembly": return new AsmObj(vm.main);
            case "System.Reflection.Assembly::Load": case "System.Reflection.Assembly::LoadFrom": case "System.Reflection.Assembly::LoadFile": return new AsmObj(vm.main);
            case "System.Diagnostics.Process::GetCurrentProcess": return new Misc("Process");
            case "System.Diagnostics.Process::Start": return new Misc("Process");
            case "System.Diagnostics.Process::GetProcessesByName": case "System.Diagnostics.Process::GetProcesses": return new Object[0];
            case "System.IO.File::OpenRead": return openFile(path(a[0]), 3, 1);
            case "System.IO.File::OpenWrite": return openFile(path(a[0]), 4, 2);
            case "System.IO.File::Create": return openFile(path(a[0]), 2, 3);
            case "System.IO.File::Open": return openFile(path(a[0]), num(a[1]), a.length > 2 && m.ptypes[2].equals("FileAccess") ? num(a[2]) : 3);
            case "System.IO.File::OpenText": return new Rdr(openFile(path(a[0]), 3, 1), StandardCharsets.UTF_8);
            case "System.IO.File::CreateText": return new Wtr(openFile(path(a[0]), 2, 2), StandardCharsets.UTF_8, true);
            case "System.IO.File::AppendText": return new Wtr(openFile(path(a[0]), 6, 2), StandardCharsets.UTF_8, true);
            case "System.IO.File::Copy": try { File dst = path(a[1]); if (dst.exists() && !(a.length > 2 && num(a[2]) != 0)) throw vm.makeThrow("System.IO.IOException", "The file '" + a[1] + "' already exists."); Files.copy(path(a[0]).toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); return null; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
            case "System.IO.File::Move": try { Files.move(path(a[0]).toPath(), path(a[1]).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); return null; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
            case "System.IO.File::GetLastWriteTime": case "System.IO.File::GetCreationTime": return path(a[0]).lastModified() * 10000L + 621355968000000000L;
            case "System.IO.File::ReadLines": try { return Files.readAllLines(path(a[0]).toPath(), StandardCharsets.UTF_8).toArray(new Object[0]); } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.FileNotFoundException", "Could not find file '" + a[0] + "'."); }
            case "System.IO.File::WriteAllLines": try { StringBuilder sb = new StringBuilder(); for (Object o : (Object[]) a[1]) sb.append(o).append("\r\n"); Files.write(path(a[0]).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8)); return null; } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); }
            case "System.IO.File::SetAttributes": case "System.IO.File::SetLastWriteTime": return null;
            case "System.IO.Directory::Delete": { File d = path(a[0]); if (a.length > 1 && num(a[1]) != 0) deleteRec(d); else d.delete(); return null; }
            case "System.IO.Directory::Move": path(a[0]).renameTo(path(a[1])); return null;
            case "System.IO.Directory::SetCurrentDirectory": vm.cwd = WinPath.absolute(vm.cwd, (String) a[0]); return null;
            case "System.IO.Directory::GetParent": { String p = (String) a[0]; int i = p.lastIndexOf('\\'); return new Info("dir", path(i < 0 ? p : p.substring(0, i)), i < 0 ? p : p.substring(0, i)); }
            case "System.IO.Path::GetRandomFileName": return "tmp" + Long.toHexString(System.nanoTime()) + ".tmp";
            case "System.IO.Path::GetTempFileName": return "C:\\Windows\\Temp\\tmp" + Long.toHexString(System.nanoTime()) + ".tmp";
            case "System.IO.Path::IsPathRooted": return bool(((String) a[0]).matches("^([A-Za-z]:)?[\\\\/].*|^[A-Za-z]:.*"));
            case "System.IO.Path::ChangeExtension": { String p = (String) a[0]; int i = p.lastIndexOf('.'); String e = (String) a[1]; return (i < 0 || i < p.lastIndexOf('\\') ? p : p.substring(0, i)) + (e == null || e.isEmpty() ? "" : e.startsWith(".") ? e : "." + e); }
            case "System.IO.Path::GetInvalidFileNameChars": return "\"<>|:*?\\/".toCharArray();
            case "System.IO.Path::GetInvalidPathChars": return "\"<>|".toCharArray();
            case "System.IO.Path::GetPathRoot": return "C:\\";
            case "System.Text.RegularExpressions.Regex::IsMatch": return bool(Pattern.compile(convertPattern((String) a[1]), a.length > 2 ? flags(num(a[2])) : 0).matcher((String) a[0]).find());
            case "System.Text.RegularExpressions.Regex::Match": { Matcher mm = Pattern.compile(convertPattern((String) a[1]), a.length > 2 ? flags(num(a[2])) : 0).matcher((String) a[0]); Mt mt = new Mt(mm, (String) a[0]); mt.ok = mm.find(); return mt; }
            case "System.Text.RegularExpressions.Regex::Replace": return Pattern.compile(convertPattern((String) a[1]), a.length > 3 ? flags(num(a[3])) : 0).matcher((String) a[0]).replaceAll(((String) a[2]).replaceAll("\\$(\\d+)", "\\$$1"));
            case "System.Text.RegularExpressions.Regex::Split": return Pattern.compile(convertPattern((String) a[1])).split((String) a[0], -1);
            case "System.Text.RegularExpressions.Regex::Escape": return Pattern.quote((String) a[0]).replaceAll("^\\\\Q|\\\\E$", "").replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1");
            case "System.Text.RegularExpressions.Regex::Matches": { Matcher mm = Pattern.compile(convertPattern((String) a[1]), a.length > 2 ? flags(num(a[2])) : 0).matcher((String) a[0]); ArrayList<Object> l = new ArrayList<>(); while (mm.find()) { Mt mt = new Mt(mm, (String) a[0]); mt.ok = true; Matcher snap = Pattern.compile(mm.pattern().pattern(), mm.pattern().flags()).matcher((String) a[0]); snap.find(mm.start()); Mt c = new Mt(snap, (String) a[0]); c.ok = true; l.add(c); } return l; }
            case "System.Environment::get_StackTrace": return "";
            case "System.Environment::GetFolderPath": { int f = num(a[0]); return f == 5 ? "C:\\Users\\Public\\Documents" : f == 0 || f == 16 ? "C:\\Users\\Public\\Desktop" : f == 28 || f == 26 ? "C:\\Users\\Public\\AppData" : "C:\\Users\\Public"; }
            case "System.Environment::GetEnvironmentVariable": { String k = (String) a[0]; return k.equalsIgnoreCase("TEMP") || k.equalsIgnoreCase("TMP") ? "C:\\Windows\\Temp" : k.equalsIgnoreCase("USERNAME") ? "User" : k.equalsIgnoreCase("USERPROFILE") ? "C:\\Users\\Public" : k.equalsIgnoreCase("SystemRoot") ? "C:\\Windows" : null; }
            case "System.Environment::ExpandEnvironmentVariables": return a[0];
            case "System.Environment::get_CommandLine": return "\"C:\\" + vm.main.name + ".exe\"";
            case "System.Environment::get_Version": case "System.Environment::get_SystemDirectory": return "C:\\Windows\\System32";
            case "System.Environment::FailFast": throw new Interp.Exit(-1);
            case "System.Diagnostics.Debugger::get_IsAttached": return 0;
            case "System.Diagnostics.Trace::WriteLine": case "System.Diagnostics.Trace::Write": return null;
            case "System.Threading.Monitor::TryEnter": return 1;
            case "System.Threading.Monitor::Pulse": case "System.Threading.Monitor::PulseAll": case "System.Threading.Monitor::Wait": return null;
            case "System.Threading.ThreadPool::QueueUserWorkItem": { final Delegate d = (Delegate) a[0]; final Object st = a.length > 1 ? a[1] : null; Thread t = new Thread(() -> { try { vm.interp.invokeDelegate(d, new Object[]{st}); } catch (RuntimeException e) { /* swallow */ } }); t.setDaemon(true); t.start(); return 1; }
            case "System.Convert::ToHexString": { StringBuilder sb = new StringBuilder(); for (byte b : (byte[]) a[0]) sb.append(String.format("%02X", b)); return sb.toString(); }
            case "System.BitConverter::ToString": { StringBuilder sb = new StringBuilder(); byte[] b = (byte[]) a[0]; for (int i = 0; i < b.length; i++) { if (i > 0) sb.append('-'); sb.append(String.format("%02X", b[i])); } return sb.toString(); }
            case "System.BitConverter::GetBytes": { Object v = a[0]; java.nio.ByteBuffer bb; if (v instanceof Long) { bb = java.nio.ByteBuffer.allocate(8); bb.order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong((Long) v); } else if (v instanceof Double) { bb = java.nio.ByteBuffer.allocate(8); bb.order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble((Double) v); } else { bb = java.nio.ByteBuffer.allocate(4); bb.order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(num(v)); } return bb.array(); }
            case "System.BitConverter::ToInt32": return java.nio.ByteBuffer.wrap((byte[]) a[0]).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(num(a[1]));
            case "System.BitConverter::get_IsLittleEndian": return 1;
            case "System.Security.Cryptography.MD5::Create": case "System.Security.Cryptography.SHA1::Create": case "System.Security.Cryptography.SHA256::Create": return new Misc(owner.substring(owner.lastIndexOf('.') + 1));
            case "System.Net.ServicePointManager::set_SecurityProtocol": case "System.Net.ServicePointManager::set_Expect100Continue": return null;
            case "System.Net.WebRequest::Create": case "System.Net.HttpWebRequest::Create": return new Req(a[0] instanceof String ? (String) a[0] : String.valueOf(a[0]));
            case "System.Guid::NewGuid": return java.util.UUID.randomUUID().toString();
            case "System.DateTime::get_Today": return (System.currentTimeMillis() / 86400000L * 86400000L) * 10000L + 621355968000000000L;
            case "System.DateTime::op_Subtraction": return a[0] instanceof Long && a[1] instanceof Long ? (Object) ((Long) a[0] - (Long) a[1]) : Bcl.NONE;
        }
        return Bcl.NONE;
    }

    private void deleteRec(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteRec(c); f.delete(); }

    // ---- threads
    private Object onThread(final Thr t, String n, Object[] a) {
        switch (n) {
            case "Start": {
                if (t.started) throw vm.makeThrow("System.Threading.ThreadStateException", "Thread is running or terminated; it cannot restart.");
                t.started = true; t.arg = a.length > 1 ? a[1] : null;
                Thread th = new Thread(() -> {
                    try { vm.interp.invokeDelegate(t.d, t.d.targets.get(0)[1] != null && ((ClrMethod) t.d.targets.get(0)[1]).nparams == 1 ? new Object[]{t.arg} : new Object[0]); }
                    catch (Interp.Stop | Interp.Exit e) { /* terminated */ }
                    catch (ClrThrow e) { vm.console.write("\nUnhandled Exception: " + Vm.describe(e.ex) + "\n"); }
                    catch (RuntimeException e) { vm.console.write("\n[thread error] " + e + "\n"); }
                });
                th.setDaemon(true); t.t = th; threads.put(th, t); th.start(); return null;
            }
            case "Join": { try { if (t.t != null) { if (a.length > 1 && a[1] instanceof Integer) { t.t.join(Math.max(1, (Integer) a[1])); return bool(!t.t.isAlive()); } t.t.join(); } } catch (InterruptedException e) { throw new Interp.Stop(); } return 1; }
            case "get_IsAlive": return bool(t.t != null && t.t.isAlive());
            case "get_Name": return t.name; case "set_Name": t.name = (String) a[1]; return null;
            case "get_IsBackground": return bool(t.bg); case "set_IsBackground": t.bg = num(a[1]) != 0; return null;
            case "get_ManagedThreadId": return t.id; case "get_IsThreadPoolThread": return 0;
            case "get_ThreadState": return t.started ? (t.t != null && t.t.isAlive() ? 0 : 16) : 8;
            case "set_Priority": case "SetApartmentState": case "set_CurrentCulture": case "set_CurrentUICulture": return null;
            case "Abort": case "Interrupt": if (t.t != null) t.t.interrupt(); return null;
            case "GetHashCode": return t.id;
        }
        return Bcl.NONE;
    }

    // ---- streams
    private Object onStream(Strm s, String n, Object[] a) {
        switch (n) {
            case "Read": { byte[] b = (byte[]) a[1]; int off = num(a[2]), cnt = Math.min(num(a[3]), s.len - s.pos); if (cnt <= 0) return 0; System.arraycopy(s.buf, s.pos, b, off, cnt); s.pos += cnt; return cnt; }
            case "ReadByte": return s.pos < s.len ? s.buf[s.pos++] & 0xFF : -1;
            case "Write": s.put((byte[]) a[1], num(a[2]), num(a[3])); return null;
            case "WriteByte": s.put(new byte[]{(byte) num(a[1])}, 0, 1); return null;
            case "get_Length": return (long) s.len; case "get_Position": return (long) s.pos; case "set_Position": s.pos = (int) (long) (Long) a[1]; return null;
            case "SetLength": s.ensure(num(a[1])); s.len = (int) ((Number) a[1]).longValue(); if (s.pos > s.len) s.pos = s.len; return null;
            case "Seek": { long off = ((Number) a[1]).longValue(); int o = num(a[2]); s.pos = (int) (o == 0 ? off : o == 1 ? s.pos + off : s.len + off); return (long) s.pos; }
            case "Flush": s.flush(); return null;
            case "Close": case "Dispose": s.flush(); s.closed = true; return null;
            case "get_CanRead": return bool(s.read); case "get_CanWrite": return bool(s.write || s.file == null); case "get_CanSeek": return 1;
            case "ToArray": return Arrays.copyOf(s.buf, s.len);
            case "GetBuffer": return s.buf;
            case "CopyTo": { Strm d = (Strm) a[1]; d.put(s.buf, s.pos, s.len - s.pos); s.pos = s.len; return null; }
            case "get_Name": return s.file == null ? "" : s.file.getName();
        }
        return Bcl.NONE;
    }

    private Object onReader(Rdr r, String n, Object[] a) {
        switch (n) {
            case "ReadToEnd": { String t = r.text.substring(r.p); r.p = r.text.length(); return t; }
            case "ReadLine": {
                if (r.p >= r.text.length()) return null;
                int e = r.p; while (e < r.text.length() && r.text.charAt(e) != '\n' && r.text.charAt(e) != '\r') e++;
                String line = r.text.substring(r.p, e);
                if (e < r.text.length() && r.text.charAt(e) == '\r') e++;
                if (e < r.text.length() && r.text.charAt(e) == '\n') e++;
                r.p = e; return line;
            }
            case "Read": if (a.length == 1) return r.p < r.text.length() ? (int) r.text.charAt(r.p++) : -1; else { char[] b = (char[]) a[1]; int c = 0; while (c < num(a[3]) && r.p < r.text.length()) b[num(a[2]) + c++] = r.text.charAt(r.p++); return c; }
            case "Peek": return r.p < r.text.length() ? (int) r.text.charAt(r.p) : -1;
            case "get_EndOfStream": return bool(r.p >= r.text.length());
            case "Close": case "Dispose": return null;
            case "get_BaseStream": return r.s;
        }
        return Bcl.NONE;
    }

    private Object onWriter(Wtr w, String n, Object[] a) {
        switch (n) {
            case "Write": case "WriteLine": {
                String t = a.length > 1 ? (a[1] instanceof char[] ? new String((char[]) a[1]) : fmtW(a)) : "";
                if (n.equals("WriteLine")) t += "\r\n";
                byte[] b = t.getBytes(w.cs); w.s.put(b, 0, b.length); return null;
            }
            case "Flush": w.s.flush(); return null;
            case "Close": case "Dispose": w.s.flush(); return null;
            case "get_BaseStream": return w.s;
            case "set_AutoFlush": case "set_NewLine": return null;
            case "get_NewLine": return "\r\n";
        }
        return Bcl.NONE;
    }

    private String fmtW(Object[] a) { if (a.length > 2 && a[1] instanceof String && a[2] != null) { Object[] rest = Arrays.copyOfRange(a, 2, a.length); if (rest.length == 1 && rest[0] instanceof Object[]) rest = (Object[]) rest[0]; return vm.bcl.format((String) a[1], rest); } return vm.bcl.str(a[1]); }

    private Object onEnc(Charset cs, String n, Object[] a) {
        switch (n) {
            case "GetBytes": return a[1] instanceof char[] ? new String((char[]) a[1]).getBytes(cs) : ((String) a[1]).getBytes(cs);
            case "GetString": return a.length > 2 ? new String((byte[]) a[1], num(a[2]), num(a[3]), cs) : new String((byte[]) a[1], cs);
            case "GetByteCount": return ((String) a[1]).getBytes(cs).length;
            case "GetChars": return new String((byte[]) a[1], cs).toCharArray();
            case "get_WebName": case "get_EncodingName": return cs.name();
            case "GetPreamble": return new byte[0];
        }
        return Bcl.NONE;
    }

    // ---- regex
    private Object onRegex(Rx r, String n, Object[] a) {
        switch (n) {
            case "IsMatch": return bool(r.p.matcher((String) a[1]).find());
            case "Match": { Matcher m = r.p.matcher((String) a[1]); Mt mt = new Mt(m, (String) a[1]); mt.ok = m.find(); return mt; }
            case "Matches": { ArrayList<Object> l = new ArrayList<>(); Matcher m = r.p.matcher((String) a[1]); while (m.find()) { Matcher s = r.p.matcher((String) a[1]); s.find(m.start()); Mt mt = new Mt(s, (String) a[1]); mt.ok = true; l.add(mt); } return l; }
            case "Replace": return r.p.matcher((String) a[1]).replaceAll(((String) a[2]).replaceAll("\\$(\\d+)", "\\$$1"));
            case "Split": return r.p.split((String) a[1], -1);
        }
        return Bcl.NONE;
    }

    private Object onMatch(Mt m, String n, Object[] a) {
        switch (n) {
            case "get_Success": return bool(m.ok);
            case "get_Value": return m.ok ? m.m.group() : "";
            case "get_Index": return m.ok ? m.m.start() : 0;
            case "get_Length": return m.ok ? m.m.group().length() : 0;
            case "NextMatch": { Mt x = new Mt(m.m, m.in); x.ok = m.m.find(); return x; }
            case "get_Groups": { ArrayList<Object> l = new ArrayList<>(); if (m.ok) for (int i = 0; i <= m.m.groupCount(); i++) l.add(new Grp(m.m.group(i) == null ? "" : m.m.group(i), m.m.group(i) != null, Math.max(0, m.m.start(i)))); return l; }
            case "ToString": return m.ok ? m.m.group() : "";
        }
        return Bcl.NONE;
    }

    // ---- reflection stubs
    private Object onAsm(AsmObj as, String n, Object[] a) {
        switch (n) {
            case "get_Location": case "get_CodeBase": return "C:\\" + as.img.name + ".exe";
            case "get_FullName": return as.img.name + ", Version=1.0.0.0, Culture=neutral, PublicKeyToken=null";
            case "GetName": { Misc v = new Misc("AssemblyName"); return v; }
            case "GetManifestResourceStream": { byte[] b = as.img.md.managedResource((String) a[1]); if (b == null) return null; Strm s = new Strm(); s.buf = b; s.len = b.length; return s; }
            case "GetManifestResourceNames": { ArrayList<Object> l = new ArrayList<>(); if (as.img.md.rows[Metadata.MANIFESTRES] != null) for (int[] r : as.img.md.rows[Metadata.MANIFESTRES]) l.add(as.img.md.string(r[2])); return l.toArray(); }
            case "GetTypes": return new Object[0];
            case "GetCustomAttributes": return new Object[0];
            case "get_EntryPoint": return null;
        }
        return Bcl.NONE;
    }

    private Object onInfo(Info i, String n, Object[] a) {
        switch (n) {
            case "get_Exists": return bool(i.kind.equals("file") ? i.f.isFile() : i.f.isDirectory());
            case "get_Name": return i.f.getName(); case "get_FullName": return WinPath.toWin(vm.fsRoot, i.f);
            case "get_Length": return i.f.length(); case "get_Extension": { String s = i.f.getName(); int k = s.lastIndexOf('.'); return k < 0 ? "" : s.substring(k); }
            case "get_DirectoryName": return WinPath.toWin(vm.fsRoot, i.f.getParentFile());
            case "get_LastWriteTime": case "get_CreationTime": return i.f.lastModified() * 10000L + 621355968000000000L;
            case "Delete": if (i.f.isDirectory()) deleteRec(i.f); else i.f.delete(); return null;
            case "Create": i.f.mkdirs(); return null;
            case "get_Parent": return new Info("dir", i.f.getParentFile(), "");
            case "GetFiles": case "GetDirectories": case "GetFileSystemInfos": { ArrayList<Object> l = new ArrayList<>(); File[] k = i.f.listFiles(); if (k != null) for (File c : k) if (n.equals("GetFileSystemInfos") || c.isDirectory() == n.equals("GetDirectories")) l.add(new Info(c.isDirectory() ? "dir" : "file", c, "")); return l.toArray(); }
            case "OpenRead": return openFile(i.f, 3, 1);
            case "CopyTo": try { Files.copy(i.f.toPath(), path(a[1]).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (java.io.IOException e) { /* ignore */ } return null;
            case "ToString": return WinPath.toWin(vm.fsRoot, i.f);
        }
        return Bcl.NONE;
    }

    private java.net.HttpURLConnection connect(String url, String method, byte[] body, java.util.Map<String, String> headers) throws java.io.IOException {
        java.net.Proxy px = java.net.Proxy.NO_PROXY;
        String t = com.unixroot.win10.core.WinSock.proxyType, h = com.unixroot.win10.core.WinSock.proxyHost;
        if (!t.isEmpty() && !h.isEmpty()) px = new java.net.Proxy(t.equals("socks") ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(h, com.unixroot.win10.core.WinSock.proxyPort));
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection(px);
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        if (!com.unixroot.win10.core.WinSock.proxyUser.isEmpty() && !px.equals(java.net.Proxy.NO_PROXY))
            c.setRequestProperty("Proxy-Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((com.unixroot.win10.core.WinSock.proxyUser + ":" + com.unixroot.win10.core.WinSock.proxyPass).getBytes("UTF-8")));
        if (headers != null) for (java.util.Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) { c.setDoOutput(true); c.getOutputStream().write(body); }
        return c;
    }

    private byte[] fetchBytes(String url, String method, byte[] body, java.util.Map<String, String> headers) {
        try {
            java.net.HttpURLConnection c = connect(url, method, body, headers);
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
            if (code >= 400) throw vm.makeThrow("System.Net.WebException", "The remote server returned an error: (" + code + ").");
            return bo.toByteArray();
        } catch (java.io.IOException e) { throw vm.makeThrow("System.Net.WebException", "Unable to connect to the remote server: " + e.getMessage()); }
    }

    private Object onNet(Misc m, String n, Object[] a) {
        switch (n) {
            case "DownloadString": return new String(fetchBytes((String) a[1], "GET", null, null), StandardCharsets.UTF_8);
            case "DownloadData": return fetchBytes((String) a[1], "GET", null, null);
            case "DownloadFile": try { Files.write(path(a[2]).toPath(), fetchBytes((String) a[1], "GET", null, null)); } catch (java.io.IOException e) { throw vm.makeThrow("System.IO.IOException", e.getMessage()); } return null;
            case "UploadString": return new String(fetchBytes((String) a[1], "POST", ((String) a[2]).getBytes(StandardCharsets.UTF_8), null), StandardCharsets.UTF_8);
            case "get_Headers": return new Misc("Headers"); case "set_Proxy": case "set_Credentials": case "set_Encoding": case "Dispose": return null;
        }
        return Bcl.NONE;
    }

    private Object onReq(Req r, String n, Object[] a) {
        switch (n) {
            case "set_Method": r.method = (String) a[1]; return null;
            case "get_Method": return r.method;
            case "set_ContentType": r.headers.put("Content-Type", (String) a[1]); return null;
            case "set_UserAgent": r.headers.put("User-Agent", (String) a[1]); return null;
            case "set_Accept": r.headers.put("Accept", (String) a[1]); return null;
            case "get_Headers": return new Misc("Headers");
            case "set_Timeout": case "set_Proxy": case "set_Credentials": case "set_AllowAutoRedirect": case "set_KeepAlive": case "set_ContentLength": case "set_Referer": case "set_CookieContainer": return null;
            case "GetRequestStream": { Strm s = new Strm(); s.write = true; r.resp = s; return s; }
            case "GetResponse": {
                try {
                    byte[] body = r.resp != null ? Arrays.copyOf(r.resp.buf, r.resp.len) : null;
                    java.net.HttpURLConnection c = connect(r.url, r.method, body, r.headers);
                    int code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    if (in != null) { byte[] b = new byte[8192]; int k; while ((k = in.read(b)) > 0) bo.write(b, 0, k); }
                    Req resp = new Req(r.url); resp.status = code; resp.ctype = String.valueOf(c.getContentType());
                    Strm s = new Strm(); s.buf = bo.toByteArray(); s.len = s.buf.length; resp.resp = s;
                    if (code >= 400) throw vm.makeThrow("System.Net.WebException", "The remote server returned an error: (" + code + ") " + c.getResponseMessage() + ".");
                    return resp;
                } catch (java.io.IOException e) { throw vm.makeThrow("System.Net.WebException", "Unable to connect to the remote server: " + e.getMessage()); }
            }
            case "GetResponseStream": return r.resp;
            case "get_StatusCode": return r.status;
            case "get_StatusDescription": return "OK";
            case "get_ContentType": return r.ctype;
            case "get_ContentLength": return (long) (r.resp == null ? 0 : r.resp.len);
            case "Close": case "Dispose": return null;
        }
        return Bcl.NONE;
    }

    private Object onMisc(Misc m, String n, Object[] a) {
        switch (m.kind) {
            case "AppDomain":
                switch (n) { case "get_BaseDirectory": return "C:\\"; case "get_FriendlyName": return vm.main.name; case "GetAssemblies": return new Object[]{new AsmObj(vm.main)}; case "get_SetupInformation": return new Misc("Setup"); }
                if (n.startsWith("add_") || n.startsWith("remove_")) return null;
                break;
            case "Culture":
                switch (n) { case "get_Name": return "en-US"; case "get_TwoLetterISOLanguageName": return "en"; case "get_NumberFormat": case "get_DateTimeFormat": return new Misc("Fmt"); case "get_DisplayName": case "ToString": return "English (United States)"; case "get_Parent": return m; case "get_IsNeutralCulture": return 0; }
                break;
            case "Fmt": switch (n) { case "get_NumberDecimalSeparator": return "."; case "get_NumberGroupSeparator": return ","; } break;
            case "Process":
                switch (n) { case "get_ProcessName": return vm.main.name; case "get_Id": return 1234; case "get_MainModule": return new Misc("Module"); case "get_StartInfo": return new Misc("Process"); case "Kill": case "WaitForExit": case "Start": case "Close": case "Dispose": return null; case "get_WorkingSet64": case "get_PrivateMemorySize64": return 1L << 25; case "get_HasExited": return 1; case "get_ExitCode": return 0; case "get_MainWindowHandle": return 0; }
                if (n.startsWith("set_")) return null;
                break;
            case "Module": if (n.equals("get_FileName")) return "C:\\" + vm.main.name + ".exe"; break;
            case "AssemblyName": switch (n) { case "get_Name": return vm.main.name; case "get_Version": return new Misc("Version"); case "get_FullName": return vm.main.name + ", Version=1.0.0.0"; } break;
            case "Version": switch (n) { case "ToString": return "1.0.0.0"; case "get_Major": return 1; case "get_Minor": case "get_Build": case "get_Revision": return 0; case "CompareTo": return 0; } break;
            case "Net": return null;
            case "MD5": case "SHA1": case "SHA256":
                if (n.equals("ComputeHash")) { try { byte[] in = a[1] instanceof Strm ? Arrays.copyOf(((Strm) a[1]).buf, ((Strm) a[1]).len) : (byte[]) a[1]; return java.security.MessageDigest.getInstance(m.kind.equals("MD5") ? "MD5" : m.kind.equals("SHA1") ? "SHA-1" : "SHA-256").digest(in); } catch (java.security.NoSuchAlgorithmException e) { return null; } }
                if (n.equals("Dispose") || n.equals("Clear")) return null;
                break;
        }
        if (n.equals("Dispose") || n.equals("Close")) return null;
        return Bcl.NONE;
    }
}
