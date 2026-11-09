package com.unixroot.win10.core;

import java.io.File;

/** Maps Windows paths (C:\a\b, relative paths, any letter case) onto the app's private drive C. */
public final class WinPath {
    private WinPath() { }

    /** Absolute Windows path for p, using cwd (like C:\Users\Public) for relative paths. */
    public static String absolute(String cwd, String p) {
        String s = p.replace('/', '\\');
        if (s.length() >= 2 && s.charAt(1) == ':') return s;
        if (s.startsWith("\\")) return "C:" + s;
        return cwd + (cwd.endsWith("\\") ? "" : "\\") + s;
    }

    public static File resolve(File root, String cwd, String p) {
        String s = absolute(cwd, p);
        if (s.length() >= 2 && s.charAt(1) == ':') s = s.substring(2);
        File cur = root;
        for (String part : s.split("\\\\")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!cur.equals(root)) cur = cur.getParentFile(); continue; }
            File next = new File(cur, part);
            if (!next.exists()) {
                String[] kids = cur.list();
                if (kids != null) for (String k : kids) if (k.equalsIgnoreCase(part)) { next = new File(cur, k); break; }
            }
            cur = next;
        }
        return cur;
    }

    public static String toWin(File root, File f) {
        String rp = root.getAbsolutePath(), fp = f.getAbsolutePath();
        if (!fp.startsWith(rp)) return "C:\\";
        String rel = fp.substring(rp.length()).replace('/', '\\');
        return "C:" + (rel.isEmpty() ? "\\" : rel);
    }
}
