package com.unixroot.win10;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** The Windows 10 style desktop: wallpaper, icons, windows, start menu and taskbar. */
final class Desktop extends FrameLayout {

    interface AppFactory { View create(Desktop d, WinFrame w, String arg); }
    /** Implemented by app views that need to clean up when their window closes. */
    interface Closeable { void onClose(); }
    /** Implemented by app views that show files and want to know when the file system changed. */
    interface FsListener { void onFsChanged(); }

    static final class AppInfo {
        final String name, glyph;
        final int color, w, h;
        final AppFactory factory;
        AppInfo(String name, String glyph, int color, int w, int h, AppFactory f) {
            this.name = name; this.glyph = glyph; this.color = color; this.w = w; this.h = h; this.factory = f;
        }
    }

    private static final int[][] WALLPAPERS = {
            {0xFF0A2A5E, 0xFF1E6FD0}, {0xFF1B1B3A, 0xFF6A3093}, {0xFF0F3D2E, 0xFF2E9C6A}, {0xFF2B2B2B, 0xFF6B6B6B}};

    final MainActivity act;
    final Vfs vfs;
    private final FrameLayout iconLayer, windowLayer;
    private final LinearLayout taskButtons;
    private final TextView clock;
    private final View startMenu;
    private final int taskbarH;
    private final ArrayList<WinFrame> windows = new ArrayList<>();
    private final ArrayList<AppInfo> apps = new ArrayList<>();
    private WinFrame active;
    private int cascade;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm\ndd/MM/yyyy", Locale.US);

