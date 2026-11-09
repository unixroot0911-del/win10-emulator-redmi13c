package com.unixroot.win10.core;

/** Sparse 4 GB paged little-endian memory for the emulated 32-bit address space. */
public final class Memory {
    private final byte[][] pages = new byte[1 << 20][];

    private byte[] page(int addr, boolean create) {
        int i = addr >>> 12;
        byte[] p = pages[i];
        if (p == null && create) { p = new byte[4096]; pages[i] = p; }
        return p;
    }

    public int r8(int a) {
        byte[] p = page(a, false);
        return p == null ? 0 : p[a & 0xFFF] & 0xFF;
    }

    public void w8(int a, int v) { page(a, true)[a & 0xFFF] = (byte) v; }

    public int r16(int a) { return r8(a) | (r8(a + 1) << 8); }

    public void w16(int a, int v) { w8(a, v); w8(a + 1, v >> 8); }

    public int r32(int a) {
        int o = a & 0xFFF;
        if (o <= 4092) {
            byte[] p = page(a, false);
            if (p == null) return 0;
            return (p[o] & 0xFF) | ((p[o + 1] & 0xFF) << 8) | ((p[o + 2] & 0xFF) << 16) | ((p[o + 3] & 0xFF) << 24);
        }
        return r16(a) | (r16(a + 2) << 16);
    }

    public void w32(int a, int v) {
        int o = a & 0xFFF;
        if (o <= 4092) {
            byte[] p = page(a, true);
            p[o] = (byte) v; p[o + 1] = (byte) (v >> 8); p[o + 2] = (byte) (v >> 16); p[o + 3] = (byte) (v >> 24);
        } else { w16(a, v); w16(a + 2, v >>> 16); }
    }

    public void write(int addr, byte[] src, int off, int len) {
        for (int i = 0; i < len; i++) w8(addr + i, src[off + i]);
    }

    public byte[] read(int addr, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) r8(addr + i);
        return b;
    }

    /** Read a NUL-terminated ISO-8859-1 string (capped at 64 KB). */
    public String cstr(int addr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65536; i++) {
            int c = r8(addr + i);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    public void putCstr(int addr, String s) {
        for (int i = 0; i < s.length(); i++) w8(addr + i, s.charAt(i));
        w8(addr + s.length(), 0);
    }
}
