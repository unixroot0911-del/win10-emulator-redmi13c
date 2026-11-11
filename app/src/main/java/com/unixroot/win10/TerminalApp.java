package com.unixroot.win10;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.unixroot.win10.core.Emulator;
import com.unixroot.win10.core.GuiHost;
import com.unixroot.win10.core.Surface;
import com.unixroot.win10.core.Win32Gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/** Command prompt that can also run 32-bit Windows .exe files on the built-in x86 emulator. */
final class TerminalApp extends LinearLayout implements Desktop.Closeable {
    private final Desktop d;
    private final WinFrame w;
    private final TextView out, prompt;
    private final ScrollView scroll;
    private final EditText in;
    private final StringBuilder buf = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean flushScheduled;
    private String cwd = "C:\\Users\\Public";
    private volatile Thread proc;
    private volatile Emulator emu;
    private final LinkedBlockingQueue<Integer> stdin = new LinkedBlockingQueue<>();

    private final Runnable flush = new Runnable() {
        @Override public void run() {
            String s;
            synchronized (pending) { s = pending.toString(); pending.setLength(0); flushScheduled = false; }
            buf.append(s.replace("\r", ""));
            if (buf.length() > 60000) buf.delete(0, buf.length() - 50000);
            out.setText(buf);
            scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
        }
    };

    TerminalApp(Desktop desk, WinFrame win, String arg) {
        super(desk.act);
        d = desk;
        w = win;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);