    Desktop(MainActivity a, Vfs vfs) {
        super(a);
        this.act = a;
        this.vfs = vfs;
        taskbarH = Ui.dp(a, 44);
        registerApps();
        setWallpaper(Net.prefs(a).getInt("wallpaper", 0));

        iconLayer = new FrameLayout(a);
        addView(iconLayer, new LayoutParams(Ui.MATCH, Ui.MATCH));
        iconLayer.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) hideStart();
                return false;
            }
        });
        buildIcons();

        windowLayer = new FrameLayout(a);
        LayoutParams wl = new LayoutParams(Ui.MATCH, Ui.MATCH);
        wl.bottomMargin = taskbarH;
        addView(windowLayer, wl);

        startMenu = buildStartMenu();
        LayoutParams sl = new LayoutParams(Ui.dp(a, 300), Ui.dp(a, 300), Gravity.BOTTOM | Gravity.START);
        sl.bottomMargin = taskbarH;
        startMenu.setVisibility(GONE);
        addView(startMenu, sl);

        LinearLayout taskbar = new LinearLayout(a);
        taskbar.setOrientation(LinearLayout.HORIZONTAL);
        taskbar.setBackgroundColor(0xF0101018);
        taskbar.setGravity(Gravity.CENTER_VERTICAL);
        addView(taskbar, new LayoutParams(Ui.MATCH, taskbarH, Gravity.BOTTOM));

        View start = new View(a) {
            private final Paint p = new Paint();
            @Override protected void onDraw(Canvas c) {
                p.setColor(Color.WHITE);
                float s = Math.min(getWidth(), getHeight()) * 0.42f, cx = getWidth() / 2f, cy = getHeight() / 2f, g = s * 0.06f;
                c.drawRect(cx - s / 2, cy - s / 2, cx - g, cy - g, p);
                c.drawRect(cx + g, cy - s / 2, cx + s / 2, cy - g, p);
                c.drawRect(cx - s / 2, cy + g, cx - g, cy + s / 2, p);
                c.drawRect(cx + g, cy + g, cx + s / 2, cy + s / 2, p);
            }
        };
        start.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { toggleStart(); }
        });
        taskbar.addView(start, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.MATCH));

        HorizontalScrollHolder holder = new HorizontalScrollHolder(a);
        taskButtons = holder.row;
        taskbar.addView(holder, Ui.lp(0, Ui.MATCH, 1));

        clock = Ui.label(a, "", 11, Color.WHITE);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        taskbar.addView(clock, new LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
        tick();
    }

    /** Horizontally scrolling row for the taskbar buttons. */
    private static final class HorizontalScrollHolder extends android.widget.HorizontalScrollView {
        final LinearLayout row;
        HorizontalScrollHolder(android.content.Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            addView(row, new FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
        }
    }

    private void tick() {
        clock.setText(fmt.format(new Date()));
        handler.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 20000);
    }

    void setWallpaper(int idx) {
        int[] c = WALLPAPERS[Math.abs(idx) % WALLPAPERS.length];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        setBackground(g);
        Net.prefs(act).edit().putInt("wallpaper", idx).apply();
    }

    static String shorten(String t) { return t.length() > 14 ? t.substring(0, 13) + "…" : t; }

    // ---------------- apps ----------------
    private void registerApps() {
        apps.add(new AppInfo("Terminal", "🖥", 0xFF2D2D2D, 520, 300, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new TerminalApp(d, w, arg); } }));
        apps.add(new AppInfo("Browser", "🌐", 0xFF0078D7, 640, 330, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new BrowserApp(d, w, arg); } }));
        apps.add(new AppInfo("Files", "📁", 0xFFE8A200, 480, 300, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new FilesApp(d, w, arg); } }));
        apps.add(new AppInfo("Notepad", "📝", 0xFF5C6BC0, 460, 300, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new NotepadApp(d, w, arg); } }));
        apps.add(new AppInfo("Calculator", "🧮", 0xFF4A4A4A, 280, 340, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new CalcApp(d); } }));
        apps.add(new AppInfo("Settings", "⚙", 0xFF3A7D44, 460, 330, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new SettingsApp(d); } }));
        apps.add(new AppInfo("About", "ℹ", 0xFF7B1FA2, 460, 320, new AppFactory() {
            @Override public View create(Desktop d, WinFrame w, String arg) { return new AboutApp(d); } }));
    }

    private void buildIcons() {
        int cell = Ui.dp(act, 84);
        int perCol = 3;
        for (int i = 0; i < apps.size(); i++) {
            final AppInfo ai = apps.get(i);
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView g = Ui.label(act, ai.glyph, 24, Color.WHITE);
            g.setGravity(Gravity.CENTER);
            g.setBackground(Ui.rect(act, ai.color, 8));
            item.addView(g, new LinearLayout.LayoutParams(Ui.dp(act, 46), Ui.dp(act, 46)));
            TextView t = Ui.label(act, ai.name, 11, Color.WHITE);
            t.setShadowLayer(3, 1, 1, Color.BLACK);
            t.setGravity(Gravity.CENTER);
            item.addView(t, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            item.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { hideStart(); open(ai.name, null); }
            });
            LayoutParams lp = new LayoutParams(cell, cell);
            lp.leftMargin = Ui.dp(act, 6) + (i / perCol) * cell;
            lp.topMargin = Ui.dp(act, 6) + (i % perCol) * cell;
            iconLayer.addView(item, lp);
        }
    }

    private View buildStartMenu() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF2181820);
        panel.setPadding(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6));
        TextView head = Ui.label(act, "Win10 Emulator", 12, 0xFFAAAAAA);
        head.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), 0, Ui.dp(act, 6));
        panel.addView(head);
        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        panel.addView(sv, Ui.lp(Ui.MATCH, 0, 1));
        for (final AppInfo ai : apps) list.addView(menuRow(ai.glyph, ai.name, new OnClickListener() {
            @Override public void onClick(View v) { hideStart(); open(ai.name, null); }
        }));
        panel.addView(menuRow("⏻", "Exit", new OnClickListener() {
            @Override public void onClick(View v) { act.finish(); }
        }));
        return panel;
    }

    private View menuRow(String glyph, String text, OnClickListener l) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));
        TextView g = Ui.label(act, glyph, 18, Color.WHITE);
        g.setGravity(Gravity.CENTER);
        row.addView(g, new LinearLayout.LayoutParams(Ui.dp(act, 34), Ui.WRAP));
        row.addView(Ui.label(act, text, 14, Color.WHITE));
        row.setOnClickListener(l);
        return row;
    }

    void toggleStart() { startMenu.setVisibility(startMenu.getVisibility() == VISIBLE ? GONE : VISIBLE); }
    void hideStart() { startMenu.setVisibility(GONE); }
    boolean startVisible() { return startMenu.getVisibility() == VISIBLE; }

    // ---------------- windows ----------------
    WinFrame open(String appName, String arg) {
        AppInfo info = null;
        for (AppInfo a : apps) if (a.name.equals(appName)) info = a;
        if (info == null) return null;
        WinFrame w = new WinFrame(this, info.name, info.glyph);
        w.setContent(info.factory.create(this, w, arg));

        int sw = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int sh = (getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels) - taskbarH;
        int ww = Math.min(Ui.dp(act, info.w), sw - Ui.dp(act, 10));
        int wh = Math.min(Ui.dp(act, info.h), sh - Ui.dp(act, 6));
        LayoutParams lp = new LayoutParams(ww, wh, Gravity.TOP | Gravity.START);
        int off = (cascade++ % 6) * Ui.dp(act, 26);
        lp.leftMargin = Math.max(0, Math.min(sw - ww, Ui.dp(act, 110) + off));
        lp.topMargin = Math.max(0, Math.min(sh - wh, Ui.dp(act, 8) + off));
        windowLayer.addView(w, lp);
        windows.add(w);

        final WinFrame fw = w;
        TextView tb = Ui.label(act, info.glyph + " " + shorten(info.name), 12, Color.WHITE);
        tb.setGravity(Gravity.CENTER);
        tb.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0);
        tb.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (fw == active && fw.getVisibility() == VISIBLE) minimize(fw);
                else { fw.setVisibility(VISIBLE); focus(fw); }
            }
        });
        w.taskBtn = tb;
        taskButtons.addView(tb, new LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
        focus(w);
        return w;
    }

    /** Opens a window around arbitrary content (used for emulated native Win32 windows). */
    WinFrame openCustom(String title, String glyph, View content, int wDp, int hDp) {
        WinFrame w = new WinFrame(this, title, glyph);
        w.setContent(content);
        int sw = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int sh = (getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels) - taskbarH;
        int ww = Math.min(Ui.dp(act, wDp), sw - Ui.dp(act, 10));
        int wh = Math.min(Ui.dp(act, hDp), sh - Ui.dp(act, 6));
        LayoutParams lp = new LayoutParams(ww, wh, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.max(0, (sw - ww) / 2);
        lp.topMargin = Math.max(0, Math.min(sh - wh, Ui.dp(act, 6)));
        windowLayer.addView(w, lp);
        windows.add(w);
        final WinFrame fw = w;
        TextView tb = Ui.label(act, glyph + " " + shorten(title), 12, Color.WHITE);
        tb.setGravity(Gravity.CENTER);
        tb.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0);
        tb.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (fw == active && fw.getVisibility() == VISIBLE) minimize(fw);
                else { fw.setVisibility(VISIBLE); focus(fw); }
            }
        });
        w.taskBtn = tb;
        taskButtons.addView(tb, new LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
        focus(w);
        return w;
    }

    void focus(WinFrame w) {
        hideStart();
        if (w == active) return;
        active = w;
        w.bringToFront();
        refreshTasks();
    }

    void minimize(WinFrame w) {
        w.setVisibility(GONE);
        if (active == w) active = null;
        refreshTasks();
    }

    void close(WinFrame w) {
        if (w.content instanceof Closeable) ((Closeable) w.content).onClose();
        windowLayer.removeView(w);
        taskButtons.removeView(w.taskBtn);
        windows.remove(w);
        if (active == w) active = null;
        refreshTasks();
    }

    private void refreshTasks() {
        for (WinFrame w : windows) {
            boolean on = w == active && w.getVisibility() == VISIBLE;
            w.taskBtn.setBackgroundColor(on ? 0x55FFFFFF : 0x22FFFFFF);
        }
    }

    boolean handleBack() {
        if (startVisible()) { hideStart(); return true; }
        if (active != null) { close(active); return true; }
        return false;
    }

    void fsChanged() {
        for (WinFrame w : windows) if (w.content instanceof FsListener) ((FsListener) w.content).onFsChanged();
    }
}
