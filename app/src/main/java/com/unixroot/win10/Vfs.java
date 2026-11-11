package com.unixroot.win10;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Virtual drive C: stored in the app's private files directory. */
final class Vfs {
    final File root;

    Vfs(Context c) {
        root = new File(c.getFilesDir(), "C");
        for (String d : new String[]{"Windows\\System32", "Users\\Public\\Documents", "Users\\Public\\Downloads",
                "Users\\Public\\Desktop", "Program Files\\Samples"}) {
            resolve("C:\\" + d).mkdirs();
        }
        copyAsset(c, "hello.exe", resolve("C:\\Program Files\\Samples\\hello.exe"));
        copyAsset(c, "calc.exe", resolve("C:\\Program Files\\Samples\\calc.exe"));
        copyAsset(c, "guidemo.exe", resolve("C:\\Program Files\\Samples\\guidemo.exe"));
        copyAsset(c, "wfdemo.exe", resolve("C:\\Program Files\\Samples\\wfdemo.exe"));
        copyAsset(c, "netdemo.exe", resolve("C:\\Program Files\\Samples\\netdemo.exe"));
        File readme = resolve("C:\\Users\\Public\\Documents\\readme.txt");
        if (!readme.exists()) {
            writeText(readme, "Win10 Emulator\r\n\r\nDrive C: lives inside this app.\r\n"
                    + "Use Files > Import to copy your own 32-bit .exe files here, then open them from the Terminal.\r\n"
                    + "Try:  C:\\Program Files\\Samples\\hello.exe  and  calc.exe\r\n");
        }
    }

    /** Resolve a Windows-style absolute path (C:\a\b) to a File, ignoring case where entries exist. */
    File resolve(String win) {
        String s = win.replace('/', '\\');
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

    String toWin(File f) {
        String rp = root.getAbsolutePath(), fp = f.getAbsolutePath();
        if (!fp.startsWith(rp)) return "C:\\";
        String rel = fp.substring(rp.length()).replace('/', '\\');
        return "C:" + (rel.isEmpty() ? "\\" : rel);
    }

    static void copyAsset(Context c, String name, File dst) {
        if (dst.exists()) return;
        try (InputStream in = c.getAssets().open(name); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ignored) { }
    }

    static byte[] readAll(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    static String readText(File f) {
        try { return new String(readAll(f), "UTF-8"); } catch (IOException e) { return ""; }
    }

    static boolean writeText(File f, String text) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
            return true;
        } catch (IOException e) { return false; }
    }

    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
