package com.unixroot.win10.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Loads a 32-bit PE executable and runs it on {@link Cpu}, high-level-emulating a small Win32/CRT API set. */
public final class Emulator implements Cpu.Hook {

    public interface Console {
        void write(String s);
        /** Blocking read of one byte from stdin, -1 on EOF. */
        int read();
        void messageBox(String title, String text);
    }

    private static final int STACK_TOP = 0x7E100000, TEB = 0x7FFDE000, PEB = 0x7FFDF000;
    private static final int HEAP_BASE = 0x10000000, VIRT_BASE = 0x30000000;

    public final Memory mem = new Memory();
    public final Cpu cpu = new Cpu(mem);
    private final Console con;
    private final File root;
    public int exitCode;

    private int imageBase, heapPtr = HEAP_BASE, virtPtr = VIRT_BASE, lastError, cmdLinePtr;
    private final Map<String, Integer> stubByName = new HashMap<>();
    private final ArrayList<String> stubNames = new ArrayList<>();
    private final Map<Integer, RandomAccessFile> files = new HashMap<>();
    private int nextHandle = 0x100;
    private final Random rng = new Random(1);
    private final long startNanos = System.nanoTime();

    public Emulator(Console con, File root) {
        this.con = con;
        this.root = root;
        cpu.hook = this;
        stubNames.add("<exit>");
    }

    public void stop() { cpu.halted = true; }

    // ---------------- PE loading ----------------
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int u32(byte[] b, int o) { return u16(b, o) | (u16(b, o + 2) << 16); }

    public void load(byte[] b, String path, String args) {
        if (b.length < 0x40 || b[0] != 'M' || b[1] != 'Z') throw new EmuException("Not a valid Win32 application (no MZ header)");
        int e = u32(b, 0x3C);
        if (e <= 0 || e + 24 > b.length || u32(b, e) != 0x4550) throw new EmuException("Not a valid PE file");
        int machine = u16(b, e + 4);
        if (machine == 0x8664) throw new EmuException("64-bit (x64) programs are not supported yet - 32-bit only");
        if (machine != 0x14C) throw new EmuException("Unsupported machine type " + Integer.toHexString(machine));
        int nsec = u16(b, e + 6), optSize = u16(b, e + 20), opt = e + 24;
        if (u16(b, opt) != 0x10B) throw new EmuException("Not a PE32 image");
        int entry = u32(b, opt + 16);
        imageBase = u32(b, opt + 28);
        int hdrSize = u32(b, opt + 60);
        int importRva = u32(b, opt + 104);
        if (imageBase <= 0 || imageBase >= HEAP_BASE) throw new EmuException("Unsupported image base " + Integer.toHexString(imageBase));
        mem.write(imageBase, b, 0, Math.min(hdrSize, b.length));
        int sec = opt + optSize;
        for (int i = 0; i < nsec; i++, sec += 40) {
            int rva = u32(b, sec + 12), raw = u32(b, sec + 16), ptr = u32(b, sec + 20);
            if (ptr < 0 || ptr > b.length) continue;
            mem.write(imageBase + rva, b, ptr, Math.min(raw, b.length - ptr));
        }
        if (importRva != 0) {
            for (int d = imageBase + importRva; mem.r32(d + 12) != 0; d += 20) {
                int ilt = mem.r32(d) != 0 ? mem.r32(d) : mem.r32(d + 16), iat = mem.r32(d + 16);
                String dll = mem.cstr(imageBase + mem.r32(d + 12)).toLowerCase();
                for (int i = 0; ; i++) {
                    int v = mem.r32(imageBase + ilt + 4 * i);
                    if (v == 0) break;
                    String name = v < 0 ? dll + "#" + (v & 0xFFFF) : mem.cstr(imageBase + v + 2);
                    mem.w32(imageBase + iat + 4 * i, stubFor(name));
                }
            }
        }
        // stack, TEB, PEB
        cpu.r[Cpu.ESP] = STACK_TOP - 16;
        mem.w32(TEB, -1); mem.w32(TEB + 4, STACK_TOP); mem.w32(TEB + 8, STACK_TOP - 0x100000);
        mem.w32(TEB + 0x18, TEB); mem.w32(TEB + 0x30, PEB); mem.w32(PEB + 8, imageBase);
        cpu.fsBase = TEB;
        cmdLinePtr = alloc(path.length() + args.length() + 4);
        mem.putCstr(cmdLinePtr, path + (args.isEmpty() ? "" : " " + args));
        cpu.push(cpu.stubLo); // return address -> exit stub
        cpu.eip = imageBase + entry;
    }

