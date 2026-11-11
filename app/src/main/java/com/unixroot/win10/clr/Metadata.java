package com.unixroot.win10.clr;

import java.nio.charset.StandardCharsets;

/** ECMA-335 metadata reader for a .NET PE image. Rows are int[] (heap/coded columns already decoded to raw ints). */
public final class Metadata {
    public static final int MODULE = 0x00, TYPEREF = 0x01, TYPEDEF = 0x02, FIELD = 0x04, METHOD = 0x06, PARAM = 0x08, IFACEIMPL = 0x09,
            MEMBERREF = 0x0A, CONSTANT = 0x0B, CUSTOMATTR = 0x0C, CLASSLAYOUT = 0x0F, FIELDLAYOUT = 0x10, STANDALONESIG = 0x11,
            EVENTMAP = 0x12, EVENT = 0x14, PROPMAP = 0x15, PROPERTY = 0x17, METHODSEM = 0x18, METHODIMPL = 0x19, MODULEREF = 0x1A,
            TYPESPEC = 0x1B, IMPLMAP = 0x1C, FIELDRVA = 0x1D, ASSEMBLY = 0x20, ASSEMBLYREF = 0x23, NESTED = 0x29, GENERICPARAM = 0x2A,
            METHODSPEC = 0x2B, GPCONSTRAINT = 0x2C, MANIFESTRES = 0x28, EXPORTEDTYPE = 0x27;

    // coded index kinds
    private static final String[][] CODED = {
        /*0 TDR*/ {"02", "01", "1B"},
        /*1 HC*/ {"04", "08", "17"},
        /*2 HCA*/ {"06", "04", "01", "02", "08", "09", "0A", "00", "0E", "17", "14", "11", "1A", "1B", "20", "23", "26", "27", "28", "2A", "2C", "2B"},
        /*3 HFM*/ {"04", "08"},
        /*4 HDS*/ {"02", "06", "20"},
        /*5 MRP*/ {"02", "01", "1A", "06", "1B"},
        /*6 HS*/ {"14", "17"},
        /*7 MDR*/ {"06", "0A"},
        /*8 MF*/ {"04", "06"},
        /*9 IMP*/ {"26", "23", "27"},
        /*10 CAT*/ {"--", "--", "06", "0A", "--"},
        /*11 RS*/ {"00", "1A", "23", "01"},
        /*12 TOMD*/ {"02", "06"}};
    public static final int C_TDR = 0, C_HC = 1, C_HCA = 2, C_HFM = 3, C_HDS = 4, C_MRP = 5, C_HS = 6, C_MDR = 7, C_MF = 8, C_IMP = 9, C_CAT = 10, C_RS = 11, C_TOMD = 12;

    // column spec: '2','4' fixed; 'S' string,'G' guid,'B' blob; 'T'+hex table: use letters: tables as "t02"; coded as "c<n>"
    private static final String[][] SCHEMA = new String[0x2D][];
    static {
        s(0x00, "2", "S", "G", "G", "G"); s(0x01, "c11", "S", "S"); s(0x02, "4", "S", "S", "c0", "t04", "t06");
        s(0x03, "t04"); s(0x04, "2", "S", "B"); s(0x05, "t06"); s(0x06, "4", "2", "2", "S", "B", "t08"); s(0x07, "t08");
        s(0x08, "2", "2", "S"); s(0x09, "t02", "c0"); s(0x0A, "c5", "S", "B"); s(0x0B, "2", "c1", "B");
        s(0x0C, "c2", "c10", "B"); s(0x0D, "c3", "B"); s(0x0E, "2", "c4", "B"); s(0x0F, "2", "4", "t02"); s(0x10, "4", "t04");
        s(0x11, "B"); s(0x12, "t02", "t14"); s(0x13, "t14"); s(0x14, "2", "S", "c0"); s(0x15, "t02", "t17"); s(0x16, "t17");
        s(0x17, "2", "S", "B"); s(0x18, "2", "t06", "c6"); s(0x19, "t02", "c7", "c7"); s(0x1A, "S"); s(0x1B, "B");
        s(0x1C, "2", "c8", "S", "t1A"); s(0x1D, "4", "t04"); s(0x1E, "4", "4"); s(0x1F, "4"); s(0x20, "4", "2", "2", "2", "2", "4", "B", "S", "S");
        s(0x21, "4"); s(0x22, "4", "4", "4"); s(0x23, "2", "2", "2", "2", "4", "B", "S", "S", "B"); s(0x24, "4", "t23"); s(0x25, "4", "4", "4", "t23");
        s(0x26, "4", "S", "B"); s(0x27, "4", "4", "S", "S", "c9"); s(0x28, "4", "4", "S", "c9"); s(0x29, "t02", "t02");
        s(0x2A, "2", "2", "c12", "S"); s(0x2B, "c7", "B"); s(0x2C, "t2A", "c0");
    }
    private static void s(int t, String... c) { SCHEMA[t] = c; }

