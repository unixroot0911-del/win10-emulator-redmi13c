package com.unixroot.win10.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Loads 32-bit PE executables (and DLLs found next to them) and runs them on {@link Cpu}.
 * Windows system DLLs are replaced by a high-level emulation of the Win32 / C runtime API.
 */
public final class Emulator implements Cpu.Hook {

    public interface Console {
        void write(String s);
        /** Blocking read of one byte from stdin, -1 on EOF. */
        int read();
        void messageBox(String title, String text);
    }

    static final int STACK_TOP = 0x7E100000, TEB = 0x7FFDE000, PEB = 0x7FFDF000;
    static final int HEAP_BASE = 0x50000000, VIRT_BASE = 0x60000000, IMG_ALLOC = 0x20000000;

    public final Memory mem = new Memory();
    public final Cpu cpu = new Cpu(mem);
    final Console con;
    final File root;
    public int exitCode;
    String cwd = "C:\\";
    private File exeDir;
    private String exePath = "C:\\program.exe";

    int heapPtr = HEAP_BASE, virtPtr = VIRT_BASE, lastError, cmdLinePtr;
    private int nextImage = IMG_ALLOC, mainBase, retAddr;
    private boolean handledReturn;
    private final Map<String, Integer> stubByName = new HashMap<>();
    private final ArrayList<String> stubNames = new ArrayList<>();
    private final Map<Integer, RandomAccessFile> files = new HashMap<>();
    private final Map<Integer, CFile> cfiles = new HashMap<>();
    private final Map<Integer, ArrayDeque<Integer>> freeLists = new HashMap<>();
    private final Map<Integer, Integer> tls = new HashMap<>();
    private int tlsNext = 1;
    private final Map<Integer, java.util.Iterator<File>> finds = new HashMap<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final ArrayList<Integer> atexitFns = new ArrayList<>();
    private int nextHandle = 0x100;
    private final Random rng = new Random(1);
    private final long startNanos = System.nanoTime();
    // C runtime data cells
    private int iobBase, argcCell, argvCell, environCell, fmodeCell, commodeCell, errnoCell, pgmptrCell, mbCurMax, tlsArr;
    private int argvPtr;

    private final class Module {
        String name;
        int base, entry;
        boolean dll;
        final Map<String, Integer> exp = new HashMap<>();
        final Map<Integer, Integer> expOrd = new HashMap<>();
    }

    private final Map<String, Module> mods = new LinkedHashMap<>();
    private final ArrayList<Module> initOrder = new ArrayList<>();
    private final Map<Integer, Module> modByBase = new HashMap<>();

    private static final class CFile {
        RandomAccessFile raf;
        boolean eof;
        int unget = -1;
    }

    Gui gui;

    public Emulator(Console con, File root) {
        this.con = con;
        this.root = root;
        cpu.hook = this;
        stubNames.add("<exit>");
        stubNames.add("<return>");
        retAddr = cpu.stubLo + 16;
        env.put("PATH", "C:\\Windows\\System32");
        env.put("SystemRoot", "C:\\Windows");
        env.put("COMSPEC", "C:\\Windows\\System32\\cmd.exe");
        env.put("USERNAME", "user");
        env.put("TEMP", "C:\\Users\\Public\\Temp");
    }

    public void setGui(Gui g) { gui = g; }
    public void setCwd(String winPath) { cwd = winPath; }
    public void setExeDir(File dir) { exeDir = dir; }
    public void stop() { cpu.halted = true; }

    // ---------------- PE loading ----------------
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int u32(byte[] b, int o) { return u16(b, o) | (u16(b, o + 2) << 16); }