    public int run() {
        try {
            while (!cpu.halted) cpu.step();
        } catch (EmuException ex) {
            con.write("\r\n[emulator] " + ex.getMessage() + "\r\n");
            exitCode = -1;
        } catch (RuntimeException ex) {
            con.write("\r\n[emulator] internal error: " + ex + "\r\n");
            exitCode = -1;
        }
        for (RandomAccessFile f : files.values()) try { f.close(); } catch (IOException ignored) { }
        return exitCode;
    }

    // ---------------- stubs ----------------
    private int stubFor(String name) {
        Integer a = stubByName.get(name);
        if (a != null) return a;
        stubNames.add(name);
        int addr = cpu.stubLo + 16 * (stubNames.size() - 1);
        stubByName.put(name, addr);
        return addr;
    }

    @Override
    public void onStub(Cpu c, int addr) {
        int idx = (addr - c.stubLo) / 16;
        if (idx == 0) { exitCode = c.r[Cpu.EAX]; c.halted = true; return; }
        String name = stubNames.get(idx);
        Integer nargs = Win32Table.nargs(name);
        if (nargs == null) throw new EmuException("Unimplemented API: " + name);
        int ret = api(name);
        if (c.halted) return;
        c.r[Cpu.EAX] = ret;
        c.eip = c.pop();
        if (Win32Table.isStdcall(name)) c.r[Cpu.ESP] += nargs * 4;
    }

    // ---------------- memory helpers ----------------
    private int alloc(int size) {
        int p = (heapPtr + 15) & ~15;
        mem.w32(p, size);
        heapPtr = p + 16 + ((size + 15) & ~15);
        return p + 16;
    }

    private int a(int i) { return cpu.arg(i); }

    private File resolve(String p) {
        String s = p.replace('\\', '/');
        if (s.length() >= 2 && s.charAt(1) == ':') s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return new File(root, s);
    }

    private void out(String s) { con.write(s); }

    private int writeFile(int h, int buf, int n, int pWritten) {
        byte[] data = mem.read(buf, n);
        if (h == 1 || h == 2 || h == 3) {
            StringBuilder sb = new StringBuilder(n);
            for (byte x : data) sb.append((char) (x & 0xFF));
            out(sb.toString());
        } else {
            RandomAccessFile f = files.get(h);
            if (f == null) { lastError = 6; return 0; }
            try { f.write(data); } catch (IOException ex) { lastError = 29; return 0; }
        }
        if (pWritten != 0) mem.w32(pWritten, n);
        return 1;
    }

    private int readFile(int h, int buf, int n, int pRead) {
        int got = 0;
        if (h == 0 || h == 1 || h == 2 || h == 3) {
            while (got < n) {
                int ch = con.read();
                if (ch < 0) break;
                mem.w8(buf + got++, ch);
                if (ch == '\n') break;
            }
        } else {
            RandomAccessFile f = files.get(h);
            if (f == null) { lastError = 6; return 0; }
            try {
                byte[] tmp = new byte[n];
                int k = f.read(tmp);
                if (k > 0) { mem.write(buf, tmp, 0, k); got = k; }
            } catch (IOException ex) { lastError = 29; return 0; }
        }
        if (pRead != 0) mem.w32(pRead, got);
        return 1;
    }