    public final byte[] img;
    public int entryPointToken;
    public int corFlags;
    public int[] rowCount = new int[0x2D];
    public int[][][] rows = new int[0x2D][][];
    private int strOff, strLen, usOff, usLen, blobOff, blobLen, guidOff;
    private int[] secVa, secRaw, secSize, secPtr;
    private int cliRva, resRva, resSize;
    public int versionMajor;

    /** True when the image is a PE file carrying a CLR (.NET) header. */
    public static boolean isManaged(byte[] b) {
        try {
            Metadata m = new Metadata(b);
            return m.entryPointToken != 0 || m.count(TYPEDEF) > 0;
        } catch (RuntimeException e) { return false; }
    }

    public Metadata(byte[] image) {
        img = image;
        parsePe();
    }

    private int u8(int o) { return img[o] & 0xFF; }
    private int u16(int o) { return u8(o) | u8(o + 1) << 8; }
    private int i32(int o) { return u16(o) | u16(o + 2) << 16; }

    public int rvaToOff(int rva) {
        for (int i = 0; i < secVa.length; i++) {
            int sz = Math.max(secSize[i], secRaw[i]);
            if (rva >= secVa[i] && rva < secVa[i] + sz) return secPtr[i] + (rva - secVa[i]);
        }
        throw new IllegalStateException("bad RVA " + Integer.toHexString(rva));
    }

    private void parsePe() {
        if (u16(0) != 0x5A4D) throw new IllegalStateException("not a PE file");
        int pe = i32(0x3C);
        if (i32(pe) != 0x4550) throw new IllegalStateException("bad PE signature");
        int nsec = u16(pe + 6), optSize = u16(pe + 20), opt = pe + 24;
        int magic = u16(opt);
        boolean pe32p = magic == 0x20B;
        int ddBase = opt + (pe32p ? 112 : 96);
        cliRva = i32(ddBase + 14 * 8);
        if (cliRva == 0) throw new IllegalStateException("not a .NET image");
        int sh = opt + optSize;
        secVa = new int[nsec]; secRaw = new int[nsec]; secSize = new int[nsec]; secPtr = new int[nsec];
        for (int i = 0; i < nsec; i++) {
            int o = sh + i * 40;
            secSize[i] = i32(o + 8); secVa[i] = i32(o + 12); secRaw[i] = i32(o + 16); secPtr[i] = i32(o + 20);
        }
        int cli = rvaToOff(cliRva);
        int mdRva = i32(cli + 8);
        corFlags = i32(cli + 16);
        entryPointToken = i32(cli + 20);
        resRva = i32(cli + 24); resSize = i32(cli + 28);
        int md = rvaToOff(mdRva);
        if (i32(md) != 0x424A5342) throw new IllegalStateException("bad metadata signature");
        int vlen = i32(md + 12);
        int p = md + 16 + vlen;
        int ns = u16(p + 2);
        p += 4;
        int tblOff = -1;
        for (int i = 0; i < ns; i++) {
            int off = i32(p), size = i32(p + 4);
            int q = p + 8;
            StringBuilder nm = new StringBuilder();
            while (img[q] != 0) nm.append((char) img[q++]);
            q++;
            q = (q - p + 3 & ~3) + p;
            p = q;
            String n = nm.toString();
            if (n.equals("#~") || n.equals("#-")) tblOff = md + off;
            else if (n.equals("#Strings")) { strOff = md + off; strLen = size; }
            else if (n.equals("#US")) { usOff = md + off; usLen = size; }
            else if (n.equals("#Blob")) { blobOff = md + off; blobLen = size; }
            else if (n.equals("#GUID")) guidOff = md + off;
        }
        if (tblOff < 0) throw new IllegalStateException("no #~ stream");
        readTables(tblOff);
    }

    private int idxSize(int table) { return rowCount[table] < 65536 ? 2 : 4; }
    private int codedSize(int kind) {
        int bits = 0, n = CODED[kind].length;
        while ((1 << bits) < n) bits++;
        int max = 0;
        for (String t : CODED[kind]) if (!t.equals("--")) max = Math.max(max, rowCount[Integer.parseInt(t, 16)]);
        return max < (1 << (16 - bits)) ? 2 : 4;
    }