    private byte[] findDll(String name) {
        String n = name.toLowerCase();
        String base = n.contains("\\") ? n.substring(n.lastIndexOf('\\') + 1) : n;
        File[] dirs = {exeDir, WinPath.resolve(root, cwd, "C:\\Windows\\System32"), WinPath.resolve(root, cwd, cwd)};
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            String[] kids = d.list();
            if (kids == null) continue;
            for (String k : kids) {
                if (k.equalsIgnoreCase(base) || k.equalsIgnoreCase(base + ".dll")) {
                    File f = new File(d, k);
                    if (f.isFile()) try { return java.nio.file.Files.readAllBytes(f.toPath()); } catch (IOException ignored) { }
                }
            }
        }
        return null;
    }

    private Module loadModule(byte[] b, String name, boolean isExe) {
        if (b.length < 0x40 || b[0] != 'M' || b[1] != 'Z') throw new EmuException("Not a valid Win32 application (no MZ header): " + name);
        int e = u32(b, 0x3C);
        if (e <= 0 || e + 24 > b.length || u32(b, e) != 0x4550) throw new EmuException("Not a valid PE file: " + name);
        int machine = u16(b, e + 4);
        if (machine == 0x8664) throw new EmuException(name + " is a 64-bit (x64) program; only 32-bit programs are supported");
        if (machine != 0x14C) throw new EmuException("Unsupported machine type " + Integer.toHexString(machine) + " in " + name);
        int nsec = u16(b, e + 4 + 2), chars = u16(b, e + 22), optSize = u16(b, e + 20), opt = e + 24;
        if (u16(b, opt) != 0x10B) throw new EmuException("Not a PE32 image: " + name);
        Module m = new Module();
        m.name = name;
        m.dll = (chars & 0x2000) != 0;
        m.entry = u32(b, opt + 16);
        int pref = u32(b, opt + 28), sizeOfImage = u32(b, opt + 56), hdrSize = u32(b, opt + 60);
        int importRva = u32(b, opt + 104), exportRva = u32(b, opt + 96), exportSize = u32(b, opt + 100);
        int relocRva = u32(b, opt + 136), relocSize = u32(b, opt + 140);
        int tlsRva = u32(b, opt + 168);
        int base = pref;
        boolean clash = pref <= 0 || pref >= HEAP_BASE || (pref + sizeOfImage) < 0;
        for (Module o : mods.values()) if (pref < o.base + 0x1000000 && pref + sizeOfImage > o.base && o.base != 0) clash = true;
        if (clash) {
            if (relocRva == 0) throw new EmuException(name + " cannot be relocated (no relocation table) and its base address is taken");
            base = nextImage;
            nextImage += (sizeOfImage + 0xFFFF) & ~0xFFFF;
        } else if (pref + sizeOfImage > nextImage && pref < IMG_ALLOC + 0x20000000) {
            nextImage = Math.max(nextImage, (pref + sizeOfImage + 0xFFFF) & ~0xFFFF);
        }
        m.base = base;
        mem.write(base, b, 0, Math.min(hdrSize, b.length));
        int sec = opt + optSize;
        for (int i = 0; i < nsec; i++, sec += 40) {
            int rva = u32(b, sec + 12), raw = u32(b, sec + 16), ptr = u32(b, sec + 20);
            if (ptr < 0 || ptr > b.length) continue;
            mem.write(base + rva, b, ptr, Math.min(raw, b.length - ptr));
        }
        if (base != pref && relocRva != 0) {
            int delta = base - pref, p = base + relocRva, end = p + relocSize;
            while (p < end) {
                int page = mem.r32(p), blk = mem.r32(p + 4);
                if (blk < 8) break;
                for (int q = p + 8; q < p + blk; q += 2) {
                    int ent = mem.r16(q), t = ent >> 12, off = ent & 0xFFF;
                    if (t == 3) { int a = base + page + off; mem.w32(a, mem.r32(a) + delta); }
                }
                p += blk;
            }
        }
        mods.put(name.toLowerCase(), m);
        modByBase.put(base, m);
        if (exportRva != 0) {
            int d = base + exportRva;
            int nNames = mem.r32(d + 24), funcs = base + mem.r32(d + 28), names = base + mem.r32(d + 32), ords = base + mem.r32(d + 36), ordBase = mem.r32(d + 16);
            int nFuncs = mem.r32(d + 20);
            for (int i = 0; i < nFuncs; i++) {
                int rva = mem.r32(funcs + 4 * i);
                if (rva == 0) continue;
                int addr = base + rva;
                if (rva >= exportRva && rva < exportRva + exportSize) addr = stubFor(mem.cstr(addr).substring(mem.cstr(addr).lastIndexOf('.') + 1));
                m.expOrd.put(ordBase + i, addr);
            }
            for (int i = 0; i < nNames; i++) {
                String fn = mem.cstr(base + mem.r32(names + 4 * i));
                int idx = mem.r16(ords + 2 * i);
                Integer a = m.expOrd.get(ordBase + idx);
                if (a != null) m.exp.put(fn, a);
            }
        }
        if (tlsRva != 0) {
            int t = base + tlsRva;
            int start = mem.r32(t), end = mem.r32(t + 4), idxAddr = mem.r32(t + 8), zero = mem.r32(t + 16);
            int size = Math.max(0, end - start) + zero;
            int blk = allocZero(Math.max(size, 16));
            for (int i = 0; i < end - start; i++) mem.w8(blk + i, mem.r8(start + i));
            if (tlsArr != 0) {
                int slot = tlsNext++;
                mem.w32(idxAddr, slot);
                mem.w32(tlsArr + 4 * slot, blk);
            }
        }
        if (importRva != 0) {
            for (int d = base + importRva; mem.r32(d + 12) != 0; d += 20) {
                int ilt = mem.r32(d) != 0 ? mem.r32(d) : mem.r32(d + 16), iat = mem.r32(d + 16);
                String dll = mem.cstr(base + mem.r32(d + 12)).toLowerCase();
                Module prov = mods.get(dll);
                if (prov == null) {
                    byte[] db = findDll(dll);
                    if (db != null) prov = loadModule(db, dll.endsWith(".dll") ? dll : dll + ".dll", false);
                }
                for (int i = 0; ; i++) {
                    int v = mem.r32(base + ilt + 4 * i);
                    if (v == 0) break;
                    int target;
                    if (v < 0) {
                        int ord = v & 0xFFFF;
                        Integer a = prov == null ? null : prov.expOrd.get(ord);
                        target = a != null ? a : stubFor(dll + "#" + ord);
                    } else {
                        String fn = mem.cstr(base + v + 2);
                        Integer a = prov == null ? null : prov.exp.get(fn);
                        int data = dataAddr(fn);
                        target = a != null ? a : data != 0 ? data : stubFor(fn);
                    }
                    mem.w32(base + iat + 4 * i, target);
                }
            }
        }
        if (m.dll && m.entry != 0) initOrder.add(m);
        return m;
    }

    private int dataAddr(String n) {
        switch (n) {
            case "_iob": return iobBase;
            case "__argc": return argcCell;
            case "__argv": return argvCell;
            case "_environ": return environCell;
            case "_fmode": return fmodeCell;
            case "_commode": return commodeCell;
            case "_pgmptr": return pgmptrCell;
            case "__mb_cur_max": return mbCurMax;
            default: return 0;
        }
    }

    public void load(byte[] b, String winPath, String args) {
        exePath = winPath;
        // stack, TEB, PEB
        cpu.r[Cpu.ESP] = STACK_TOP - 16;
        mem.w32(TEB, -1); mem.w32(TEB + 4, STACK_TOP); mem.w32(TEB + 8, STACK_TOP - 0x100000);
        mem.w32(TEB + 0x18, TEB); mem.w32(TEB + 0x30, PEB);
        tlsArr = allocZero(4 * 64);
        mem.w32(TEB + 0x2C, tlsArr);
        cpu.fsBase = TEB;
        // C runtime data
        iobBase = allocZero(96);
        argcCell = allocZero(4); argvCell = allocZero(4); environCell = allocZero(4); fmodeCell = allocZero(4);
        commodeCell = allocZero(4); errnoCell = allocZero(4); pgmptrCell = allocZero(4); mbCurMax = allocZero(4);
        mem.w32(mbCurMax, 1);
        int envBlock = allocZero(8);
        mem.w32(environCell, envBlock);
        String cmdline = "\"" + winPath + "\"" + (args.isEmpty() ? "" : " " + args);
        cmdLinePtr = allocZero(cmdline.length() + 4);
        mem.putCstr(cmdLinePtr, cmdline);
        ArrayList<String> argv = new ArrayList<>();
        argv.add(winPath);
        StringBuilder cur = new StringBuilder();
        boolean q = false, any = false;
        for (char ch : args.toCharArray()) {
            if (ch == '"') { q = !q; any = true; }
            else if (ch == ' ' && !q) { if (any || cur.length() > 0) argv.add(cur.toString()); cur.setLength(0); any = false; }
            else cur.append(ch);
        }
        if (any || cur.length() > 0) argv.add(cur.toString());
        argvPtr = allocZero(4 * (argv.size() + 1));
        for (int i = 0; i < argv.size(); i++) {
            int sp = allocZero(argv.get(i).length() + 2);
            mem.putCstr(sp, argv.get(i));
            mem.w32(argvPtr + 4 * i, sp);
        }
        mem.w32(argcCell, argv.size());
        mem.w32(argvCell, argvPtr);
        mem.w32(pgmptrCell, mem.r32(argvPtr));

        Module main = loadModule(b, winPath.substring(winPath.lastIndexOf('\\') + 1), true);
        mainBase = main.base;
        mem.w32(PEB + 8, main.base);
        cpu.push(cpu.stubLo); // return address of the entry point -> exit stub
        cpu.eip = main.base + main.entry;
    }

    public int run() {
        try {
            ArrayList<Module> inits = new ArrayList<>(initOrder);
            for (Module m : inits) callGuest(m.base + m.entry, m.base, 1, 0);
            while (!cpu.halted) cpu.step();
        } catch (EmuException ex) {
            con.write("\r\n[emulator] " + ex.getMessage() + "\r\n");
            exitCode = -1;
        } catch (RuntimeException ex) {
            StackTraceElement[] st = ex.getStackTrace();
            con.write("\r\n[emulator] internal error: " + ex + (st.length > 0 ? " at " + st[0] : "") + " (eip=" + Integer.toHexString(cpu.eip) + ")\r\n");
            exitCode = -1;
        }
        for (RandomAccessFile f : files.values()) try { f.close(); } catch (IOException ignored) { }
        for (CFile f : cfiles.values()) try { if (f.raf != null) f.raf.close(); } catch (IOException ignored) { }
        if (gui != null) gui.onExit();
        return exitCode;
    }

    /** Calls guest code and returns EAX (used for callbacks from the emulated API layer). */
    public int callGuest(int fn, int... args) {
        int[] saved = cpu.r.clone();
        int savedEip = cpu.eip;
        for (int i = args.length - 1; i >= 0; i--) cpu.push(args[i]);
        cpu.push(retAddr);
        cpu.eip = fn;
        while (cpu.eip != retAddr && !cpu.halted) cpu.step();
        int ret = cpu.r[Cpu.EAX];
        if (!cpu.halted) {
            System.arraycopy(saved, 0, cpu.r, 0, 8);
            cpu.eip = savedEip;
        }
        cpu.r[Cpu.EAX] = ret;
        return ret;
    }

    // ---------------- stubs ----------------
    int stubFor(String name) {
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
        if (idx == 0) { doExitHandlers(); exitCode = c.r[Cpu.EAX]; c.halted = true; return; }
        String name = stubNames.get(idx);
        Integer nargs = Win32Table.nargs(name);
        if (nargs == null && gui != null) nargs = gui.nargs(name);
        if (nargs == null) throw new EmuException("Unimplemented API: " + name);
        boolean std = Win32Table.nargs(name) != null ? Win32Table.isStdcall(name) : gui.isStdcall(name);
        int ret = api(name);
        if (c.halted) return;
        if (handledReturn) { handledReturn = false; return; }
        c.r[Cpu.EAX] = ret;
        c.eip = c.pop();
        if (std) c.r[Cpu.ESP] += nargs * 4;
    }

    private void doExitHandlers() {
        while (!atexitFns.isEmpty() && !cpu.halted) {
            int fn = atexitFns.remove(atexitFns.size() - 1);
            callGuest(fn);
        }
    }

    // ---------------- memory helpers ----------------
    int alloc(int size) {
        int rs = Math.max(16, (size + 15) & ~15);
        ArrayDeque<Integer> q = freeLists.get(rs);
        if (q != null && !q.isEmpty()) { int p = q.pop(); mem.w32(p - 12, size); return p; }
        int p = (heapPtr + 15) & ~15;
        if (p + 16 + rs < 0 || p + 16 + rs > VIRT_BASE) return 0;
        mem.w32(p, rs);
        mem.w32(p + 4, size);
        heapPtr = p + 16 + rs;
        return p + 16;
    }

    int allocZero(int size) {
        int p = alloc(size);
        for (int i = 0; i < size; i++) mem.w8(p + i, 0);
        return p;
    }

    private void free(int p) {
        if (p == 0) return;
        int rs = mem.r32(p - 16);
        if (rs < 16 || rs > 0x10000000) return;
        freeLists.computeIfAbsent(rs, k -> new ArrayDeque<>()).push(p);
    }

    private int realloc(int old, int nsz) {
        if (old == 0) return alloc(nsz);
        int osz = mem.r32(old - 12);
        int p = alloc(nsz);
        if (p != 0) { int n = Math.min(osz, nsz); mem.write(p, mem.read(old, n), 0, n); free(old); }
        return p;
    }

    int a(int i) { return cpu.arg(i); }
    private File file(String p) { return WinPath.resolve(root, cwd, p); }
    String wstr(int p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65536; i++) { int ch = mem.r16(p + 2 * i); if (ch == 0) break; sb.append((char) ch); }
        return sb.toString();
    }
    private double dbl(int i) { return Double.longBitsToDouble((a(i) & 0xFFFFFFFFL) | ((long) a(i + 1) << 32)); }
    private long i64(int i) { return (a(i) & 0xFFFFFFFFL) | ((long) a(i + 1) << 32); }
    void out(String s) { con.write(s); }
    private static String latin(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length);
        for (byte x : data) sb.append((char) (x & 0xFF));
        return sb.toString();
    }

    // ---------------- file access ----------------
    private int writeHandle(int h, byte[] data, int pWritten) {
        if (h == 1 || h == 2 || h == 3) out(latin(data));
        else {
            RandomAccessFile f = files.get(h);
            if (f == null) { lastError = 6; return 0; }
            try { f.write(data); } catch (IOException ex) { lastError = 29; return 0; }
        }
        if (pWritten != 0) mem.w32(pWritten, data.length);
        return 1;
    }

    private int readHandle(int h, int buf, int n, int pRead) {
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

    private RandomAccessFile openFile(File f, String mode, boolean truncate) throws IOException {
        if (f.getParentFile() != null && !mode.equals("r")) f.getParentFile().mkdirs();
        RandomAccessFile r = new RandomAccessFile(f, mode);
        if (truncate) r.setLength(0);
        return r;
    }

    // C stdio
    private int stdinF() { return iobBase; }
    private int stdoutF() { return iobBase + 32; }
    private int stderrF() { return iobBase + 64; }

    private int fgetcOf(int fp) {
        if (fp == stdinF()) return con.read();
        CFile cf = cfiles.get(fp);
        if (cf == null) return -1;
        if (cf.unget >= 0) { int u = cf.unget; cf.unget = -1; return u; }
        try { int b = cf.raf.read(); if (b < 0) cf.eof = true; return b; } catch (IOException e) { return -1; }
    }

    private int fwriteTo(int fp, byte[] data) {
        if (fp == stdoutF() || fp == stderrF()) { out(latin(data)); return data.length; }
        CFile cf = cfiles.get(fp);
        if (cf == null) return 0;
        try { cf.raf.write(data); return data.length; } catch (IOException e) { return 0; }
    }

    private int fopen(String path, String mode) {
        File f = file(path);
        boolean plus = mode.contains("+");
        char m = mode.isEmpty() ? 'r' : mode.charAt(0);
        try {
            RandomAccessFile r;
            if (m == 'r') { if (!f.isFile()) { mem.w32(errnoCell, 2); return 0; } r = openFile(f, plus ? "rw" : "r", false); }
            else if (m == 'w') r = openFile(f, "rw", true);
            else { r = openFile(f, "rw", false); r.seek(r.length()); }
            int fp = allocZero(32);
            CFile cf = new CFile();
            cf.raf = r;
            cfiles.put(fp, cf);
            return fp;
        } catch (IOException e) { mem.w32(errnoCell, 13); return 0; }
    }

    // ---------------- printf ----------------
    interface Args { int next(); }

    private Args stackArgs(final int start) {
        return new Args() { int i = start; public int next() { return a(i++); } };
    }

    private Args vaList(final int ptr) {
        return new Args() { int p = ptr; public int next() { int v = mem.r32(p); p += 4; return v; } };
    }

    private static String fmtG(double v, int prec, boolean upper, boolean alt) {
        if (prec < 0) prec = 6; else if (prec == 0) prec = 1;
        if (v == 0) return alt ? String.format("%." + (prec - 1) + "f", 0.0) : "0";
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v < 0 ? "-inf" : "inf";
        int x = (int) Math.floor(Math.log10(Math.abs(v)));
        String s = String.format("%." + (prec - 1) + "e", v);
        x = Integer.parseInt(s.substring(s.indexOf('e') + 1));
        if (x < -4 || x >= prec) {
            String mant = s.substring(0, s.indexOf('e')), ex = s.substring(s.indexOf('e'));
            if (!alt && mant.contains(".")) mant = mant.replaceAll("0+$", "").replaceAll("\\.$", "");
            s = mant + ex;
        } else {
            s = String.format("%." + Math.max(0, prec - 1 - x) + "f", v);
            if (!alt && s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return upper ? s.toUpperCase() : s;
    }

    String format(String f, Args args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length(); i++) {
            char ch = f.charAt(i);
            if (ch != '%') { sb.append(ch); continue; }
            i++;
            if (i >= f.length()) break;
            boolean left = false, zero = false, plus = false, space = false, alt = false;
            for (; i < f.length(); i++) {
                char fl = f.charAt(i);
                if (fl == '-') left = true; else if (fl == '0') zero = true; else if (fl == '+') plus = true; else if (fl == ' ') space = true; else if (fl == '#') alt = true; else break;
            }
            int width = 0, prec = -1;
            if (i < f.length() && f.charAt(i) == '*') { width = args.next(); if (width < 0) { left = true; width = -width; } i++; }
            else while (i < f.length() && Character.isDigit(f.charAt(i))) width = width * 10 + (f.charAt(i++) - '0');
            if (i < f.length() && f.charAt(i) == '.') {
                i++; prec = 0;
                if (i < f.length() && f.charAt(i) == '*') { prec = args.next(); i++; }
                else while (i < f.length() && Character.isDigit(f.charAt(i))) prec = prec * 10 + (f.charAt(i++) - '0');
            }
            boolean ll = false, wide = false;
            while (i < f.length()) {
                char m = f.charAt(i);
                if (m == 'l') { if (i + 1 < f.length() && f.charAt(i + 1) == 'l') { ll = true; i++; } else wide = true; i++; }
                else if (m == 'I' && f.startsWith("I64", i)) { ll = true; i += 3; }
                else if (m == 'I' && f.startsWith("I32", i)) { i += 3; }
                else if (m == 'h' || m == 'z' || m == 'j' || m == 't' || m == 'I' || m == 'L') { if (m == 'j') ll = true; i++; }
                else break;
            }
            if (i >= f.length()) break;
            char cv = f.charAt(i);
            String s;
            switch (cv) {
                case 'd': case 'i': {
                    long v = ll ? (args.next() & 0xFFFFFFFFL) | ((long) args.next() << 32) : args.next();
                    s = Long.toString(v);
                    if (prec >= 0) { boolean neg = v < 0; String dg = neg ? s.substring(1) : s; while (dg.length() < prec) dg = "0" + dg; s = (neg ? "-" : "") + dg; zero = false; }
                    if (v >= 0) s = (plus ? "+" : space ? " " : "") + s;
                    break; }
                case 'u': { long v = ll ? (args.next() & 0xFFFFFFFFL) | ((long) args.next() << 32) : args.next() & 0xFFFFFFFFL; s = ll ? Long.toUnsignedString(v) : Long.toString(v); break; }
                case 'x': case 'X': case 'o': {
                    long v = ll ? (args.next() & 0xFFFFFFFFL) | ((long) args.next() << 32) : args.next() & 0xFFFFFFFFL;
                    s = cv == 'o' ? Long.toOctalString(v) : Long.toHexString(v);
                    if (prec >= 0) while (s.length() < prec) s = "0" + s;
                    if (alt && v != 0) s = (cv == 'o' ? "0" : cv == 'x' ? "0x" : "0X") + s;
                    if (cv == 'X') s = s.toUpperCase();
                    break; }
                case 'p': s = String.format("%08X", args.next()); break;
                case 'c': s = wide ? String.valueOf((char) (args.next() & 0xFFFF)) : String.valueOf((char) (args.next() & 0xFF)); break;
                case 's': case 'S': { int p = args.next(); s = p == 0 ? "(null)" : (wide || cv == 'S') ? wstr(p) : mem.cstr(p); if (prec >= 0 && prec < s.length()) s = s.substring(0, prec); break; }
                case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': {
                    long bits = (args.next() & 0xFFFFFFFFL) | ((long) args.next() << 32);
                    double v = Double.longBitsToDouble(bits);
                    if (Double.isNaN(v)) s = "nan"; else if (Double.isInfinite(v)) s = v < 0 ? "-inf" : "inf";
                    else if (cv == 'f' || cv == 'F') s = String.format("%." + (prec < 0 ? 6 : prec) + "f", v);
                    else if (cv == 'e' || cv == 'E') s = String.format("%." + (prec < 0 ? 6 : prec) + "e", v);
                    else s = fmtG(v, prec, cv == 'G', alt);
                    if (cv == 'E') s = s.toUpperCase();
                    if (v >= 0 && !Double.isNaN(v)) s = (plus ? "+" : space ? " " : "") + s;
                    break; }
                case '%': s = "%"; break;
                case 'n': args.next(); s = ""; break;
                default: s = "%" + cv;
            }
            while (s.length() < width) {
                if (left) s = s + " ";
                else if (zero && cv != 's' && cv != 'c' && cv != 'S') {
                    int sign = (s.startsWith("-") || s.startsWith("+")) ? 1 : 0;
                    s = s.substring(0, sign) + "0" + s.substring(sign);
                } else s = " " + s;
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static long parseInt(String s, int base, boolean[] ok, int[] consumed) {
        int i = 0, n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        boolean neg = false;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) { neg = s.charAt(i) == '-'; i++; }
        if ((base == 0 || base == 16) && i + 1 < n && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) { i += 2; base = 16; }
        else if (base == 0 && i < n && s.charAt(i) == '0') base = 8;
        else if (base == 0) base = 10;
        long v = 0;
        int start = i;
        while (i < n) {
            int d = Character.digit(s.charAt(i), base);
            if (d < 0) break;
            v = v * base + d;
            i++;
        }
        ok[0] = i > start;
        consumed[0] = ok[0] ? i : 0;
        return neg ? -v : v;
    }

    // ---------------- API implementations ----------------
    private int api(String n) {
        switch (n) {
            // ===== process / console =====
            case "ExitProcess": exitCode = a(0); doExitHandlers(); cpu.halted = true; return 0;
            case "TerminateProcess": exitCode = a(1); cpu.halted = true; return 0;
            case "exit": exitCode = a(0); doExitHandlers(); cpu.halted = true; return 0;
            case "_exit": exitCode = a(0); cpu.halted = true; return 0;
            case "abort": out("\r\nabort() has been called\r\n"); exitCode = 3; cpu.halted = true; return 0;
            case "_cexit": doExitHandlers(); return 0;
            case "atexit": case "_crt_atexit": atexitFns.add(a(0)); return 0;
            case "_onexit": atexitFns.add(a(0)); return a(0);
            case "ExitThread": exitCode = a(0); cpu.halted = true; return 0;
            case "GetStdHandle": { int k = a(0); return k == -10 ? 1 : k == -11 ? 2 : 3; }
            case "SetStdHandle": return 1;
            case "WriteFile": return writeHandle(a(0), mem.read(a(1), a(2)), a(3));
            case "WriteConsoleA": return writeHandle(a(0), mem.read(a(1), a(2)), a(3));
            case "WriteConsoleW": {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a(2); i++) sb.append((char) mem.r16(a(1) + 2 * i));
                out(sb.toString());
                if (a(3) != 0) mem.w32(a(3), a(2));
                return 1; }
            case "ReadFile": case "ReadConsoleA": return readHandle(a(0), a(1), a(2), a(3));
            case "GetFileType": return a(0) >= 1 && a(0) <= 3 ? 2 : 1;
            case "GetConsoleMode": if (a(0) >= 1 && a(0) <= 3) { mem.w32(a(1), 7); return 1; } return 0;
            case "SetConsoleMode": case "SetConsoleTitleA": case "SetConsoleTextAttribute": case "SetConsoleCtrlHandler": case "SetConsoleOutputCP": return 1;
            case "GetConsoleOutputCP": case "GetConsoleCP": return 65001;
            case "GetConsoleScreenBufferInfo": {
                int p = a(1);
                for (int i = 0; i < 22; i += 2) mem.w16(p + i, 0);
                mem.w16(p, 80); mem.w16(p + 2, 25); mem.w16(p + 8, 7); mem.w16(p + 12, 79); mem.w16(p + 14, 24); mem.w16(p + 16, 80); mem.w16(p + 18, 25);
                return 1; }
            case "GetCommandLineA": return cmdLinePtr;
            case "GetCommandLineW": {
                String s = mem.cstr(cmdLinePtr); int p = allocZero(2 * s.length() + 2);
                for (int i = 0; i < s.length(); i++) mem.w16(p + 2 * i, s.charAt(i));
                return p; }
            case "GetStartupInfoA": case "GetStartupInfoW": { for (int i = 0; i < 68; i += 4) mem.w32(a(0) + i, 0); mem.w32(a(0), 68); return 0; }
            case "GetVersion": return 0x0000000A | (19045 << 16);
            case "GetVersionExA": { int p = a(0); mem.w32(p + 4, 10); mem.w32(p + 8, 0); mem.w32(p + 12, 19045); mem.w32(p + 16, 2); return 1; }
            case "IsDebuggerPresent": return 0;
            case "GetCurrentProcessId": return 1234;
            case "GetCurrentThreadId": return 5678;
            case "GetCurrentProcess": return -1;
            case "GetCurrentThread": return -2;
            case "GetSystemInfo": {
                int p = a(0);
                for (int i = 0; i < 36; i += 4) mem.w32(p + i, 0);
                mem.w32(p + 4, 4096); mem.w32(p + 8, 0x10000); mem.w32(p + 12, 0x7FFEFFFF); mem.w32(p + 20, 4); mem.w32(p + 24, 586); mem.w32(p + 28, 0x10000);
                return 0; }
            case "GlobalMemoryStatusEx": { int p = a(0); mem.w32(p, 64); mem.w32(p + 4, 50); mem.w64(p + 8, 2L << 30); mem.w64(p + 16, 1L << 30); mem.w64(p + 24, 4L << 30); mem.w64(p + 32, 3L << 30); mem.w64(p + 40, 2L << 30); mem.w64(p + 48, 1L << 30); return 1; }
            case "GetComputerNameA": mem.putCstr(a(0), "WIN10-EMU"); mem.w32(a(1), 9); return 1;
            case "GetUserNameA": mem.putCstr(a(0), "user"); mem.w32(a(1), 5); return 1;
            case "IsProcessorFeaturePresent": return a(0) == 10 ? 1 : 0;
            case "DisableThreadLibraryCalls": case "SetErrorMode": case "SetThreadPriority": case "SwitchToThread": return 1;
            case "OutputDebugStringA": return 0;
            case "GetLogicalDrives": return 4;
            case "GetDriveTypeA": return 3;
            case "GetProcessAffinityMask": return 0;
            case "InitializeSListHead": return 0;
            case "RtlUnwind": return 0;
            case "SetUnhandledExceptionFilter": case "UnhandledExceptionFilter": return 0;
            case "RaiseException": throw new EmuException(String.format("Unhandled exception 0x%08X raised by the program", a(0)));
            case "_XcptFilter": case "_seh_filter_exe": return 0;
            case "__C_specific_handler": return 1;
            case "EncodePointer": case "DecodePointer": return a(0);
            // ===== modules =====
            case "GetModuleHandleA": case "GetModuleHandleW": {
                if (a(0) == 0) return mainBase;
                String nm = (n.endsWith("W") ? wstr(a(0)) : mem.cstr(a(0))).toLowerCase();
                Module m = mods.get(nm.endsWith(".dll") || nm.endsWith(".exe") ? nm : nm + ".dll");
                return m != null ? m.base : 0x70000000 + (nm.hashCode() & 0xFFF) * 0x10000; }
            case "GetModuleHandleExA": mem.w32(a(2), mainBase); return 1;
            case "GetModuleFileNameA": {
                String p = exePath; int cap = a(2);
                if (p.length() >= cap) p = p.substring(0, Math.max(0, cap - 1));
                mem.putCstr(a(1), p); return p.length(); }
            case "LoadLibraryA": case "LoadLibraryW": case "LoadLibraryExA": {
                String nm = n.equals("LoadLibraryW") ? wstr(a(0)) : mem.cstr(a(0));
                String key = nm.toLowerCase();
                key = key.substring(key.lastIndexOf('\\') + 1);
                Module m = mods.get(key.endsWith(".dll") ? key : key + ".dll");
                if (m == null) {
                    byte[] db = findDll(nm);
                    if (db != null) {
                        int before = initOrder.size();
                        m = loadModule(db, key.endsWith(".dll") ? key : key + ".dll", false);
                        for (int i = before; i < initOrder.size(); i++) { Module im = initOrder.get(i); callGuest(im.base + im.entry, im.base, 1, 0); }
                    }
                }
                if (m != null) return m.base;
                lastError = 0;
                return 0x70000000 + (key.hashCode() & 0xFFF) * 0x10000; }
            case "FreeLibrary": return 1;
            case "GetProcAddress": {
                Module m = modByBase.get(a(0));
                int p = a(1);
                if (m != null) {
                    Integer r = (p >>> 16) == 0 ? m.expOrd.get(p) : m.exp.get(mem.cstr(p));
                    if (r == null) { lastError = 127; return 0; }
                    return r;
                }
                String fn = (p >>> 16) == 0 ? "#" + p : mem.cstr(p);
                return Win32Table.nargs(fn) != null || (gui != null && gui.nargs(fn) != null) ? stubFor(fn) : stubFor(fn); }
            // ===== memory =====
            case "GetProcessHeap": return 0x00500000;
            case "HeapCreate": return 0x00500000 + 0x1000;
            case "HeapDestroy": case "HeapValidate": return 1;
            case "HeapAlloc": { int p = (a(1) & 8) != 0 ? allocZero(a(2)) : alloc(a(2)); return p; }
            case "HeapFree": free(a(2)); return 1;
            case "HeapReAlloc": return realloc(a(2), a(3));
            case "HeapSize": return mem.r32(a(2) - 12);
            case "GlobalAlloc": case "LocalAlloc": return (a(0) & 0x40) != 0 ? allocZero(a(1)) : alloc(a(1));
            case "GlobalFree": case "LocalFree": free(a(0)); return 0;
            case "GlobalLock": return a(0);
            case "GlobalUnlock": return 1;
            case "VirtualAlloc": {
                int size = (a(1) + 0xFFF) & ~0xFFF;
                if (a(0) != 0) return a(0);
                int p = virtPtr; virtPtr += size + 0x1000; return p; }
            case "VirtualFree": return 1;
            case "VirtualProtect": if (a(3) != 0) mem.w32(a(3), 0x40); return 1;
            case "VirtualQuery": {
                int p = a(1), addr = a(0) & ~0xFFF;
                mem.w32(p, addr); mem.w32(p + 4, addr); mem.w32(p + 8, 0x40); mem.w32(p + 12, 0x1000); mem.w32(p + 16, 0x1000); mem.w32(p + 20, 0x40); mem.w32(p + 24, 0x20000);
                return 28; }
            case "malloc": return alloc(a(0));
            case "calloc": return allocZero(a(0) * a(1));
            case "realloc": return realloc(a(0), a(1));
            case "free": free(a(0)); return 0;
            case "_aligned_malloc": return alloc(a(0) + a(1) + 16) + 0; // heap blocks are 16-byte aligned
            case "_aligned_free": return 0;
            case "memcpy": case "memmove": {
                int len = a(2), dst = a(0), src = a(1);
                if (len > 0) { byte[] tmp = mem.read(src, len); mem.write(dst, tmp, 0, len); }
                return dst; }
            case "memset": { int p = a(0), v = a(1), len = a(2); for (int i = 0; i < len; i++) mem.w8(p + i, v); return p; }
            case "memcmp": { int x = a(0), y = a(1), len = a(2); for (int i = 0; i < len; i++) { int d = mem.r8(x + i) - mem.r8(y + i); if (d != 0) return d; } return 0; }
            case "memchr": { for (int i = 0; i < a(2); i++) if (mem.r8(a(0) + i) == (a(1) & 0xFF)) return a(0) + i; return 0; }
            case "_chkstk": case "__chkstk": case "_alloca": case "__alloca": {
                int ret = cpu.pop(); cpu.r[Cpu.ESP] -= cpu.r[Cpu.EAX]; cpu.eip = ret; handledReturn = true; return 0; }
            case "___chkstk_ms": return cpu.r[Cpu.EAX];
            // ===== threads / sync (single-threaded model) =====
            case "InitializeCriticalSection": case "InitializeCriticalSectionAndSpinCount": case "InitializeCriticalSectionEx":
            case "EnterCriticalSection": case "LeaveCriticalSection": case "DeleteCriticalSection": return 1;
            case "TryEnterCriticalSection": return 1;
            case "_lock": case "_unlock": case "__lock": case "__unlock": return 0;
            case "TlsAlloc": case "FlsAlloc": return tlsNext++ + 100;
            case "TlsFree": case "FlsFree": return 1;
            case "TlsGetValue": case "FlsGetValue": { Integer v = tls.get(a(0)); return v == null ? 0 : v; }
            case "TlsSetValue": case "FlsSetValue": tls.put(a(0), a(1)); return 1;
            case "InterlockedIncrement": { int v = mem.r32(a(0)) + 1; mem.w32(a(0), v); return v; }
            case "InterlockedDecrement": { int v = mem.r32(a(0)) - 1; mem.w32(a(0), v); return v; }
            case "InterlockedExchange": { int old = mem.r32(a(0)); mem.w32(a(0), a(1)); return old; }
            case "InterlockedExchangeAdd": { int old = mem.r32(a(0)); mem.w32(a(0), old + a(1)); return old; }
            case "InterlockedCompareExchange": { int old = mem.r32(a(0)); if (old == a(2)) mem.w32(a(0), a(1)); return old; }
            case "CreateThread": {
                int fn = a(2), param = a(3), h = nextHandle++;
                if (a(5) != 0) mem.w32(a(5), 9000 + h);
                callGuest(fn, param); // run to completion immediately (no real concurrency)
                return h; }
            case "WaitForSingleObject": return 0;
            case "CreateEventA": case "CreateMutexA": return nextHandle++;
            case "SetEvent": case "ResetEvent": case "ReleaseMutex": return 1;
            case "Sleep": try { Thread.sleep(Math.min(a(0) & 0xFFFFFFFFL, 5000)); } catch (InterruptedException ignored) { } return 0;
            case "GetTickCount": case "GetTickCount64": return (int) ((System.nanoTime() - startNanos) / 1000000L) + 100000;
            case "GetLastError": return lastError;
            case "SetLastError": lastError = a(0); return 0;
            case "_errno": return errnoCell;
            case "GetSystemTimeAsFileTime": { long t = (System.currentTimeMillis() + 11644473600000L) * 10000L; mem.w32(a(0), (int) t); mem.w32(a(0) + 4, (int) (t >>> 32)); return 0; }
            case "QueryPerformanceCounter": mem.w64(a(0), System.nanoTime()); return 1;
            case "QueryPerformanceFrequency": mem.w64(a(0), 1000000000L); return 1;
            case "GetLocalTime": case "GetSystemTime": {
                java.util.Calendar c = java.util.Calendar.getInstance(); int p = a(0);
                mem.w16(p, c.get(1)); mem.w16(p + 2, c.get(2) + 1); mem.w16(p + 4, c.get(7) - 1); mem.w16(p + 6, c.get(5));
                mem.w16(p + 8, c.get(11)); mem.w16(p + 10, c.get(12)); mem.w16(p + 12, c.get(13)); mem.w16(p + 14, c.get(14)); return 0; }
            // ===== strings / locale =====
            case "lstrlenA": case "strlen": return mem.cstr(a(0)).length();
            case "lstrlenW": return wstr(a(0)).length();
            case "lstrcpyA": case "strcpy": mem.putCstr(a(0), mem.cstr(a(1))); return a(0);
            case "lstrcatA": case "strcat": { String s = mem.cstr(a(0)); mem.putCstr(a(0) + s.length(), mem.cstr(a(1))); return a(0); }
            case "strncpy": { String s = mem.cstr(a(1)); for (int i = 0; i < a(2); i++) mem.w8(a(0) + i, i < s.length() ? s.charAt(i) : 0); return a(0); }
            case "strncat": { String s = mem.cstr(a(0)), t = mem.cstr(a(1)); if (t.length() > a(2)) t = t.substring(0, a(2)); mem.putCstr(a(0) + s.length(), t); return a(0); }
            case "lstrcmpA": case "strcmp": return Integer.signum(mem.cstr(a(0)).compareTo(mem.cstr(a(1))));
            case "lstrcmpiA": case "_stricmp": case "stricmp": return Integer.signum(mem.cstr(a(0)).compareToIgnoreCase(mem.cstr(a(1))));
            case "strncmp": { String x = mem.cstr(a(0)), y = mem.cstr(a(1)); int k = a(2); return Integer.signum((x.length() > k ? x.substring(0, k) : x).compareTo(y.length() > k ? y.substring(0, k) : y)); }
            case "_strnicmp": { String x = mem.cstr(a(0)), y = mem.cstr(a(1)); int k = a(2); return Integer.signum((x.length() > k ? x.substring(0, k) : x).compareToIgnoreCase(y.length() > k ? y.substring(0, k) : y)); }
            case "strchr": { int p = a(0); for (int i = 0; ; i++) { int ch = mem.r8(p + i); if (ch == (a(1) & 0xFF)) return p + i; if (ch == 0) return 0; } }
            case "strrchr": { int p = a(0), last = 0; for (int i = 0; ; i++) { int ch = mem.r8(p + i); if (ch == (a(1) & 0xFF)) last = p + i; if (ch == 0) return last; } }
            case "strstr": { String h = mem.cstr(a(0)), nd = mem.cstr(a(1)); int i = h.indexOf(nd); return i < 0 ? 0 : a(0) + i; }
            case "strdup": case "_strdup": { String s = mem.cstr(a(0)); int p = alloc(s.length() + 1); mem.putCstr(p, s); return p; }
            case "toupper": return Character.toUpperCase(a(0));
            case "tolower": return Character.toLowerCase(a(0));
            case "isalpha": return Character.isLetter(a(0)) && a(0) < 128 ? 1 : 0;
            case "isdigit": return a(0) >= '0' && a(0) <= '9' ? 1 : 0;
            case "isspace": return a(0) == ' ' || (a(0) >= 9 && a(0) <= 13) ? 1 : 0;
            case "isalnum": return Character.isLetterOrDigit(a(0)) && a(0) < 128 ? 1 : 0;
            case "isupper": return a(0) >= 'A' && a(0) <= 'Z' ? 1 : 0;
            case "islower": return a(0) >= 'a' && a(0) <= 'z' ? 1 : 0;
            case "isxdigit": return Character.digit(a(0), 16) >= 0 && a(0) < 128 ? 1 : 0;
            case "ispunct": return a(0) > 32 && a(0) < 127 && !Character.isLetterOrDigit(a(0)) ? 1 : 0;
            case "isprint": return a(0) >= 32 && a(0) < 127 ? 1 : 0;
            case "iscntrl": return a(0) < 32 || a(0) == 127 ? 1 : 0;
            case "atoi": case "atol": { boolean[] ok = new boolean[1]; int[] cs = new int[1]; return (int) parseInt(mem.cstr(a(0)), 10, ok, cs); }
            case "strtol": case "strtoul": {
                boolean[] ok = new boolean[1]; int[] cs = new int[1];
                String s = mem.cstr(a(0));
                long v = parseInt(s, a(2), ok, cs);
                if (a(1) != 0) mem.w32(a(1), a(0) + cs[0]);
                return (int) v; }
            case "atof": case "strtod": {
                String s = mem.cstr(a(0)).trim(); int end = 0;
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^[+-]?(\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?|inf|nan)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
                double v = 0;
                if (mt.find()) { try { v = Double.parseDouble(mt.group()); } catch (NumberFormatException ex) { v = 0; } end = mt.end(); }
                if (n.equals("strtod") && a(1) != 0) mem.w32(a(1), a(0) + (s.isEmpty() ? 0 : mem.cstr(a(0)).indexOf(s) + end));
                cpu.fpu.push(v);
                return 0; }
            case "abs": case "labs": return Math.abs(a(0));
            case "rand": return rng.nextInt(32768);
            case "srand": rng.setSeed(a(0)); return 0;
            case "time": { int t = (int) (System.currentTimeMillis() / 1000); if (a(0) != 0) mem.w32(a(0), t); return t; }
            case "clock": return (int) ((System.nanoTime() - startNanos) / 1000000L);
            case "getenv": {
                String v = env.get(mem.cstr(a(0)));
                if (v == null) return 0;
                int p = allocZero(v.length() + 1); mem.putCstr(p, v); return p; }
            case "GetEnvironmentVariableA": {
                String v = env.get(mem.cstr(a(0)));
                if (v == null) { lastError = 203; return 0; }
                if (v.length() + 1 > a(2)) return v.length() + 1;
                mem.putCstr(a(1), v); return v.length(); }
            case "SetEnvironmentVariableA": if (a(1) == 0) env.remove(mem.cstr(a(0))); else env.put(mem.cstr(a(0)), mem.cstr(a(1))); return 1;
            case "GetEnvironmentStringsA": case "GetEnvironmentStringsW": {
                boolean w = n.endsWith("W"); StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : env.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\0');
                sb.append('\0');
                int p = allocZero(sb.length() * 2 + 4);
                for (int i = 0; i < sb.length(); i++) { if (w) mem.w16(p + 2 * i, sb.charAt(i)); else mem.w8(p + i, sb.charAt(i)); }
                return p; }
            case "FreeEnvironmentStringsA": case "FreeEnvironmentStringsW": return 1;
            case "system": return -1;
            case "GetACP": case "GetOEMCP": return 1252;
            case "IsValidCodePage": return 1;
            case "GetCPInfo": { mem.w32(a(1), 1); mem.w32(a(1) + 4, 0x3F); return 1; }
            case "GetUserDefaultLCID": return 0x409;
            case "GetStringTypeW": { for (int i = 0; i < a(2); i++) { int ch = mem.r16(a(1) + 2 * i), t = 0; if (Character.isLetter(ch)) t |= 0x100 | (Character.isUpperCase(ch) ? 1 : 2); if (Character.isDigit(ch)) t |= 4; if (Character.isWhitespace(ch)) t |= 8; mem.w16(a(3) + 2 * i, t); } return 1; }
            case "MultiByteToWideChar": {
                int len = a(3); String s = len < 0 ? mem.cstr(a(2)) : latin(mem.read(a(2), len));
                int cnt = len < 0 ? s.length() + 1 : len;
                if (a(5) == 0) return cnt;
                for (int i = 0; i < cnt && i < a(5); i++) mem.w16(a(4) + 2 * i, i < s.length() ? s.charAt(i) : 0);
                return cnt; }
            case "WideCharToMultiByte": {
                int len = a(3); String s;
                if (len < 0) s = wstr(a(2)); else { StringBuilder sb = new StringBuilder(); for (int i = 0; i < len; i++) sb.append((char) mem.r16(a(2) + 2 * i)); s = sb.toString(); }
                int cnt = len < 0 ? s.length() + 1 : len;
                if (a(5) == 0) return cnt;
                for (int i = 0; i < cnt && i < a(5); i++) mem.w8(a(4) + i, i < s.length() ? s.charAt(i) : 0);
                return cnt; }
            // ===== kernel32 file system =====
            case "CreateFileA": case "CreateFileW": {
                String path = n.endsWith("W") ? wstr(a(0)) : mem.cstr(a(0));
                int access = a(1), disp = a(4);
                if (path.equalsIgnoreCase("CONOUT$")) return 2;
                if (path.equalsIgnoreCase("CONIN$")) return 1;
                File f = file(path);
                try {
                    if (disp == 1 && f.exists()) { lastError = 80; return -1; }
                    if ((disp == 3 || disp == 5) && !f.exists()) { lastError = 2; return -1; }
                    boolean write = (access & 0x40000000) != 0 || (access & 0x10000000) != 0;
                    RandomAccessFile raf = openFile(f, write || disp == 2 || disp == 1 || disp == 4 ? "rw" : "r", disp == 2 || disp == 5);
                    int h = nextHandle++;
                    files.put(h, raf);
                    lastError = 0;
                    return h;
                } catch (IOException ex) { lastError = 2; return -1; }
            }
            case "CloseHandle": { RandomAccessFile f = files.remove(a(0)); if (f != null) try { f.close(); } catch (IOException ignored) { } return 1; }
            case "GetFileSize": { RandomAccessFile f = files.get(a(0)); try { return f == null ? -1 : (int) f.length(); } catch (IOException ex) { return -1; } }
            case "GetFileSizeEx": { RandomAccessFile f = files.get(a(0)); try { if (f == null) return 0; mem.w64(a(1), f.length()); return 1; } catch (IOException ex) { return 0; } }
            case "SetFilePointer": {
                RandomAccessFile f = files.get(a(0));
                try { if (f == null) return -1; long base = a(3) == 0 ? 0 : a(3) == 1 ? f.getFilePointer() : f.length(); long np = base + a(1); f.seek(np); return (int) np; } catch (IOException ex) { return -1; } }
            case "SetFilePointerEx": {
                RandomAccessFile f = files.get(a(0));
                try { if (f == null) return 0; long base = a(4) == 0 ? 0 : a(4) == 1 ? f.getFilePointer() : f.length(); long np = base + i64(1); f.seek(np); if (a(3) != 0) mem.w64(a(3), np); return 1; } catch (IOException ex) { return 0; } }
            case "SetEndOfFile": { RandomAccessFile f = files.get(a(0)); try { if (f != null) f.setLength(f.getFilePointer()); return 1; } catch (IOException ex) { return 0; } }
            case "FlushFileBuffers": return 1;
            case "DeleteFileA": return file(mem.cstr(a(0))).delete() ? 1 : 0;
            case "remove": return file(mem.cstr(a(0))).delete() ? 0 : -1;
            case "rename": return file(mem.cstr(a(0))).renameTo(file(mem.cstr(a(1)))) ? 0 : -1;
            case "CreateDirectoryA": return file(mem.cstr(a(0))).mkdir() ? 1 : 0;
            case "RemoveDirectoryA": return file(mem.cstr(a(0))).delete() ? 1 : 0;
            case "GetFileAttributesA": { File f = file(mem.cstr(a(0))); return !f.exists() ? -1 : f.isDirectory() ? 0x10 : 0x20; }
            case "GetCurrentDirectoryA": { if (cwd.length() + 1 > a(0)) return cwd.length() + 1; mem.putCstr(a(1), cwd); return cwd.length(); }
            case "SetCurrentDirectoryA": { File f = file(mem.cstr(a(0))); if (!f.isDirectory()) return 0; cwd = WinPath.toWin(root, f); return 1; }
            case "GetTempPathA": { String t = "C:\\Users\\Public\\Temp\\"; file(t).mkdirs(); mem.putCstr(a(1), t); return t.length(); }
            case "GetSystemDirectoryA": mem.putCstr(a(0), "C:\\Windows\\System32"); return 19;
            case "GetWindowsDirectoryA": mem.putCstr(a(0), "C:\\Windows"); return 10;
            case "FindFirstFileA": {
                String pat = mem.cstr(a(0));
                int sl = Math.max(pat.lastIndexOf('\\'), pat.lastIndexOf('/'));
                String dirPart = sl >= 0 ? pat.substring(0, sl + 1) : "", wild = pat.substring(sl + 1);
                File d = file(dirPart.isEmpty() ? "." : dirPart);
                final java.util.regex.Pattern rx = java.util.regex.Pattern.compile(wild.replace(".", "\\.").replace("*", ".*").replace("?", "."), java.util.regex.Pattern.CASE_INSENSITIVE);
                File[] kids = d.listFiles();
                ArrayList<File> hit = new ArrayList<>();
                if (kids != null) { Arrays.sort(kids); for (File k : kids) if (rx.matcher(k.getName()).matches()) hit.add(k); }
                if (hit.isEmpty()) { lastError = 2; return -1; }
                java.util.Iterator<File> it = hit.iterator();
                int h = nextHandle++;
                finds.put(h, it);
                fillFind(a(1), it.next());
                return h; }
            case "FindNextFileA": { java.util.Iterator<File> it = finds.get(a(0)); if (it == null || !it.hasNext()) { lastError = 18; return 0; } fillFind(a(1), it.next()); return 1; }
            case "FindClose": finds.remove(a(0)); return 1;
            // ===== C stdio =====
            case "puts": out(mem.cstr(a(0)) + "\n"); return 1;
            case "putchar": out(String.valueOf((char) (a(0) & 0xFF))); return a(0);
            case "getchar": case "_getch": case "_getche": return con.read();
            case "_kbhit": return 0;
            case "printf": { String s = format(mem.cstr(a(0)), stackArgs(1)); out(s); return s.length(); }
            case "vprintf": { String s = format(mem.cstr(a(0)), vaList(a(1))); out(s); return s.length(); }
            case "sprintf": { String s = format(mem.cstr(a(1)), stackArgs(2)); mem.putCstr(a(0), s); return s.length(); }
            case "snprintf": case "_snprintf": { String s = format(mem.cstr(a(2)), stackArgs(3)); putLimited(a(0), a(1), s); return s.length(); }
            case "vsprintf": { String s = format(mem.cstr(a(1)), vaList(a(2))); mem.putCstr(a(0), s); return s.length(); }
            case "vsnprintf": case "_vsnprintf": { String s = format(mem.cstr(a(2)), vaList(a(3))); putLimited(a(0), a(1), s); return s.length(); }
            case "fprintf": { String s = format(mem.cstr(a(1)), stackArgs(2)); fwriteTo(a(0), s.getBytes(StandardCharsets.ISO_8859_1)); return s.length(); }
            case "vfprintf": { String s = format(mem.cstr(a(1)), vaList(a(2))); fwriteTo(a(0), s.getBytes(StandardCharsets.ISO_8859_1)); return s.length(); }
            case "__stdio_common_vfprintf": { String s = format(mem.cstr(a(3)), vaList(a(5))); fwriteTo(a(2), s.getBytes(StandardCharsets.ISO_8859_1)); return s.length(); }
            case "__stdio_common_vsprintf": { String s = format(mem.cstr(a(4)), vaList(a(6))); putLimited(a(2), a(3), s); return s.length(); }
            case "__stdio_common_vsnprintf_s": { String s = format(mem.cstr(a(5)), vaList(a(7))); putLimited(a(2), a(3), s); return s.length(); }
            case "__acrt_iob_func": return iobBase + 32 * a(0);
            case "__iob_func": return iobBase;
            case "fileno": case "_fileno": return a(0) == iobBase ? 0 : a(0) == iobBase + 32 ? 1 : a(0) == iobBase + 64 ? 2 : 3;
            case "_setmode": return 0x4000;
            case "fwrite": { int total = a(1) * a(2); if (total == 0) return 0; int w = fwriteTo(a(3), mem.read(a(0), total)); return a(1) == 0 ? 0 : w / a(1); }
            case "fread": {
                int total = a(1) * a(2), got = 0;
                for (; got < total; got++) { int ch = fgetcOf(a(3)); if (ch < 0) break; mem.w8(a(0) + got, ch); }
                return a(1) == 0 ? 0 : got / a(1); }
            case "fputs": return fwriteTo(a(1), mem.cstr(a(0)).getBytes(StandardCharsets.ISO_8859_1)) >= 0 ? 1 : -1;
            case "fputc": case "putc": fwriteTo(a(1), new byte[]{(byte) a(0)}); return a(0) & 0xFF;
            case "fgetc": case "getc": return fgetcOf(a(0));
            case "ungetc": { CFile cf = cfiles.get(a(1)); if (cf != null) cf.unget = a(0) & 0xFF; return a(0); }
            case "fgets": {
                int i = 0;
                while (i < a(1) - 1) { int ch = fgetcOf(a(2)); if (ch < 0) break; mem.w8(a(0) + i++, ch); if (ch == '\n') break; }
                if (i == 0) return 0;
                mem.w8(a(0) + i, 0); return a(0); }
            case "fopen": return fopen(mem.cstr(a(0)), mem.cstr(a(1)));
            case "fclose": { CFile cf = cfiles.remove(a(0)); if (cf != null) try { cf.raf.close(); } catch (IOException ignored) { } return 0; }
            case "fflush": return 0;
            case "feof": { CFile cf = cfiles.get(a(0)); return cf != null && cf.eof ? 1 : 0; }
            case "ferror": return 0;
            case "fseek": { CFile cf = cfiles.get(a(0)); if (cf == null) return -1; try { long base = a(2) == 0 ? 0 : a(2) == 1 ? cf.raf.getFilePointer() : cf.raf.length(); cf.raf.seek(base + a(1)); cf.eof = false; return 0; } catch (IOException e) { return -1; } }
            case "ftell": { CFile cf = cfiles.get(a(0)); try { return cf == null ? -1 : (int) cf.raf.getFilePointer(); } catch (IOException e) { return -1; } }
            case "rewind": { CFile cf = cfiles.get(a(0)); try { if (cf != null) { cf.raf.seek(0); cf.eof = false; } } catch (IOException ignored) { } return 0; }
            case "setvbuf": return 0;
            // ===== CRT startup =====
            case "__p___argc": return argcCell;
            case "__p___argv": return argvCell;
            case "__p__environ": return environCell;
            case "__p__fmode": return fmodeCell;
            case "__p__commode": return commodeCell;
            case "_set_fmode": case "_configure_narrow_argv": case "_initialize_narrow_environment": case "_set_app_type": case "__set_app_type":
            case "_set_new_mode": case "_configthreadlocale": case "_set_invalid_parameter_handler": case "__setusermatherr": case "_controlfp": case "_controlfp_s": case "_amsg_exit":
                return 0;
            case "__getmainargs": mem.w32(a(0), mem.r32(argcCell)); mem.w32(a(1), argvPtr); mem.w32(a(2), mem.r32(environCell)); return 0;
            case "_initterm": {
                for (int p = a(0); p < a(1); p += 4) { int fn = mem.r32(p); if (fn != 0) callGuest(fn); }
                return 0; }
            case "_initterm_e": {
                for (int p = a(0); p < a(1); p += 4) { int fn = mem.r32(p); if (fn != 0) { int r = callGuest(fn); if (r != 0) return r; } }
                return 0; }
            case "qsort": return qsort(a(0), a(1), a(2), a(3));
            case "bsearch": {
                int lo = 0, hi = a(2) - 1;
                while (lo <= hi) { int mid = (lo + hi) >>> 1; int p = a(1) + mid * a(3); int c = callGuest(a(4), a(0), p); if (c == 0) return p; if (c < 0) hi = mid - 1; else lo = mid + 1; }
                return 0; }
            // ===== math (results are returned on the x87 stack) =====
            case "sqrt": cpu.fpu.push(Math.sqrt(dbl(0))); return 0;
            case "sin": cpu.fpu.push(Math.sin(dbl(0))); return 0;
            case "cos": cpu.fpu.push(Math.cos(dbl(0))); return 0;
            case "tan": cpu.fpu.push(Math.tan(dbl(0))); return 0;
            case "asin": cpu.fpu.push(Math.asin(dbl(0))); return 0;
            case "acos": cpu.fpu.push(Math.acos(dbl(0))); return 0;
            case "atan": cpu.fpu.push(Math.atan(dbl(0))); return 0;
            case "atan2": cpu.fpu.push(Math.atan2(dbl(0), dbl(2))); return 0;
            case "sinh": cpu.fpu.push(Math.sinh(dbl(0))); return 0;
            case "cosh": cpu.fpu.push(Math.cosh(dbl(0))); return 0;
            case "tanh": cpu.fpu.push(Math.tanh(dbl(0))); return 0;
            case "exp": cpu.fpu.push(Math.exp(dbl(0))); return 0;
            case "log": cpu.fpu.push(Math.log(dbl(0))); return 0;
            case "log10": cpu.fpu.push(Math.log10(dbl(0))); return 0;
            case "pow": cpu.fpu.push(Math.pow(dbl(0), dbl(2))); return 0;
            case "floor": cpu.fpu.push(Math.floor(dbl(0))); return 0;
            case "ceil": cpu.fpu.push(Math.ceil(dbl(0))); return 0;
            case "fabs": cpu.fpu.push(Math.abs(dbl(0))); return 0;
            case "fmod": cpu.fpu.push(dbl(0) % dbl(2)); return 0;
            case "round": cpu.fpu.push(Math.signum(dbl(0)) * Math.floor(Math.abs(dbl(0)) + 0.5)); return 0;
            case "trunc": cpu.fpu.push(dbl(0) < 0 ? Math.ceil(dbl(0)) : Math.floor(dbl(0))); return 0;
            case "lround": { double v = dbl(0); return (int) (Math.signum(v) * Math.floor(Math.abs(v) + 0.5)); }
            case "llround": { double v = dbl(0); long r = (long) (Math.signum(v) * Math.floor(Math.abs(v) + 0.5)); cpu.r[Cpu.EDX] = (int) (r >> 32); return (int) r; }
            case "lrint": return (int) Math.rint(dbl(0));
            case "rint": case "nearbyint": cpu.fpu.push(Math.rint(dbl(0))); return 0;
            case "fmax": cpu.fpu.push(Math.max(dbl(0), dbl(2))); return 0;
            case "fmin": cpu.fpu.push(Math.min(dbl(0), dbl(2))); return 0;
            case "copysign": cpu.fpu.push(Math.copySign(dbl(0), dbl(2))); return 0;
            case "hypot": cpu.fpu.push(Math.hypot(dbl(0), dbl(2))); return 0;
            case "cbrt": cpu.fpu.push(Math.cbrt(dbl(0))); return 0;
            case "log2": cpu.fpu.push(Math.log(dbl(0)) / Math.log(2)); return 0;
            case "exp2": cpu.fpu.push(Math.pow(2, dbl(0))); return 0;
            case "expm1": cpu.fpu.push(Math.expm1(dbl(0))); return 0;
            case "log1p": cpu.fpu.push(Math.log1p(dbl(0))); return 0;
            case "sinf": cpu.fpu.push((float) Math.sin(Float.intBitsToFloat(a(0)))); return 0;
            case "cosf": cpu.fpu.push((float) Math.cos(Float.intBitsToFloat(a(0)))); return 0;
            case "tanf": cpu.fpu.push((float) Math.tan(Float.intBitsToFloat(a(0)))); return 0;
            case "atanf": cpu.fpu.push((float) Math.atan(Float.intBitsToFloat(a(0)))); return 0;
            case "asinf": cpu.fpu.push((float) Math.asin(Float.intBitsToFloat(a(0)))); return 0;
            case "acosf": cpu.fpu.push((float) Math.acos(Float.intBitsToFloat(a(0)))); return 0;
            case "expf": cpu.fpu.push((float) Math.exp(Float.intBitsToFloat(a(0)))); return 0;
            case "logf": cpu.fpu.push((float) Math.log(Float.intBitsToFloat(a(0)))); return 0;
            case "log10f": cpu.fpu.push((float) Math.log10(Float.intBitsToFloat(a(0)))); return 0;
            case "powf": cpu.fpu.push((float) Math.pow(Float.intBitsToFloat(a(0)), Float.intBitsToFloat(a(1)))); return 0;
            case "atan2f": cpu.fpu.push((float) Math.atan2(Float.intBitsToFloat(a(0)), Float.intBitsToFloat(a(1)))); return 0;
            case "fmodf": cpu.fpu.push((float) (Float.intBitsToFloat(a(0)) % Float.intBitsToFloat(a(1)))); return 0;
            case "roundf": { float v = Float.intBitsToFloat(a(0)); cpu.fpu.push(Math.signum(v) * Math.floor(Math.abs(v) + 0.5)); return 0; }
            case "truncf": { float v = Float.intBitsToFloat(a(0)); cpu.fpu.push(v < 0 ? Math.ceil(v) : Math.floor(v)); return 0; }
            case "_initialize_onexit_table": mem.w32(a(0), 0); mem.w32(a(0) + 4, 0); mem.w32(a(0) + 8, 0); return 0;
            case "_register_onexit_function": atexitFns.add(a(1)); return 0;
            case "_execute_onexit_table": doExitHandlers(); return 0;
            case "_get_initial_narrow_environment": return mem.r32(environCell);
            case "_set_abort_behavior": case "_register_thread_local_exe_atexit_callback": return 0;
            case "_invalid_parameter_noinfo_noreturn": throw new EmuException("Invalid parameter passed to the C runtime");
            case "ldexp": cpu.fpu.push(dbl(0) * Math.pow(2, a(2))); return 0;
            case "sqrtf": cpu.fpu.push(Math.sqrt(Float.intBitsToFloat(a(0)))); return 0;
            case "floorf": cpu.fpu.push(Math.floor(Float.intBitsToFloat(a(0)))); return 0;
            case "ceilf": cpu.fpu.push(Math.ceil(Float.intBitsToFloat(a(0)))); return 0;
            case "fabsf": cpu.fpu.push(Math.abs(Float.intBitsToFloat(a(0)))); return 0;
            case "_ftol2_sse": case "_ftol2": case "_ftol": { long v = (long) cpu.fpu.pop(); cpu.r[Cpu.EDX] = (int) (v >> 32); return (int) v; }
            // ===== 64-bit integer helpers =====
            case "__allmul": { long r = i64(0) * i64(2); cpu.r[Cpu.EDX] = (int) (r >> 32); return (int) r; }
            case "__alldiv": case "__divdi3": { long r = i64(0) / i64(2); cpu.r[Cpu.EDX] = (int) (r >> 32); return (int) r; }
            case "__allrem": case "__moddi3": { long r = i64(0) % i64(2); cpu.r[Cpu.EDX] = (int) (r >> 32); return (int) r; }
            case "__aulldiv": case "__udivdi3": { long r = Long.divideUnsigned(i64(0), i64(2)); cpu.r[Cpu.EDX] = (int) (r >> 32); return (int) r; }
            case "__aullrem": case "__umoddi3": { long r = Long.remainderUnsigned(i64(0), i64(2)); cpu.r[Cpu.EDX] = (int) (r >> 32); return (int) r; }
            case "__allshl": case "__aullshr": case "__allshr": {
                long v = (cpu.r[Cpu.EAX] & 0xFFFFFFFFL) | ((long) cpu.r[Cpu.EDX] << 32); int sh = cpu.r[Cpu.ECX] & 63;
                v = n.equals("__allshl") ? v << sh : n.equals("__aullshr") ? v >>> sh : v >> sh;
                cpu.r[Cpu.EDX] = (int) (v >> 32); return (int) v; }
            default:
                if (gui != null) return gui.call(n, this);
                throw new EmuException("Unimplemented API: " + n);
        }
    }

    private void putLimited(int dst, int cap, String s) {
        if (cap == 0) return;
        if (cap > 0 && s.length() >= cap) s = s.substring(0, cap - 1);
        mem.putCstr(dst, s);
    }

    private void fillFind(int p, File f) {
        for (int i = 0; i < 320; i += 4) mem.w32(p + i, 0);
        mem.w32(p, f.isDirectory() ? 0x10 : 0x20);
        long ft = (f.lastModified() + 11644473600000L) * 10000L;
        mem.w64(p + 4, ft); mem.w64(p + 12, ft); mem.w64(p + 20, ft);
        mem.w32(p + 28, (int) (f.length() >>> 32)); mem.w32(p + 32, (int) f.length());
        mem.putCstr(p + 44, f.getName());
    }

    private int qsort(int base, int n, int size, int cmp) {
        if (n < 2 || size <= 0) return 0;
        byte[][] el = new byte[n][];
        for (int i = 0; i < n; i++) el[i] = mem.read(base + i * size, size);
        int sa = alloc(size), sb = alloc(size);
        try {
            Arrays.sort(el, (x, y) -> { mem.write(sa, x, 0, size); mem.write(sb, y, 0, size); return callGuest(cmp, sa, sb); });
        } catch (IllegalArgumentException e) { /* inconsistent comparator: keep partial order */ }
        for (int i = 0; i < n; i++) mem.write(base + i * size, el[i], 0, size);
        free(sa); free(sb);
        return 0;
    }
}