        LinearLayout tools = new LinearLayout(d.act);
        tools.setOrientation(HORIZONTAL);
        tools.setBackgroundColor(0xFF111111);
        Button stop = Ui.button(d.act, "Stop (Ctrl+C)", 0xFF3A3A3A, Color.WHITE);
        stop.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { stopProcess(); } });
        Button clr = Ui.button(d.act, "Clear", 0xFF3A3A3A, Color.WHITE);
        clr.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { clear(); } });
        tools.addView(stop, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 4, 3, 2, 3));
        tools.addView(clr, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 2, 3, 4, 3));
        addView(tools, new LayoutParams(Ui.MATCH, Ui.WRAP));

        scroll = new ScrollView(d.act);
        out = Ui.mono(d.act, 12);
        out.setPadding(Ui.dp(d.act, 6), Ui.dp(d.act, 4), Ui.dp(d.act, 6), Ui.dp(d.act, 4));
        out.setTextIsSelectable(true);
        scroll.addView(out);
        addView(scroll, Ui.lp(Ui.MATCH, 0, 1));

        LinearLayout row = new LinearLayout(d.act);
        row.setOrientation(HORIZONTAL);
        row.setBackgroundColor(0xFF101010);
        prompt = Ui.mono(d.act, 12);
        prompt.setTextColor(Color.rgb(120, 220, 120));
        prompt.setPadding(Ui.dp(d.act, 6), 0, 0, 0);
        row.addView(prompt, new LayoutParams(Ui.WRAP, Ui.WRAP));
        in = new EditText(d.act);
        in.setTextSize(12);
        in.setTextColor(Color.WHITE);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        in.setImeOptions(EditorInfo.IME_ACTION_SEND);
        in.setBackground(null);
        in.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                submit();
                return true;
            }
        });
        row.addView(in, Ui.lp(0, Ui.WRAP, 1));
        addView(row, new LayoutParams(Ui.MATCH, Ui.WRAP));

        print("Microsoft Windows [Version 10.0.19045.0]\n(c) Win10 Emulator - custom x86 interpreter. Type HELP.\n\n");
        updatePrompt();
        if (arg != null && !arg.isEmpty()) {
            File target = d.vfs.resolve(arg);
            if (target.isDirectory()) { cwd = d.vfs.toWin(target); updatePrompt(); }
            else if (target.isFile()) {
                // like double-clicking an .exe: run it with its own folder as the working directory
                cwd = d.vfs.toWin(target.getParentFile());
                print("Starting " + target.getName() + " in " + cwd + "\n\n");
                runProgram(target.getName(), "");
            } else print("Not found: " + arg + "\n");
        }
    }

    private void updatePrompt() { prompt.setText(proc != null ? "" : cwd + ">"); }

    private void print(String s) {
        synchronized (pending) {
            pending.append(s);
            if (!flushScheduled) { flushScheduled = true; out.postDelayed(flush, 40); }
        }
    }

    private void clear() {
        synchronized (pending) { pending.setLength(0); }
        buf.setLength(0);
        out.setText("");
    }

    private void submit() {
        String line = in.getText().toString();
        in.setText("");
        if (proc != null) {
            print(line + "\n");
            for (char c : (line + "\r\n").toCharArray()) stdin.add((int) (c & 0xFF));
            return;
        }
        print(cwd + ">" + line + "\n");
        try { exec(line); } catch (RuntimeException e) { print("Error: " + e + "\n"); }
        updatePrompt();
    }

    // ---------------- commands ----------------
    private static ArrayList<String> tokenize(String s) {
        ArrayList<String> r = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char c : s.toCharArray()) {
            if (c == '"') q = !q;
            else if (c == ' ' && !q) { if (cur.length() > 0) { r.add(cur.toString()); cur.setLength(0); } }
            else cur.append(c);
        }
        if (cur.length() > 0) r.add(cur.toString());
        return r;
    }

    private String abs(String p) {
        if (p.length() >= 2 && p.charAt(1) == ':') return p;
        if (p.startsWith("\\") || p.startsWith("/")) return "C:" + p;
        return cwd + (cwd.endsWith("\\") ? "" : "\\") + p;
    }

    private File file(String p) { return d.vfs.resolve(abs(p)); }

    private void exec(String line) {
        ArrayList<String> t = tokenize(line.trim());
        if (t.isEmpty()) return;
        String cmd = t.get(0).toLowerCase(Locale.US);
        String rest = line.trim().substring(t.get(0).length()).trim();
        switch (cmd) {
            case "help": case "?":
                print("HELP     this list            DIR [path]      list files\n"
                    + "CD path  change directory     TYPE file       show a text file\n"
                    + "MKDIR d  new folder           DEL file        delete file/empty folder\n"
                    + "COPY a b copy a file          REN a b         rename\n"
                    + "ECHO txt print text           CLS             clear screen\n"
                    + "VER      version              DATE / TIME     clock\n"
                    + "CURL url HTTP GET (uses your proxy from Settings)\n"
                    + "START app [file]  open Notepad/Browser/Files/Calculator/Settings\n"
                    + "name.exe args     run a 32-bit Windows program\n"
                    + "EXIT     close this window\n");
                return;
            case "cls": clear(); return;
            case "ver": print("Microsoft Windows [Version 10.0.19045.0]\n"); return;
            case "echo": print(rest + "\n"); return;
            case "whoami": print("win10emu\\user\n"); return;
            case "hostname": print("WIN10-EMU\n"); return;
            case "date": print(new SimpleDateFormat("EEE dd/MM/yyyy", Locale.US).format(new Date()) + "\n"); return;
            case "time": print(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "\n"); return;
            case "exit": d.close(w); return;
            case "cd": case "chdir": {
                if (t.size() < 2) { print(cwd + "\n"); return; }
                File f = file(rest);
                if (!f.isDirectory()) { print("The system cannot find the path specified.\n"); return; }
                cwd = d.vfs.toWin(f);
                return;
            }
            case "dir": {
                File f = t.size() > 1 ? file(rest) : file(".");
                if (!f.isDirectory()) { print("File Not Found\n"); return; }
                StringBuilder sb = new StringBuilder("\n Directory of " + d.vfs.toWin(f) + "\n\n");
                File[] kids = f.listFiles();
                if (kids != null) {
                    Arrays.sort(kids);
                    SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.US);
                    for (File k : kids) {
                        sb.append(df.format(new Date(k.lastModified()))).append(k.isDirectory() ? "    <DIR>          " : String.format(Locale.US, " %14d ", k.length())).append(k.getName()).append('\n');
                    }
                }
                print(sb.toString() + "\n");
                return;
            }
            case "type": {
                File f = file(rest);
                if (!f.isFile()) { print("The system cannot find the file specified.\n"); return; }
                print(Vfs.readText(f) + "\n");
                return;
            }
            case "mkdir": case "md": print(file(rest).mkdirs() ? "" : "Cannot create directory.\n"); return;
            case "del": case "rmdir": case "rd": {
                File f = file(rest);
                if (!f.exists()) { print("Could not find " + rest + "\n"); return; }
                print(f.delete() ? "" : "Access denied or folder not empty.\n");
                return;
            }
            case "ren": case "rename": {
                if (t.size() < 3) { print("Usage: REN old new\n"); return; }
                File f = file(t.get(1));
                print(f.renameTo(new File(f.getParentFile(), t.get(2))) ? "" : "Rename failed.\n");
                return;
            }
            case "copy": {
                if (t.size() < 3) { print("Usage: COPY src dst\n"); return; }
                File s = file(t.get(1)), dst = file(t.get(2));
                if (dst.isDirectory()) dst = new File(dst, s.getName());
                try {
                    java.nio.file.Files.copy(s.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    print("        1 file(s) copied.\n");
                } catch (Exception e) { print("Copy failed: " + e.getMessage() + "\n"); }
                return;
            }
            case "curl": {
                if (t.size() < 2) { print("Usage: CURL http://host/path\n"); return; }
                final String url = t.get(1).contains("://") ? t.get(1) : "http://" + t.get(1);
                print("GET " + url + " ...\n");
                new Thread(new Runnable() { @Override public void run() {
                    try { print(Net.fetch(d.act, url, 8000) + "\n"); } catch (Exception e) { print("curl: " + e + "\n"); }
                } }).start();
                return;
            }
            case "start": {
                if (t.size() < 2) { print("Usage: START app [file]\n"); return; }
                String app = t.get(1).toLowerCase(Locale.US);
                String name = app.startsWith("note") ? "Notepad" : app.startsWith("brow") ? "Browser" : app.startsWith("file") || app.startsWith("expl") ? "Files"
                        : app.startsWith("calc") ? "Calculator" : app.startsWith("sett") ? "Settings" : app.startsWith("term") || app.equals("cmd") ? "Terminal" : null;
                if (name == null) { print("Unknown app: " + t.get(1) + "\n"); return; }
                d.open(name, t.size() > 2 ? abs(t.get(2)) : null);
                return;
            }
            default: runProgram(t.get(0), rest);
        }
    }

    private void runProgram(String name, String args) {
        File f = null;
        String[] dirs = {null, "C:\\Program Files\\Samples", "C:\\Windows\\System32"};
        outer:
        for (String dir : dirs) {
            for (String suffix : new String[]{"", ".exe"}) {
                File c = dir == null ? file(name + suffix) : d.vfs.resolve(dir + "\\" + name + suffix);
                if (c.isFile()) { f = c; break outer; }
            }
        }
        if (f == null) { print("'" + name + "' is not recognized as an internal or external command,\noperable program or batch file.\n"); return; }
        final File exe = f;
        final String a = args;
        final byte[] data;
        try { data = Vfs.readAll(exe); } catch (java.io.IOException e) { print("Cannot read file.\n"); return; }
        stdin.clear();
        Net.applyToWinSock(d.act);
        if (com.unixroot.win10.clr.Metadata.isManaged(data)) { runManaged(exe, data, a); return; }
        final Emulator em = new Emulator(console, d.vfs.root);
        em.setCwd(cwd);
        em.setGui(new Win32Gui(host));
        em.setExeDir(exe.getParentFile());
        try {
            em.load(data, d.vfs.toWin(exe), a);
        } catch (RuntimeException e) {
            print(e.getMessage() + "\n");
            return;
        }
        emu = em;
        proc = new Thread(new Runnable() { @Override public void run() {
            int code = em.run();
            print("\n[Process exited with code " + code + "]\n");
            proc = null;
            emu = null;
            out.post(new Runnable() { @Override public void run() { updatePrompt(); } });
        } }, "x86-process");
        proc.start();
        updatePrompt();
    }

    private volatile com.unixroot.win10.clr.Vm clr;

    private void runManaged(final File exe, final byte[] data, final String args) {
        final com.unixroot.win10.clr.Vm vm = new com.unixroot.win10.clr.Vm(exe.getParentFile(), new com.unixroot.win10.clr.Vm.Console() {
            @Override public void write(String s) { print(s); }
            @Override public int read() { return console.read(); }
            @Override public void messageBox(String t, String x) { console.messageBox(t, x); }
        });
        vm.fsRoot = d.vfs.root;
        vm.guiHost = host;
        com.unixroot.win10.clr.WinForms.install(vm, host);
        vm.cwd = cwd;
        try {
            vm.main = vm.load(data, exe.getName().replaceAll("\\.[^.]*$", ""));
        } catch (RuntimeException e) { print(".NET load error: " + e.getMessage() + "\n"); return; }
        for (int i = 1; i <= vm.main.md.count(com.unixroot.win10.clr.Metadata.ASSEMBLYREF); i++) {
            String ar = vm.main.md.string(vm.main.md.row(com.unixroot.win10.clr.Metadata.ASSEMBLYREF, i)[6]);
            if (ar.equals("PresentationFramework") || ar.equals("PresentationCore")) {
                print("This is a WPF application (uses " + ar + ").\nWPF (XAML/BAML) is not supported by this emulator; only console and WinForms .NET programs run.\n");
                return;
            }
        }
        clr = vm;
        proc = new Thread(null, new Runnable() { @Override public void run() {
            int code;
            try { code = vm.runMain(args.isEmpty() ? new String[0] : args.split(" ")); }
            catch (Throwable t) { print("\n[.NET runtime error] " + t + "\n"); code = -1; }
            print("\n[Process exited with code " + code + "]\n");
            proc = null; clr = null;
            out.post(new Runnable() { @Override public void run() { updatePrompt(); } });
        } }, "clr-process", 24L * 1024 * 1024);
        proc.start();
        updatePrompt();
    }

    private void stopProcess() {
        Emulator em = emu;
        com.unixroot.win10.clr.Vm cv = clr;
        if (cv != null) cv.stopped = true;
        Thread t = proc;
        if (em != null) em.stop();
        if (t != null) t.interrupt();
    }

    private final Emulator.Console console = new Emulator.Console() {
        @Override public void write(String s) { print(s); }
        @Override public int read() {
            try { return stdin.take(); } catch (InterruptedException e) { return -1; }
        }
        @Override public void messageBox(final String title, final String text) {
            final CountDownLatch latch = new CountDownLatch(1);
            d.act.runOnUiThread(new Runnable() { @Override public void run() {
                AlertDialog dlg = new AlertDialog.Builder(d.act).setTitle(title).setMessage(text).setPositiveButton("OK", null).create();
                dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(android.content.DialogInterface x) { latch.countDown(); } });
                dlg.show();
            } });
            try { latch.await(); } catch (InterruptedException ignored) { }
        }
    };

    private final GuiHost host = new GuiHost() {
        @Override public HostWindow createWindow(final GuiHost.Input g, final int hwnd, final String title, final int cw, final int ch) {
            final NativeWindow[] nw = new NativeWindow[1];
            final CountDownLatch latch = new CountDownLatch(1);
            d.act.runOnUiThread(new Runnable() { @Override public void run() {
                nw[0] = new NativeWindow(d, g, hwnd, cw, ch);
                float dens = d.act.getResources().getDisplayMetrics().density;
                nw[0].frame = d.openCustom(title, "\uD83E\uDE9F", nw[0], (int) (cw / dens * 1.0f) + 4, (int) (ch / dens) + 90);
                latch.countDown();
            } });
            try { latch.await(); } catch (InterruptedException ignored) { }
            final NativeWindow w = nw[0];
            return new HostWindow() {
                @Override public Surface surface() { return w.surface; }
                @Override public void setTitle(final String t) { d.act.runOnUiThread(new Runnable() { @Override public void run() { if (w.frame != null) w.frame.setTitle(t); } }); }
                @Override public void setVisible(final boolean v) { d.act.runOnUiThread(new Runnable() { @Override public void run() { if (w.frame != null) w.frame.setVisibility(v ? android.view.View.VISIBLE : android.view.View.GONE); } }); }
                @Override public void close() { d.act.runOnUiThread(new Runnable() { @Override public void run() { w.guestClosed(); } }); }
            };
        }
        @Override public Surface createSurface(int w, int h) { return new NativeWindow.BmpSurface(w, h); }
        @Override public void messageBox(String title, String text) { console.messageBox(title, text); }
    };

    @Override public void onClose() { stopProcess(); }
}