    private void readTables(int t) {
        versionMajor = u8(t + 4);
        int heap = u8(t + 6);
        long valid = (i32(t + 8) & 0xFFFFFFFFL) | (long) i32(t + 12) << 32;
        int p = t + 24;
        for (int i = 0; i < 64; i++) if ((valid >> i & 1) != 0) { if (i < 0x2D) rowCount[i] = i32(p); p += 4; }
        int sS = (heap & 1) != 0 ? 4 : 2, sG = (heap & 2) != 0 ? 4 : 2, sB = (heap & 4) != 0 ? 4 : 2;
        for (int tb = 0; tb < 0x2D; tb++) {
            if (rowCount[tb] == 0) continue;
            String[] sc = SCHEMA[tb];
            int[] w = new int[sc.length];
            for (int c = 0; c < sc.length; c++) {
                String s = sc[c];
                char k = s.charAt(0);
                w[c] = k == '2' ? 2 : k == '4' ? 4 : k == 'S' ? sS : k == 'G' ? sG : k == 'B' ? sB
                        : k == 't' ? idxSize(Integer.parseInt(s.substring(1), 16)) : codedSize(Integer.parseInt(s.substring(1)));
            }
            int[][] r = new int[rowCount[tb]][sc.length];
            for (int i = 0; i < r.length; i++)
                for (int c = 0; c < sc.length; c++) { r[i][c] = w[c] == 2 ? u16(p) : i32(p); p += w[c]; }
            rows[tb] = r;
        }
    }

    /** Decodes a coded index column value into {table, row(1-based)}. */
    public static int[] decode(int kind, int v) {
        int bits = 0, n = CODED[kind].length;
        while ((1 << bits) < n) bits++;
        int tag = v & ((1 << bits) - 1);
        String t = tag < n ? CODED[kind][tag] : "--";
        if (t.equals("--")) return new int[]{-1, 0};
        return new int[]{Integer.parseInt(t, 16), v >>> bits};
    }

    public int[] row(int table, int row1) { return rows[table][row1 - 1]; }
    public int count(int table) { return rowCount[table]; }

    public String string(int idx) {
        if (idx == 0) return "";
        int e = strOff + idx;
        int s = e;
        while (img[e] != 0) e++;
        return new String(img, s, e - s, StandardCharsets.UTF_8);
    }

    /** Blob heap entry as a copied byte[]. */
    public byte[] blob(int idx) {
        if (idx == 0) return new byte[0];
        int p = blobOff + idx;
        int len, b = u8(p);
        if ((b & 0x80) == 0) { len = b; p += 1; }
        else if ((b & 0xC0) == 0x80) { len = (b & 0x3F) << 8 | u8(p + 1); p += 2; }
        else { len = (b & 0x1F) << 24 | u8(p + 1) << 16 | u8(p + 2) << 8 | u8(p + 3); p += 4; }
        byte[] r = new byte[len];
        System.arraycopy(img, p, r, 0, len);
        return r;
    }

    /** User string (#US) heap entry for ldstr tokens. */
    public String userString(int idx) {
        int p = usOff + idx;
        int len, b = u8(p);
        if ((b & 0x80) == 0) { len = b; p += 1; }
        else if ((b & 0xC0) == 0x80) { len = (b & 0x3F) << 8 | u8(p + 1); p += 2; }
        else { len = (b & 0x1F) << 24 | u8(p + 1) << 16 | u8(p + 2) << 8 | u8(p + 3); p += 4; }
        len = Math.max(0, len - 1);
        return new String(img, p, len, StandardCharsets.UTF_16LE);
    }

    /** Returns the raw bytes of a method body starting at the given RVA. */
    public int methodBodyOffset(int rva) { return rvaToOff(rva); }

    public java.util.List<String> managedResourceNames() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        if (resRva != 0 && rows[MANIFESTRES] != null) for (int[] r : rows[MANIFESTRES]) l.add(string(r[2]));
        return l;
    }

    public byte[] managedResource(String name) {
        if (resRva == 0) return null;
        int base = rvaToOff(resRva);
        if (rows[MANIFESTRES] == null) return null;
        for (int[] r : rows[MANIFESTRES]) {
            if (string(r[2]).equals(name)) {
                int off = base + r[0];
                int len = i32(off);
                byte[] b = new byte[len];
                System.arraycopy(img, off + 4, b, 0, len);
                return b;
            }
        }
        return null;
    }

    public byte[] fieldRvaData(int field1, int size) {
        if (rows[FIELDRVA] == null) return new byte[size];
        for (int[] r : rows[FIELDRVA]) if (r[1] == field1) {
            byte[] b = new byte[size];
            System.arraycopy(img, rvaToOff(r[0]), b, 0, size);
            return b;
        }
        return new byte[size];
    }

    public String typeRefName(int row) {
        int[] r = row(TYPEREF, row);
        String ns = string(r[2]), n = string(r[1]);
        return ns.isEmpty() ? n : ns + "." + n;
    }
}