    // ---------------- printf ----------------
    private String format(String f, int argBase) {
        StringBuilder sb = new StringBuilder();
        int ai = argBase;
        for (int i = 0; i < f.length(); i++) {
            char ch = f.charAt(i);
            if (ch != '%') { sb.append(ch); continue; }
            i++;
            if (i >= f.length()) break;
            boolean left = false, zero = false, plus = false;
            for (; i < f.length(); i++) {
                char fl = f.charAt(i);
                if (fl == '-') left = true; else if (fl == '0') zero = true; else if (fl == '+') plus = true; else if (fl != ' ' && fl != '#') break;
            }
            int width = 0, prec = -1;
            if (i < f.length() && f.charAt(i) == '*') { width = a(ai++); i++; }
            else while (i < f.length() && Character.isDigit(f.charAt(i))) width = width * 10 + (f.charAt(i++) - '0');
            if (i < f.length() && f.charAt(i) == '.') {
                i++; prec = 0;
                if (i < f.length() && f.charAt(i) == '*') { prec = a(ai++); i++; }
                else while (i < f.length() && Character.isDigit(f.charAt(i))) prec = prec * 10 + (f.charAt(i++) - '0');
            }
            while (i < f.length() && (f.charAt(i) == 'l' || f.charAt(i) == 'h' || f.charAt(i) == 'I' || f.charAt(i) == 'z' || f.charAt(i) == 'j')) i++;
            if (i >= f.length()) break;
            char cv = f.charAt(i);
            String s;
            switch (cv) {
                case 'd': case 'i': { int v = a(ai++); s = (plus && v >= 0 ? "+" : "") + v; break; }
                case 'u': s = Long.toString(a(ai++) & 0xFFFFFFFFL); break;
                case 'x': s = Integer.toHexString(a(ai++)); break;
                case 'X': s = Integer.toHexString(a(ai++)).toUpperCase(); break;
                case 'p': s = String.format("%08X", a(ai++)); break;
                case 'o': s = Integer.toOctalString(a(ai++)); break;
                case 'c': s = String.valueOf((char) (a(ai++) & 0xFF)); break;
                case 's': { int p = a(ai++); s = p == 0 ? "(null)" : mem.cstr(p); if (prec >= 0 && prec < s.length()) s = s.substring(0, prec); break; }
                case 'f': case 'g': case 'e': case 'F': {
                    long bits = (a(ai) & 0xFFFFFFFFL) | ((long) a(ai + 1) << 32); ai += 2;
                    s = String.format("%." + (prec < 0 ? 6 : prec) + "f", Double.longBitsToDouble(bits)); break; }
                case '%': s = "%"; break;
                default: s = "%" + cv;
            }
            while (s.length() < width) {
                if (left) s = s + " ";
                else if (zero && cv != 's' && cv != 'c') s = (s.startsWith("-") ? "-" + "0" + s.substring(1) : "0" + s);
                else s = " " + s;
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // ---------------- API implementations ----------------
    private int api(String n) {
        switch (n) {
            // ---- kernel32 ----
            case "ExitProcess": case "exit": case "_exit": case "TerminateProcess":
                exitCode = n.equals("TerminateProcess") ? a(1) : a(0); cpu.halted = true; return 0;
            case "GetStdHandle": { int k = a(0); return k == -10 ? 1 : k == -11 ? 2 : 3; }
            case "WriteFile": return writeFile(a(0), a(1), a(2), a(3));
            case "WriteConsoleA": return writeFile(a(0), a(1), a(2), a(3));
            case "ReadFile": return readFile(a(0), a(1), a(2), a(3));
            case "ReadConsoleA": return readFile(a(0), a(1), a(2), a(3));
            case "GetCommandLineA": return cmdLinePtr;
            case "GetModuleHandleA": case "GetModuleHandleW": return a(0) == 0 ? imageBase : 0x70000000 + (mem.cstr(a(0)).toLowerCase().hashCode() & 0xFFFF) * 0x10000;
            case "LoadLibraryA": return 0x70000000 + (mem.cstr(a(0)).toLowerCase().hashCode() & 0xFFFF) * 0x10000;
            case "GetProcAddress": { int p = a(1); return stubFor(p >>> 16 == 0 ? "#" + p : mem.cstr(p)); }
            case "GetProcessHeap": return 0x00500000;
            case "HeapAlloc": { int p = alloc(a(2)); return p; }
            case "HeapFree": return 1;
            case "HeapReAlloc": { int old = a(2), nsz = a(3); int p = alloc(nsz); if (old != 0) { int osz = mem.r32(old - 16); mem.write(p, mem.read(old, Math.min(osz, nsz)), 0, Math.min(osz, nsz)); } return p; }
            case "HeapSize": return mem.r32(a(2) - 16);
            case "VirtualAlloc": { int size = (a(1) + 0xFFF) & ~0xFFF; int p = a(0) != 0 ? a(0) : virtPtr; if (a(0) == 0) virtPtr += size + 0x1000; return p; }
            case "VirtualFree": return 1;
            case "GetLastError": return lastError;
            case "SetLastError": lastError = a(0); return 0;
            case "Sleep": try { Thread.sleep(Math.min(a(0) & 0xFFFFFFFFL, 5000)); } catch (InterruptedException ignored) { } return 0;
            case "GetTickCount": return (int) ((System.nanoTime() - startNanos) / 1000000L) + 100000;
            case "GetCurrentProcessId": return 1234;
            case "GetCurrentThreadId": return 5678;
            case "GetCurrentProcess": return -1;
            case "GetVersion": return 0x0000000A | (0 << 8) | (19045 << 16);
            case "IsDebuggerPresent": return 0;
            case "GetFileType": return 2;
            case "SetConsoleTitleA": case "SetConsoleTextAttribute": case "SetConsoleCtrlHandler": return 1;
            case "lstrlenA": case "strlen": return mem.cstr(a(0)).length();
            case "lstrcpyA": case "strcpy": mem.putCstr(a(0), mem.cstr(a(1))); return a(0);
            case "lstrcmpA": case "strcmp": return Integer.signum(mem.cstr(a(0)).compareTo(mem.cstr(a(1))));
            case "GetSystemTimeAsFileTime": { long t = (System.currentTimeMillis() + 11644473600000L) * 10000L; mem.w32(a(0), (int) t); mem.w32(a(0) + 4, (int) (t >>> 32)); return 0; }
            case "QueryPerformanceCounter": { long t = System.nanoTime(); mem.w32(a(0), (int) t); mem.w32(a(0) + 4, (int) (t >>> 32)); return 1; }
            case "QueryPerformanceFrequency": mem.w32(a(0), 1000000000); mem.w32(a(0) + 4, 0); return 1;
            case "CreateFileA": {
                String path = mem.cstr(a(0)); int access = a(1), disp = a(4);
                File f = resolve(path);
                try {
                    if (disp == 1 && f.exists()) { lastError = 80; return -1; }
                    if ((disp == 3 || disp == 4) && !f.exists() && disp == 3) { lastError = 2; return -1; }
                    if (f.getParentFile() != null) f.getParentFile().mkdirs();
                    RandomAccessFile raf = new RandomAccessFile(f, (access & 0x40000000) != 0 ? "rw" : "r");
                    if (disp == 2) raf.setLength(0);
                    int h = nextHandle++;
                    files.put(h, raf);
                    return h;
                } catch (IOException ex) { lastError = 2; return -1; }
            }
            case "CloseHandle": { RandomAccessFile f = files.remove(a(0)); if (f != null) try { f.close(); } catch (IOException ignored) { } return 1; }
            case "GetFileSize": { RandomAccessFile f = files.get(a(0)); try { return f == null ? -1 : (int) f.length(); } catch (IOException ex) { return -1; } }
            case "DeleteFileA": return resolve(mem.cstr(a(0))).delete() ? 1 : 0;
            case "GetEnvironmentVariableA": return 0;
            case "GetACP": return 1252;
            case "GetLocalTime": case "GetSystemTime": { java.util.Calendar c = java.util.Calendar.getInstance(); int p = a(0);
                mem.w16(p, c.get(1)); mem.w16(p + 2, c.get(2) + 1); mem.w16(p + 4, c.get(7) - 1); mem.w16(p + 6, c.get(5));
                mem.w16(p + 8, c.get(11)); mem.w16(p + 10, c.get(12)); mem.w16(p + 12, c.get(13)); mem.w16(p + 14, c.get(14)); return 0; }
            // ---- user32 ----
            case "MessageBoxA": con.messageBox(mem.cstr(a(2)), mem.cstr(a(1))); return 1;
            // ---- msvcrt ----
            case "puts": out(mem.cstr(a(0)) + "\n"); return 1;
            case "putchar": out(String.valueOf((char) (a(0) & 0xFF))); return a(0);
            case "getchar": case "_getch": return con.read();
            case "printf": { String s = format(mem.cstr(a(0)), 1); out(s); return s.length(); }
            case "sprintf": { String s = format(mem.cstr(a(1)), 2); mem.putCstr(a(0), s); return s.length(); }
            case "malloc": return alloc(a(0));
            case "calloc": return alloc(a(0) * a(1));
            case "realloc": { int old = a(0), nsz = a(1); int p = alloc(nsz); if (old != 0) { int osz = mem.r32(old - 16); mem.write(p, mem.read(old, Math.min(osz, nsz)), 0, Math.min(osz, nsz)); } return p; }
            case "free": return 0;
            case "memcpy": case "memmove": { int len = a(2); mem.write(a(0), mem.read(a(1), len), 0, len); return a(0); }
            case "memset": { int p = a(0), v = a(1), len = a(2); for (int i = 0; i < len; i++) mem.w8(p + i, v); return p; }
            case "memcmp": { int x = a(0), y = a(1), len = a(2); for (int i = 0; i < len; i++) { int d = mem.r8(x + i) - mem.r8(y + i); if (d != 0) return d; } return 0; }
            case "atoi": try { return Integer.parseInt(mem.cstr(a(0)).trim()); } catch (NumberFormatException ex) { return 0; }
            case "abs": return Math.abs(a(0));
            case "rand": return rng.nextInt(32768);
            case "srand": rng.setSeed(a(0)); return 0;
            case "time": { int t = (int) (System.currentTimeMillis() / 1000); if (a(0) != 0) mem.w32(a(0), t); return t; }
            case "toupper": return Character.toUpperCase(a(0));
            case "tolower": return Character.toLowerCase(a(0));
            default: throw new EmuException("Unimplemented API: " + n);
        }
    }
}
