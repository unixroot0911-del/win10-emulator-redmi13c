package com.unixroot.win10.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/** user32 / gdi32 / winmm subset: windows, message loop, timers, GDI drawing and a few built-in controls. */
public final class Win32Gui extends Gui implements GuiHost.Input {
    // messages
    static final int WM_CREATE = 1, WM_DESTROY = 2, WM_MOVE = 3, WM_SIZE = 5, WM_SETFOCUS = 7, WM_KILLFOCUS = 8, WM_SETTEXT = 0xC, WM_GETTEXT = 0xD,
            WM_GETTEXTLENGTH = 0xE, WM_PAINT = 0xF, WM_CLOSE = 0x10, WM_QUIT = 0x12, WM_ERASEBKGND = 0x14, WM_SHOWWINDOW = 0x18, WM_SETCURSOR = 0x20,
            WM_SETFONT = 0x30, WM_NCCREATE = 0x81, WM_NCDESTROY = 0x82, WM_NCHITTEST = 0x84, WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_CHAR = 0x102,
            WM_COMMAND = 0x111, WM_SYSCOMMAND = 0x112, WM_TIMER = 0x113, WM_MOUSEMOVE = 0x200, WM_LBUTTONDOWN = 0x201, WM_LBUTTONUP = 0x202,
            WM_RBUTTONDOWN = 0x204, WM_RBUTTONUP = 0x205, BM_GETCHECK = 0xF0, BM_SETCHECK = 0xF1, BM_CLICK = 0xF5;
    static final int WS_CHILD = 0x40000000, WS_VISIBLE = 0x10000000, WS_POPUP = 0x80000000, WS_CAPTION = 0x00C00000, WS_DISABLED = 0x08000000;
    static final int FRAME_X = 8, FRAME_TOP = 31, FRAME_BOTTOM = 8;

    private static final class WndClass { String name; int proc, style, brush, extra; }

    private final class Wnd {
        int hwnd, parent, style, exStyle, x, y, w, h, cw, ch, proc, userData, id, ctl; // ctl: 0 app class, 1 button, 2 static, 3 edit
        WndClass cls;
        String title = "";
        boolean visible, enabled = true, invalid, pressed, checked;
        boolean top;
        GuiHost.HostWindow host;
        int[] extra = new int[0];
        StringBuilder text = new StringBuilder();
        int caret;
        final ArrayList<Wnd> kids = new ArrayList<>();
    }

    private static final class Msg { int hwnd, msg, w, l, x, y, ch; long time; }
    private static final class Timer { int hwnd, id, proc; long interval, due; }
    private static final class Pen { int rgb, width, style; }
    private static final class Brush { int rgb; boolean hollow; }
    private static final class Font { int height = 13; boolean bold, italic; }
    private static final class Bmp { Surface surf; int w, h, bpp; int bitsPtr, stride; boolean top; int[] palette; }
    private static final class DC {
        Wnd wnd; Surface surf; int ox, oy;
        int pen = 0x30000000 + 7, brush = 0x30000000, font = 0x30000000 + 17, bitmap;
        int textColor = 0, bkColor = 0xFFFFFF, bkMode = 2, curX, curY, align;
        Bmp dib;
        int[] saved;
    }

    private final GuiHost host;
    private final Map<String, WndClass> classes = new HashMap<>();
    private final Map<Integer, Wnd> wins = new LinkedHashMap<>();
    private final Map<Integer, Object> objs = new HashMap<>();
    private final Map<Integer, DC> dcs = new HashMap<>();
    private final Map<Integer, Timer> timers = new HashMap<>();
    private final LinkedBlockingDeque<Msg> queue = new LinkedBlockingDeque<>();
    private int nextHwnd = 0x00010000, nextObj = 0x02000010, nextDc = 0x01000010, nextTimer = 1, cascade;
    private boolean quit;
    private int quitCode, focus, capture, lastKeyChar, activeWnd;
    private long startNs = System.nanoTime();
    private Surface dummy;
    private Emulator emu;
    private final boolean[] keys = new boolean[256];
    private int mouseX, mouseY;

    private static final String SPEC =
            "RegisterClassA:1s,RegisterClassW:1s,RegisterClassExA:1s,RegisterClassExW:1s,UnregisterClassA:2s,CreateWindowExA:12s,CreateWindowExW:12s,"
          + "DestroyWindow:1s,ShowWindow:2s,UpdateWindow:1s,GetMessageA:4s,GetMessageW:4s,PeekMessageA:5s,PeekMessageW:5s,TranslateMessage:1s,"
          + "DispatchMessageA:1s,DispatchMessageW:1s,PostMessageA:4s,PostMessageW:4s,SendMessageA:4s,SendMessageW:4s,PostQuitMessage:1s,"
          + "DefWindowProcA:4s,DefWindowProcW:4s,CallWindowProcA:5s,CallWindowProcW:5s,GetClientRect:2s,GetWindowRect:2s,SetWindowTextA:2s,"
          + "SetWindowTextW:2s,GetWindowTextA:3s,GetWindowTextW:3s,GetWindowTextLengthA:1s,MoveWindow:6s,SetWindowPos:7s,InvalidateRect:3s,"
          + "ValidateRect:2s,BeginPaint:2s,EndPaint:2s,GetDC:1s,GetWindowDC:1s,ReleaseDC:2s,GetDesktopWindow:0s,SetTimer:4s,KillTimer:2s,"
          + "LoadCursorA:2s,LoadCursorW:2s,LoadIconA:2s,LoadIconW:2s,SetCursor:1s,GetSystemMetrics:1s,GetKeyState:1s,GetAsyncKeyState:1s,"
          + "SetFocus:1s,GetFocus:0s,SetCapture:1s,ReleaseCapture:0s,GetCapture:0s,GetCursorPos:1s,SetCursorPos:2s,ScreenToClient:2s,"
          + "ClientToScreen:2s,DrawTextA:5s,DrawTextW:5s,FillRect:3s,FrameRect:3s,GetSysColor:1s,GetSysColorBrush:1s,AdjustWindowRect:3s,"
          + "AdjustWindowRectEx:4s,SetWindowLongA:3s,GetWindowLongA:2s,SetWindowLongW:3s,GetWindowLongW:2s,GetParent:1s,IsWindow:1s,"
          + "IsWindowVisible:1s,EnableWindow:2s,IsWindowEnabled:1s,SetForegroundWindow:1s,GetActiveWindow:0s,GetForegroundWindow:0s,"
          + "SetActiveWindow:1s,PtInRect:3s,SetRect:5s,OffsetRect:3s,InflateRect:3s,IntersectRect:3s,EqualRect:2s,CopyRect:2s,IsRectEmpty:1s,"
          + "wsprintfA:0c,CharUpperA:1s,CharLowerA:1s,GetClassNameA:3s,GetDlgItem:2s,MessageBeep:1s,RedrawWindow:4s,GetUpdateRect:3s,"
          + "GetWindowThreadProcessId:2s,IsIconic:1s,IsZoomed:1s,CreateMenu:0s,CreatePopupMenu:0s,SetMenu:2s,AppendMenuA:4s,DestroyMenu:1s,"
          + "DrawMenuBar:1s,TranslateAcceleratorA:3s,MapVirtualKeyA:2s,ShowCursor:1s,ClipCursor:1s,SystemParametersInfoA:4s,GetMessageTime:0s,"
          + "GetMessagePos:0s,GetWindow:2s,InvertRect:2s,SetDlgItemTextA:3s,GetDlgItemTextA:4s,EnumWindows:2s,SetWindowsHookExA:4s,UnhookWindowsHookEx:1s,"
          + "GetStockObject:1s,CreateSolidBrush:1s,CreatePen:3s,CreateFontA:14s,CreateFontW:14s,CreateFontIndirectA:1s,SelectObject:2s,DeleteObject:1s,"
          + "DeleteDC:1s,SetBkColor:2s,SetBkMode:2s,SetTextColor:2s,GetTextColor:1s,TextOutA:5s,TextOutW:5s,ExtTextOutA:8s,ExtTextOutW:8s,MoveToEx:4s,"
          + "LineTo:3s,Rectangle:5s,RoundRect:7s,Ellipse:5s,SetPixel:4s,SetPixelV:4s,GetPixel:3s,Polygon:3s,Polyline:3s,CreateCompatibleDC:1s,"
          + "CreateCompatibleBitmap:3s,BitBlt:9s,StretchBlt:11s,PatBlt:6s,GetTextExtentPoint32A:4s,GetTextExtentPoint32W:4s,GetTextExtentPointA:4s,"
          + "GetTextMetricsA:2s,GetDeviceCaps:2s,SetROP2:2s,SaveDC:1s,RestoreDC:2s,StretchDIBits:13s,SetDIBitsToDevice:12s,CreateDIBSection:6s,"
          + "GetObjectA:3s,GdiFlush:0s,SetStretchBltMode:2s,SetTextAlign:2s,SetMapMode:2s,SelectClipRgn:2s,IntersectClipRect:5s,"
          + "timeGetTime:0s,timeBeginPeriod:1s,timeEndPeriod:1s,PlaySoundA:3s,InitCommonControls:0s,InitCommonControlsEx:1s,ShellExecuteA:6s,"
          + "RegOpenKeyExA:5s,RegCloseKey:1s,RegQueryValueExA:6s,RegCreateKeyExA:9s,RegSetValueExA:6s,CoInitialize:1s,CoInitializeEx:2s,CoUninitialize:0s,"
          + "GetModuleHandleA:1s";

    public Win32Gui(GuiHost host) {
        this.host = host;
        Win32Table.addExtra(SPEC);
    }

    @Override public Integer nargs(String name) { return Win32Table.nargs(name); }
    @Override public boolean isStdcall(String name) { return Win32Table.isStdcall(name); }

    @Override
    public void onExit() {
        ArrayList<Wnd> tops = new ArrayList<>();
        synchronized (this) { for (Wnd w : wins.values()) if (w.top && w.host != null) tops.add(w); }
        for (Wnd w : tops) w.host.close();
    }

    // ================= host -> emulator events =================
    private static int lp(int x, int y) { return (y << 16) | (x & 0xFFFF); }

    private Msg msg(int hwnd, int m, int w, int l) {
        Msg q = new Msg();
        q.hwnd = hwnd; q.msg = m; q.w = w; q.l = l; q.time = (System.nanoTime() - startNs) / 1000000L; q.x = mouseX; q.y = mouseY;
        return q;
    }

    private int absX(Wnd w) { int x = 0; for (Wnd c = w; c != null && !c.top; c = wins.get(c.parent)) x += c.x; return x; }
    private int absY(Wnd w) { int y = 0; for (Wnd c = w; c != null && !c.top; c = wins.get(c.parent)) y += c.y; return y; }
    private Wnd topOf(Wnd w) { while (w != null && !w.top) w = wins.get(w.parent); return w; }

    private Wnd hit(Wnd w, int x, int y) {
        for (int i = w.kids.size() - 1; i >= 0; i--) {
            Wnd k = w.kids.get(i);
            if (k.visible && x >= k.x && y >= k.y && x < k.x + k.w && y < k.y + k.h) return hit(k, x - k.x, y - k.y);
        }
        return w;
    }

    /** Mouse event in client coordinates of top-level window hwnd (msg = WM_xBUTTONx or WM_MOUSEMOVE). */
    public synchronized void postMouse(int hwnd, int m, int x, int y) {
        Wnd top = wins.get(hwnd);
        if (top == null) return;
        mouseX = x; mouseY = y;
        Wnd target = capture != 0 && wins.get(capture) != null ? wins.get(capture) : hit(top, x, y);
        int rx = x - absX(target), ry = y - absY(target);
        if (m == WM_LBUTTONDOWN) { keys[1] = true; setFocusInternal(target); }
        else if (m == WM_LBUTTONUP) keys[1] = false;
        else if (m == WM_RBUTTONDOWN) keys[2] = true;
        else if (m == WM_RBUTTONUP) keys[2] = false;
        int keysState = (keys[1] ? 1 : 0) | (keys[2] ? 2 : 0);
        queue.add(msg(target.hwnd, m, keysState, lp(rx, ry)));
    }

    /** Keyboard event: vk is a Windows virtual-key code, ch the produced character (0 if none). */
    public synchronized void postKey(int vk, boolean down, int ch) {
        int target = focus != 0 && wins.containsKey(focus) ? focus : activeWnd;
        if (target == 0 || !wins.containsKey(target)) return;
        if (vk > 0 && vk < 256) keys[vk] = down;
        Msg m = msg(target, down ? WM_KEYDOWN : WM_KEYUP, vk, down ? 1 : (1 | 0xC0000000));
        m.ch = down ? ch : 0;
        queue.add(m);
    }

    /** The user closed a window through the host's title bar. */
    public synchronized void windowClosed(int hwnd) {
        if (wins.containsKey(hwnd)) queue.add(msg(hwnd, WM_CLOSE, 0, 0));
    }

    public synchronized void windowActivated(int hwnd) { if (wins.containsKey(hwnd)) activeWnd = hwnd; }

    // ================= helpers =================
    private static int rgbOf(int colorref) { return ((colorref & 0xFF) << 16) | (colorref & 0xFF00) | ((colorref >> 16) & 0xFF); }

    private static int sysColor(int i) {
        switch (i) {
            case 0: return 0xC8C8C8; case 1: return 0x000000; case 2: return 0x0078D7; case 3: return 0xFFFFFF; case 4: return 0xF0F0F0;
            case 5: return 0xFFFFFF; case 6: return 0x646464; case 7: return 0x000000; case 8: return 0x000000; case 9: return 0x000000;
            case 10: return 0xB4B4B4; case 11: return 0xF4F7FC; case 12: return 0xABABAB; case 13: return 0x3399FF; case 14: return 0xFFFFFF;
            case 15: return 0xF0F0F0; case 16: return 0xA0A0A0; case 17: return 0x6D6D6D; case 18: return 0x000000; case 20: return 0xFFFFFF;
            default: return 0xF0F0F0;
        }
    }

    private int newObj(Object o) { int h = nextObj; nextObj += 4; objs.put(h, o); return h; }

    private Object obj(int h) {
        if (h >= 0x30000000 && h < 0x30000100) {
            int n = h - 0x30000000;
            if (n <= 5) { Brush b = new Brush(); b.hollow = n == 5; b.rgb = n == 0 ? 0xFFFFFF : n == 1 ? 0xC0C0C0 : n == 2 ? 0x808080 : n == 3 ? 0x404040 : 0; return b; }
            if (n >= 6 && n <= 8) { Pen p = new Pen(); p.rgb = n == 6 ? 0xFFFFFF : 0; p.width = 1; p.style = n == 8 ? 5 : 0; return p; }
            Font f = new Font(); f.height = (n == 10 || n == 11 || n == 16) ? 13 : 13; return f;
        }
        return objs.get(h);
    }

    private Brush brushOf(int h) {
        if (h >= 1 && h <= 32) { Brush b = new Brush(); b.rgb = sysColor(h - 1); return b; }
        Object o = obj(h);
        return o instanceof Brush ? (Brush) o : null;
    }

    private Surface dummySurface() { if (dummy == null) dummy = host.createSurface(64, 64); return dummy; }

    private DC dcOf(int h) { return dcs.get(h); }

    private DC newDc(Wnd w) {
        DC d = new DC();
        d.wnd = w;
        if (w != null) { Wnd t = topOf(w); d.surf = t != null && t.host != null ? t.host.surface() : dummySurface(); d.ox = absX(w); d.oy = absY(w); }
        else d.surf = dummySurface();
        return d;
    }

    private int regDc(DC d) { int h = nextDc; nextDc += 4; dcs.put(h, d); return h; }

    private String str(Emulator e, int p, boolean wide) { return p == 0 ? "" : wide ? e.wstr(p) : e.mem.cstr(p); }

    private void writeRect(Emulator e, int p, int l, int t, int r, int b) { e.mem.w32(p, l); e.mem.w32(p + 4, t); e.mem.w32(p + 8, r); e.mem.w32(p + 12, b); }

    private void flush(DC d) { if (d != null && d.surf != null) d.surf.flush(); }

    // ================= window management =================
    private void invalidate(Wnd w) {
        w.invalid = true;
        for (Wnd k : w.kids) invalidate(k);
    }

    private int send(Emulator e, Wnd w, int m, int wp, int lpar) {
        if (w.ctl != 0) return controlProc(e, w, m, wp, lpar);
        int proc = w.proc != 0 ? w.proc : w.cls != null ? w.cls.proc : 0;
        if (proc == 0) return defProc(e, w, m, wp, lpar);
        return e.callGuest(proc, w.hwnd, m, wp, lpar);
    }

    private int defProc(Emulator e, Wnd w, int m, int wp, int lpar) {
        switch (m) {
            case WM_CLOSE: destroyWindow(e, w); return 0;
            case WM_SYSCOMMAND: if ((wp & 0xFFF0) == 0xF060) destroyWindow(e, w); return 0;
            case WM_ERASEBKGND: {
                DC d = dcOf(wp);
                if (d != null) {
                    Brush b = w.cls != null ? brushOf(w.cls.brush) : null;
                    int c = b != null && !b.hollow ? b.rgb : sysColor(15);
                    if (w.cls != null && w.cls.brush == 0) c = 0xFFFFFF;
                    d.surf.fillRect(d.ox, d.oy, w.cw, w.ch, c);
                }
                return 1; }
            case WM_SETTEXT: setTitle(w, str(e, lpar, false)); return 1;
            case WM_GETTEXT: { String t = w.title; if (t.length() >= wp) t = t.substring(0, Math.max(0, wp - 1)); e.mem.putCstr(lpar, t); return t.length(); }
            case WM_GETTEXTLENGTH: return w.title.length();
            case WM_NCHITTEST: return 1;
            case WM_SETCURSOR: return 1;
            case WM_NCCREATE: return 1;
            default: return 0;
        }
    }

    private void setTitle(Wnd w, String t) {
        w.title = t;
        if (w.ctl != 0) { w.text.setLength(0); w.text.append(t); w.caret = t.length(); invalidate(w); }
        if (w.top && w.host != null) w.host.setTitle(t);
    }

    private void destroyWindow(Emulator e, Wnd w) {
        if (!wins.containsKey(w.hwnd)) return;
        for (Wnd k : new ArrayList<>(w.kids)) destroyWindow(e, k);
        send(e, w, WM_DESTROY, 0, 0);
        send(e, w, WM_NCDESTROY, 0, 0);
        synchronized (this) {
            wins.remove(w.hwnd);
            Wnd p = wins.get(w.parent);
            if (p != null) p.kids.remove(w);
            if (focus == w.hwnd) focus = 0;
            if (capture == w.hwnd) capture = 0;
            if (activeWnd == w.hwnd) activeWnd = 0;
            timers.values().removeIf(t -> t.hwnd == w.hwnd);
        }
        if (w.top && w.host != null) w.host.close();
        if (p0(w) != null) invalidate(p0(w));
    }

    private Wnd p0(Wnd w) { return wins.get(w.parent); }

    private void setFocusInternal(Wnd w) {
        if (w == null || focus == w.hwnd) return;
        focus = w.hwnd;
    }

    private int ctlTypeOf(String cls) {
        switch (cls.toLowerCase()) {
            case "button": return 1;
            case "static": return 2;
            case "edit": return 3;
            default: return 0;
        }
    }

    private int createWindow(Emulator e, boolean wide) {
        int exStyle = e.a(0), clsPtr = e.a(1), namePtr = e.a(2), style = e.a(3), x = e.a(4), y = e.a(5), w = e.a(6), h = e.a(7);
        int parent = e.a(8), menu = e.a(9), inst = e.a(10), param = e.a(11);
        String cn = (clsPtr >>> 16) == 0 ? "#" + clsPtr : str(e, clsPtr, wide);
        WndClass wc = classes.get(cn.toLowerCase());
        int ctl = wc == null ? ctlTypeOf(cn) : 0;
        if (wc == null && ctl == 0) { e.lastError = 1407; return 0; }
        Wnd wd = new Wnd();
        synchronized (this) { wd.hwnd = nextHwnd; nextHwnd += 4; }
        wd.cls = wc; wd.ctl = ctl; wd.style = style; wd.exStyle = exStyle; wd.parent = parent; wd.id = menu;
        wd.title = str(e, namePtr, wide);
        boolean child = (style & WS_CHILD) != 0 && wins.containsKey(parent);
        wd.top = !child;
        if (wd.ctl != 0) { wd.text.append(wd.title); wd.caret = wd.title.length(); }
        if (x == 0x80000000) { x = 40 + (cascade % 8) * 30; y = 30 + (cascade % 8) * 30; cascade++; }
        if (w == 0x80000000) w = 640;
        if (h == 0x80000000) h = 480;
        wd.x = x; wd.y = y; wd.w = w; wd.h = h;
        boolean frame = wd.top && (style & WS_CAPTION) == WS_CAPTION;
        wd.cw = frame ? Math.max(1, w - 2 * FRAME_X) : w;
        wd.ch = frame ? Math.max(1, h - FRAME_TOP - FRAME_BOTTOM) : h;
        if (wd.top) wd.host = host.createWindow(this, wd.hwnd, wd.title, wd.cw, wd.ch);
        synchronized (this) {
            wins.put(wd.hwnd, wd);
            if (child) wins.get(parent).kids.add(wd);
            if (wd.top) activeWnd = wd.hwnd;
        }
        if (wd.top && wd.host != null) { Surface s = wd.host.surface(); s.fillRect(0, 0, wd.cw, wd.ch, 0xFFFFFF); }
        int cs = e.allocZero(48);
        e.mem.w32(cs, param); e.mem.w32(cs + 4, inst); e.mem.w32(cs + 8, menu); e.mem.w32(cs + 12, parent); e.mem.w32(cs + 16, h); e.mem.w32(cs + 20, w);
        e.mem.w32(cs + 24, y); e.mem.w32(cs + 28, x); e.mem.w32(cs + 32, style); e.mem.w32(cs + 36, namePtr); e.mem.w32(cs + 40, clsPtr); e.mem.w32(cs + 44, exStyle);
        if (wc != null && wc.extra > 0) wd.extra = new int[(wc.extra + 3) / 4];
        if (send(e, wd, WM_NCCREATE, 0, cs) == 0 && wd.ctl == 0) { destroyWindow(e, wd); return 0; }
        if (send(e, wd, WM_CREATE, 0, cs) == -1) { destroyWindow(e, wd); return 0; }
        send(e, wd, WM_SIZE, 0, lp(wd.cw, wd.ch));
        send(e, wd, WM_MOVE, 0, lp(wd.x, wd.y));
        wd.visible = (style & WS_VISIBLE) != 0;
        if (wd.visible) { if (wd.top && wd.host != null) wd.host.setVisible(true); invalidate(wd); }
        return wd.hwnd;
    }

    private void showWindow(Emulator e, Wnd w, int cmd) {
        boolean vis = cmd != 0;
        boolean was = w.visible;
        w.visible = vis;
        if (w.top && w.host != null) w.host.setVisible(vis);
        if (vis && !was) { send(e, w, WM_SHOWWINDOW, 1, 0); invalidate(w); }
        if (vis) { Wnd t = topOf(w); if (t != null) activeWnd = t.hwnd; }
    }

    // ================= message loop =================
    private Msg generated(boolean remove) {
        long now = System.nanoTime() / 1000000L;
        for (Timer t : timers.values()) {
            if (t.due <= now) {
                if (remove) t.due = now + Math.max(1, t.interval);
                Msg m = msg(t.hwnd, WM_TIMER, t.id, t.proc);
                return m;
            }
        }
        for (Wnd w : new ArrayList<>(wins.values())) {
            if (w.invalid && w.visible && visibleChain(w)) {
                if (remove) w.invalid = false;
                return msg(w.hwnd, WM_PAINT, 0, 0);
            }
        }
        return null;
    }

    private boolean visibleChain(Wnd w) {
        for (Wnd c = w; c != null; c = wins.get(c.parent)) if (!c.visible) return false;
        return true;
    }

    private long nextTimerWaitMs() {
        long now = System.nanoTime() / 1000000L, best = 100;
        for (Timer t : timers.values()) best = Math.min(best, Math.max(1, t.due - now));
        return best;
    }

    private void fillMsg(Emulator e, int p, Msg m) {
        e.mem.w32(p, m.hwnd); e.mem.w32(p + 4, m.msg); e.mem.w32(p + 8, m.w); e.mem.w32(p + 12, m.l); e.mem.w32(p + 16, (int) m.time);
        e.mem.w32(p + 20, m.x); e.mem.w32(p + 24, m.y);
    }

    private int getMessage(Emulator e) {
        int p = e.a(0);
        while (!e.cpu.halted) {
            Msg m = queue.poll();
            if (m == null) m = generated(true);
            if (m != null) { fillMsg(e, p, m); if (m.msg == WM_KEYDOWN) lastKeyChar = m.ch; return 1; }
            if (quit) { fillMsg(e, p, msg(0, WM_QUIT, quitCode, 0)); return 0; }
            synchronized (this) { if (wins.isEmpty() && timers.isEmpty()) { fillMsg(e, p, msg(0, WM_QUIT, 0, 0)); return 0; } }
            try { m = queue.poll(nextTimerWaitMs(), TimeUnit.MILLISECONDS); } catch (InterruptedException ex) { e.cpu.halted = true; return 0; }
            if (m != null) { queue.addFirst(m); }
        }
        return 0;
    }

    private int peekMessage(Emulator e) {
        int p = e.a(0), flags = e.a(4);
        boolean remove = (flags & 1) != 0;
        Msg m = remove ? queue.poll() : queue.peek();
        if (m == null) m = generated(remove);
        if (m == null) {
            if (quit) { fillMsg(e, p, msg(0, WM_QUIT, quitCode, 0)); return 1; }
            if (e.cpu.halted) return 0;
            try { Thread.sleep(1); } catch (InterruptedException ex) { e.cpu.halted = true; }
            return 0;
        }
        fillMsg(e, p, m);
        if (m.msg == WM_KEYDOWN && remove) lastKeyChar = m.ch;
        return 1;
    }

    private int dispatch(Emulator e, int p) {
        int hwnd = e.mem.r32(p), m = e.mem.r32(p + 4), wp = e.mem.r32(p + 8), l = e.mem.r32(p + 12);
        Wnd w = wins.get(hwnd);
        if (m == WM_TIMER && l != 0) { e.callGuest(l, hwnd, WM_TIMER, wp, (int) ((System.nanoTime() - startNs) / 1000000L)); return 0; }
        if (w == null) return 0;
        if (m == WM_PAINT && w.ctl != 0) { paintControl(w); return 0; }
        if (m == WM_PAINT && w.ctl == 0) {
            int r = send(e, w, m, wp, l);
            Wnd t = topOf(w);
            if (t != null && t.host != null) t.host.surface().flush();
            return r;
        }
        return send(e, w, m, wp, l);
    }

    // ================= built-in controls =================
    private int controlProc(Emulator e, Wnd w, int m, int wp, int lpar) {
        switch (m) {
            case WM_SETTEXT: setTitle(w, str(e, lpar, false)); return 1;
            case WM_GETTEXT: { String t = w.text.toString(); if (t.length() >= wp) t = t.substring(0, Math.max(0, wp - 1)); e.mem.putCstr(lpar, t); return t.length(); }
            case WM_GETTEXTLENGTH: return w.text.length();
            case WM_SETFONT: return 0;
            case BM_GETCHECK: return w.checked ? 1 : 0;
            case BM_SETCHECK: w.checked = wp != 0; invalidate(w); return 0;
            case BM_CLICK: clickButton(e, w); return 0;
            case WM_LBUTTONDOWN: if (w.ctl == 1) { w.pressed = true; invalidate(w); capture = w.hwnd; } return 0;
            case WM_LBUTTONUP: {
                if (w.ctl == 1 && w.pressed) {
                    w.pressed = false; capture = 0;
                    int mx = (short) (lpar & 0xFFFF), my = (short) (lpar >> 16);
                    if (mx >= 0 && my >= 0 && mx < w.w && my < w.h) clickButton(e, w);
                    invalidate(w);
                }
                return 0; }
            case WM_CHAR: if (w.ctl == 3) editChar(e, w, wp); return 0;
            case WM_KEYDOWN: if (w.ctl == 3) editKey(w, wp); return 0;
            case WM_ERASEBKGND: return 1;
            case WM_NCCREATE: case WM_CREATE: return 1;
            default: return 0;
        }
    }

    private void clickButton(Emulator e, Wnd w) {
        int t = w.style & 0xF;
        if (t == 3 || t == 5) w.checked = !w.checked;
        else if (t == 9 || t == 4) {
            Wnd p = wins.get(w.parent);
            if (p != null) for (Wnd k : p.kids) if (k.ctl == 1 && ((k.style & 0xF) == 9 || (k.style & 0xF) == 4)) { k.checked = false; invalidate(k); }
            w.checked = true;
        }
        invalidate(w);
        if (w.parent != 0 && wins.containsKey(w.parent)) queue.add(msg(w.parent, WM_COMMAND, (w.id & 0xFFFF), w.hwnd));
    }

    private void editChar(Emulator e, Wnd w, int ch) {
        if (ch == 8) { if (w.caret > 0) { w.text.deleteCharAt(w.caret - 1); w.caret--; } }
        else if (ch == 13) { if ((w.style & 4) != 0) { w.text.insert(w.caret, '\n'); w.caret++; } else return; }
        else if (ch >= 32) { w.text.insert(w.caret, (char) ch); w.caret++; }
        else return;
        w.title = w.text.toString();
        invalidate(w);
        if (w.parent != 0) queue.add(msg(w.parent, WM_COMMAND, (w.id & 0xFFFF) | (0x0300 << 16), w.hwnd));
    }

    private void editKey(Wnd w, int vk) {
        if (vk == 0x25 && w.caret > 0) w.caret--;
        else if (vk == 0x27 && w.caret < w.text.length()) w.caret++;
        else if (vk == 0x24) w.caret = 0;
        else if (vk == 0x23) w.caret = w.text.length();
        else if (vk == 0x2E && w.caret < w.text.length()) { w.text.deleteCharAt(w.caret); w.title = w.text.toString(); }
        invalidate(w);
    }

    private void paintControl(Wnd w) {
        Wnd t = topOf(w);
        if (t == null || t.host == null) return;
        Surface s = t.host.surface();
        int x = absX(w), y = absY(w), fh = 13;
        String txt = w.text.toString();
        switch (w.ctl) {
            case 1: {
                int kind = w.style & 0xF;
                if (kind == 7) { s.rect(x, y + 6, w.w, w.h - 6, -1, 0xA0A0A0, 1); s.text(txt, x + 8, y, 0, 0xF0F0F0, fh, false, false); break; }
                if (kind == 2 || kind == 3 || kind == 4 || kind == 5 || kind == 9) {
                    boolean radio = kind == 4 || kind == 9;
                    s.fillRect(x, y, w.w, w.h, 0xF0F0F0);
                    int by = y + (w.h - 13) / 2;
                    if (radio) s.ellipse(x, by, 13, 13, 0xFFFFFF, 0x333333, 1); else s.rect(x, by, 13, 13, 0xFFFFFF, 0x333333, 1);
                    if (w.checked) { if (radio) s.ellipse(x + 3, by + 3, 7, 7, 0x000000, -1, 0); else { s.line(x + 3, by + 6, x + 6, by + 9, 0, 2); s.line(x + 6, by + 9, x + 11, by + 3, 0, 2); } }
                    s.text(txt, x + 19, y + (w.h - fh) / 2, w.enabled ? 0 : 0x888888, -1, fh, false, false);
                    break;
                }
                int face = w.pressed ? 0xCCE4F7 : 0xE1E1E1;
                s.rect(x, y, w.w, w.h, face, w.pressed ? 0x005499 : ((w.style & 0xF) == 1 ? 0x0078D7 : 0xADADAD), 1);
                int tw = s.textWidth(txt, fh, false);
                s.text(txt, x + (w.w - tw) / 2, y + (w.h - fh) / 2, w.enabled ? 0 : 0x888888, -1, fh, false, false);
                break; }
            case 2: {
                s.fillRect(x, y, w.w, w.h, 0xF0F0F0);
                int tw = s.textWidth(txt, fh, false), al = w.style & 3;
                int tx = al == 1 ? x + (w.w - tw) / 2 : al == 2 ? x + w.w - tw : x;
                s.text(txt, tx, y, 0, -1, fh, false, false);
                break; }
            default: {
                s.rect(x, y, w.w, w.h, 0xFFFFFF, focus == w.hwnd ? 0x0078D7 : 0x7A7A7A, 1);
                String[] lines = (w.style & 4) != 0 ? txt.split("\n", -1) : new String[]{txt};
                for (int i = 0; i < lines.length; i++) s.text(lines[i], x + 3, y + 2 + i * (fh + 2), 0, -1, fh, false, false);
                if (focus == w.hwnd) {
                    String before = txt.substring(0, Math.min(w.caret, txt.length()));
                    int li = before.lastIndexOf('\n');
                    int row = (w.style & 4) != 0 ? before.length() - before.replace("\n", "").length() : 0;
                    String seg = li >= 0 ? before.substring(li + 1) : before;
                    int cx = x + 3 + s.textWidth(seg, fh, false);
                    s.line(cx, y + 2 + row * (fh + 2), cx, y + 2 + row * (fh + 2) + fh, 0, 1);
                }
            }
        }
    }

    // ================= GDI helpers =================
    private int penRgb(DC d) { Object o = obj(d.pen); return o instanceof Pen && ((Pen) o).style != 5 ? ((Pen) o).rgb : -1; }
    private int penW(DC d) { Object o = obj(d.pen); return o instanceof Pen ? Math.max(1, ((Pen) o).width) : 1; }
    private int fillRgb(DC d) { Object o = obj(d.brush); return o instanceof Brush && !((Brush) o).hollow ? ((Brush) o).rgb : -1; }
    private Font fontOf(DC d) { Object o = obj(d.font); return o instanceof Font ? (Font) o : new Font(); }

    private int textOut(DC d, String s, int x, int y) {
        Font f = fontOf(d);
        int tw = d.surf.textWidth(s, f.height, f.bold);
        int ax = x + d.ox, ay = y + d.oy;
        if ((d.align & 6) == 6) ax -= tw / 2; else if ((d.align & 6) == 2) ax -= tw;
        if ((d.align & 24) == 24) ay -= (int) (f.height * 0.8);
        else if ((d.align & 8) != 0) ay -= f.height;
        d.surf.text(s, ax, ay, d.textColor, d.bkMode == 2 ? d.bkColor : -1, f.height, f.bold, f.italic);
        if ((d.align & 1) != 0) d.curX = x + tw;
        flush(d);
        return 1;
    }

    private int srcPixelsToDest(Emulator e, DC dst, DC src, int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh) {
        if (src.dib != null) {
            Bmp b = src.dib;
            int[] px = new int[sw * sh];
            for (int j = 0; j < sh; j++) for (int i = 0; i < sw; i++) px[j * sw + i] = dibPixel(e, b, sx + i, sy + j);
            dst.surf.pixels(px, sw, sh, dx + dst.ox, dy + dst.oy, dw, dh);
        } else dst.surf.blit(src.surf, sx + src.ox, sy + src.oy, sw, sh, dx + dst.ox, dy + dst.oy, dw, dh);
        flush(dst);
        return 1;
    }

    private int dibPixel(Emulator e, Bmp b, int x, int y) {
        if (x < 0 || y < 0 || x >= b.w || y >= b.h) return 0;
        int row = b.top ? y : b.h - 1 - y;
        int a = b.bitsPtr + row * b.stride;
        switch (b.bpp) {
            case 32: return e.mem.r32(a + 4 * x) & 0xFFFFFF;
            case 24: return e.mem.r8(a + 3 * x) | (e.mem.r8(a + 3 * x + 1) << 8) | (e.mem.r8(a + 3 * x + 2) << 16);
            case 16: { int v = e.mem.r16(a + 2 * x); int r = (v >> 10) & 31, g = (v >> 5) & 31, bl = v & 31; return ((bl << 3) | (bl >> 2)) | (((g << 3) | (g >> 2)) << 8) | (((r << 3) | (r >> 2)) << 16); }
            case 8: { int idx = e.mem.r8(a + x); return b.palette != null && idx < b.palette.length ? b.palette[idx] : idx * 0x010101; }
            default: return 0;
        }
    }

    private int[] readDib(Emulator e, int bmi, int bits, int sx, int sy, int sw, int sh, Bmp out) {
        int size = e.mem.r32(bmi), w = e.mem.r32(bmi + 4), h = e.mem.r32(bmi + 8), bpp = e.mem.r16(bmi + 14), comp = e.mem.r32(bmi + 16);
        int used = e.mem.r32(bmi + 32);
        boolean top = h < 0;
        h = Math.abs(h);
        Bmp b = out != null ? out : new Bmp();
        b.w = w; b.h = h; b.bpp = bpp; b.top = top; b.bitsPtr = bits; b.stride = ((w * bpp + 31) / 32) * 4;
        if (bpp <= 8) {
            int n = used != 0 ? used : (1 << bpp);
            b.palette = new int[n];
            for (int i = 0; i < n; i++) { int c = e.mem.r32(bmi + size + 4 * i); b.palette[i] = c & 0xFFFFFF; }
        }
        if (comp == 3 && bpp == 16) b.bpp = 16;
        int[] px = new int[sw * sh];
        for (int j = 0; j < sh; j++) for (int i = 0; i < sw; i++) px[j * sw + i] = dibPixel(e, b, sx + i, sy + j);
        return px;
    }

    private int drawText(Emulator e, DC d, String s, int rp, int fmt) {
        int l = e.mem.r32(rp), t = e.mem.r32(rp + 4), r = e.mem.r32(rp + 8), b = e.mem.r32(rp + 12);
        Font f = fontOf(d);
        ArrayList<String> lines = new ArrayList<>();
        int width = r - l;
        if ((fmt & 0x20) != 0) lines.add(s.replace("\r", "").replace("\n", " "));
        else for (String para : s.replace("\r", "").split("\n", -1)) {
            if ((fmt & 0x10) == 0) { lines.add(para); continue; }
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                String test = cur.length() == 0 ? word : cur + " " + word;
                if (cur.length() > 0 && d.surf.textWidth(test, f.height, f.bold) > width) { lines.add(cur.toString()); cur = new StringBuilder(word); }
                else { cur = new StringBuilder(test); }
            }
            lines.add(cur.toString());
        }
        int lh = f.height + 2, total = lines.size() * lh, maxw = 0;
        for (String ln : lines) maxw = Math.max(maxw, d.surf.textWidth(ln, f.height, f.bold));
        if ((fmt & 0x400) != 0) { e.mem.w32(rp + 8, l + maxw); e.mem.w32(rp + 12, t + total); return total; }
        int y0 = (fmt & 4) != 0 ? t + (b - t - total) / 2 : (fmt & 8) != 0 ? b - total : t;
        for (int i = 0; i < lines.size(); i++) {
            String ln = lines.get(i);
            int tw = d.surf.textWidth(ln, f.height, f.bold);
            int x0 = (fmt & 1) != 0 ? l + (width - tw) / 2 : (fmt & 2) != 0 ? r - tw : l;
            d.surf.text(ln, x0 + d.ox, y0 + i * lh + d.oy, d.textColor, d.bkMode == 2 ? d.bkColor : -1, f.height, f.bold, f.italic);
        }
        flush(d);
        return total;
    }

    // ================= API dispatch =================
    @Override
    public int call(String n, Emulator e) {
        emu = e;
        switch (n) {
            // ---------- window classes ----------
            case "RegisterClassA": case "RegisterClassW": case "RegisterClassExA": case "RegisterClassExW": {
                boolean ex = n.contains("Ex"), wide = n.endsWith("W");
                int p = e.a(0), o = ex ? 4 : 0;
                WndClass wc = new WndClass();
                wc.style = e.mem.r32(p + o); wc.proc = e.mem.r32(p + o + 4); wc.extra = e.mem.r32(p + o + 12);
                wc.brush = e.mem.r32(p + o + 28);
                int np = e.mem.r32(p + o + 36);
                wc.name = (np >>> 16) == 0 ? "#" + np : str(e, np, wide);
                classes.put(wc.name.toLowerCase(), wc);
                return 0xC000 + classes.size(); }
            case "UnregisterClassA": return 1;
            // ---------- windows ----------
            case "CreateWindowExA": return createWindow(e, false);
            case "CreateWindowExW": return createWindow(e, true);
            case "DestroyWindow": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; destroyWindow(e, w); return 1; }
            case "ShowWindow": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; showWindow(e, w, e.a(1)); return 1; }
            case "UpdateWindow": return 1;
            case "IsWindow": return wins.containsKey(e.a(0)) ? 1 : 0;
            case "IsWindowVisible": { Wnd w = wins.get(e.a(0)); return w != null && visibleChain(w) ? 1 : 0; }
            case "IsIconic": case "IsZoomed": return 0;
            case "EnableWindow": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; boolean was = w.enabled; w.enabled = e.a(1) != 0; invalidate(w); return was ? 0 : 1; }
            case "IsWindowEnabled": { Wnd w = wins.get(e.a(0)); return w != null && w.enabled ? 1 : 0; }
            case "GetParent": { Wnd w = wins.get(e.a(0)); return w == null || w.top ? 0 : w.parent; }
            case "GetWindow": return 0;
            case "GetDesktopWindow": return 0x10;
            case "SetForegroundWindow": case "SetActiveWindow": activeWnd = e.a(0); return 1;
            case "GetActiveWindow": case "GetForegroundWindow": return activeWnd;
            case "GetWindowThreadProcessId": if (e.a(1) != 0) e.mem.w32(e.a(1), 1234); return 5678;
            case "SetWindowTextA": case "SetWindowTextW": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; setTitle(w, str(e, e.a(1), n.endsWith("W"))); return 1; }
            case "GetWindowTextA": case "GetWindowTextW": {
                Wnd w = wins.get(e.a(0)); if (w == null) return 0;
                String t = w.ctl != 0 ? w.text.toString() : w.title;
                if (t.length() >= e.a(2)) t = t.substring(0, Math.max(0, e.a(2) - 1));
                if (n.endsWith("W")) { for (int i = 0; i < t.length(); i++) e.mem.w16(e.a(1) + 2 * i, t.charAt(i)); e.mem.w16(e.a(1) + 2 * t.length(), 0); }
                else e.mem.putCstr(e.a(1), t);
                return t.length(); }
            case "GetWindowTextLengthA": { Wnd w = wins.get(e.a(0)); return w == null ? 0 : (w.ctl != 0 ? w.text.length() : w.title.length()); }
            case "SetDlgItemTextA": { Wnd p = wins.get(e.a(0)); if (p == null) return 0; for (Wnd k : p.kids) if (k.id == e.a(1)) setTitle(k, e.mem.cstr(e.a(2))); return 1; }
            case "GetDlgItemTextA": { Wnd p = wins.get(e.a(0)); if (p == null) return 0; for (Wnd k : p.kids) if (k.id == e.a(1)) { String t = k.text.toString(); if (t.length() >= e.a(3)) t = t.substring(0, Math.max(0, e.a(3) - 1)); e.mem.putCstr(e.a(2), t); return t.length(); } return 0; }
            case "GetDlgItem": { Wnd p = wins.get(e.a(0)); if (p == null) return 0; for (Wnd k : p.kids) if (k.id == e.a(1)) return k.hwnd; return 0; }
            case "GetClassNameA": { Wnd w = wins.get(e.a(0)); String c = w == null ? "" : w.cls != null ? w.cls.name : w.ctl == 1 ? "Button" : w.ctl == 2 ? "Static" : "Edit"; e.mem.putCstr(e.a(1), c); return c.length(); }
            case "GetClientRect": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; writeRect(e, e.a(1), 0, 0, w.cw, w.ch); return 1; }
            case "GetWindowRect": {
                Wnd w = wins.get(e.a(0)); if (w == null) return 0;
                Wnd t = topOf(w); int ox = t == null ? 0 : t.x + (w.top ? 0 : FRAME_X) + (w.top ? 0 : absX(w)), oy = t == null ? 0 : t.y + (w.top ? 0 : FRAME_TOP) + (w.top ? 0 : absY(w));
                writeRect(e, e.a(1), w.top ? w.x : ox, w.top ? w.y : oy, (w.top ? w.x : ox) + w.w, (w.top ? w.y : oy) + w.h); return 1; }
            case "MoveWindow": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; w.x = e.a(1); w.y = e.a(2); w.w = e.a(3); w.h = e.a(4); if (!w.top || (w.style & WS_CAPTION) != WS_CAPTION) { w.cw = w.w; w.ch = w.h; } invalidate(w); return 1; }
            case "SetWindowPos": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; int fl = e.a(6); if ((fl & 2) == 0) { w.x = e.a(2); w.y = e.a(3); } if ((fl & 1) == 0 && !w.top) { w.w = e.a(4); w.h = e.a(5); w.cw = w.w; w.ch = w.h; } invalidate(w); return 1; }
            case "AdjustWindowRect": case "AdjustWindowRectEx": {
                int p = e.a(0), st = e.a(1);
                if ((st & WS_CAPTION) == WS_CAPTION) { e.mem.w32(p, e.mem.r32(p) - FRAME_X); e.mem.w32(p + 4, e.mem.r32(p + 4) - FRAME_TOP); e.mem.w32(p + 8, e.mem.r32(p + 8) + FRAME_X); e.mem.w32(p + 12, e.mem.r32(p + 12) + FRAME_BOTTOM); }
                return 1; }
            case "SetWindowLongA": case "SetWindowLongW": {
                Wnd w = wins.get(e.a(0)); if (w == null) return 0; int idx = e.a(1), v = e.a(2), old;
                switch (idx) {
                    case -4: old = w.proc != 0 ? w.proc : w.cls != null ? w.cls.proc : e.stubFor("DefWindowProcA"); w.proc = v; return old;
                    case -21: old = w.userData; w.userData = v; return old;
                    case -16: old = w.style; w.style = v; return old;
                    case -20: old = w.exStyle; w.exStyle = v; return old;
                    case -12: old = w.id; w.id = v; return old;
                    default: if (idx >= 0 && idx / 4 < w.extra.length) { old = w.extra[idx / 4]; w.extra[idx / 4] = v; return old; } return 0;
                } }
            case "GetWindowLongA": case "GetWindowLongW": {
                Wnd w = wins.get(e.a(0)); if (w == null) return 0; int idx = e.a(1);
                switch (idx) {
                    case -4: return w.proc != 0 ? w.proc : w.cls != null ? w.cls.proc : e.stubFor("DefWindowProcA");
                    case -21: return w.userData; case -16: return w.style; case -20: return w.exStyle; case -12: return w.id; case -6: return 0x400000; case -8: return w.parent;
                    default: return idx >= 0 && idx / 4 < w.extra.length ? w.extra[idx / 4] : 0;
                } }
            case "SetFocus": { Wnd w = wins.get(e.a(0)); int old = focus; if (w != null) { focus = w.hwnd; Wnd t = topOf(w); if (t != null) activeWnd = t.hwnd; } return old; }
            case "GetFocus": return focus;
            case "SetCapture": { int old = capture; capture = e.a(0); return old; }
            case "ReleaseCapture": capture = 0; return 1;
            case "GetCapture": return capture;
            // ---------- messages ----------
            case "GetMessageA": case "GetMessageW": return getMessage(e);
            case "PeekMessageA": case "PeekMessageW": return peekMessage(e);
            case "TranslateMessage": {
                int p = e.a(0);
                if (e.mem.r32(p + 4) == WM_KEYDOWN && lastKeyChar != 0) { int ch = lastKeyChar; lastKeyChar = 0; queue.add(msg(e.mem.r32(p), WM_CHAR, ch, e.mem.r32(p + 12))); return 1; }
                return 0; }
            case "DispatchMessageA": case "DispatchMessageW": return dispatch(e, e.a(0));
            case "PostMessageA": case "PostMessageW": { int h = e.a(0); if (h == 0 || h == 0xFFFF) { queue.add(msg(0, e.a(1), e.a(2), e.a(3))); return 1; } if (!wins.containsKey(h)) return 0; queue.add(msg(h, e.a(1), e.a(2), e.a(3))); return 1; }
            case "SendMessageA": case "SendMessageW": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; return send(e, w, e.a(1), e.a(2), e.a(3)); }
            case "PostQuitMessage": quit = true; quitCode = e.a(0); return 0;
            case "DefWindowProcA": case "DefWindowProcW": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; return w.ctl != 0 ? controlProc(e, w, e.a(1), e.a(2), e.a(3)) : defProc(e, w, e.a(1), e.a(2), e.a(3)); }
            case "CallWindowProcA": case "CallWindowProcW": {
                Wnd w = wins.get(e.a(1));
                if (e.a(0) == e.stubFor("DefWindowProcA") || e.a(0) == e.stubFor("DefWindowProcW")) return w == null ? 0 : defProc(e, w, e.a(2), e.a(3), e.a(4));
                return e.callGuest(e.a(0), e.a(1), e.a(2), e.a(3), e.a(4)); }
            case "GetMessageTime": return (int) ((System.nanoTime() - startNs) / 1000000L);
            case "GetMessagePos": return lp(mouseX, mouseY);
            case "SetTimer": {
                Timer t = new Timer(); t.hwnd = e.a(0); t.id = e.a(1) != 0 ? e.a(1) : nextTimer++ + 1000; t.interval = Math.max(10, e.a(2) & 0xFFFFFFFFL); t.proc = e.a(3);
                t.due = System.nanoTime() / 1000000L + t.interval;
                timers.put((t.hwnd << 8) ^ t.id, t);
                return t.id; }
            case "KillTimer": return timers.remove((e.a(0) << 8) ^ e.a(1)) != null ? 1 : 0;
            case "InvalidateRect": case "RedrawWindow": { Wnd w = wins.get(e.a(0)); if (w != null) invalidate(w); return 1; }
            case "ValidateRect": { Wnd w = wins.get(e.a(0)); if (w != null) w.invalid = false; return 1; }
            case "GetUpdateRect": { Wnd w = wins.get(e.a(0)); if (w == null) return 0; if (e.a(1) != 0) writeRect(e, e.a(1), 0, 0, w.cw, w.ch); return w.invalid ? 1 : 0; }
            // ---------- input state ----------
            case "GetKeyState": case "GetAsyncKeyState": { int vk = e.a(0) & 0xFF; return keys[vk] ? 0x8001 | 0x80 : 0; }
            case "GetCursorPos": e.mem.w32(e.a(0), mouseX); e.mem.w32(e.a(0) + 4, mouseY); return 1;
            case "SetCursorPos": case "ClipCursor": case "SetCursor": case "ShowCursor": return 1;
            case "ScreenToClient": case "ClientToScreen": {
                Wnd w = wins.get(e.a(0)); if (w == null) return 0; Wnd t = topOf(w); int p = e.a(1);
                int ox = (t != null ? t.x + FRAME_X : 0) + absX(w), oy = (t != null ? t.y + FRAME_TOP : 0) + absY(w);
                int sgn = n.equals("ScreenToClient") ? -1 : 1;
                e.mem.w32(p, e.mem.r32(p) + sgn * ox); e.mem.w32(p + 4, e.mem.r32(p + 4) + sgn * oy); return 1; }
            case "MapVirtualKeyA": return e.a(1) == 2 ? e.a(0) : e.a(0);
            case "LoadCursorA": case "LoadCursorW": return 0x100 + e.a(1);
            case "LoadIconA": case "LoadIconW": return 0x200 + e.a(1);
            case "TranslateAcceleratorA": return 0;
            case "MessageBeep": return 1;
            case "SystemParametersInfoA": return 0;
            case "SetWindowsHookExA": return 1;
            case "UnhookWindowsHookEx": return 1;
            case "EnumWindows": return 1;
            case "CreateMenu": case "CreatePopupMenu": return newObj("menu");
            case "SetMenu": case "AppendMenuA": case "DestroyMenu": case "DrawMenuBar": return 1;
            case "GetSystemMetrics": {
                switch (e.a(0)) {
                    case 0: case 16: return 1280; case 1: return 720; case 17: return 697; case 2: case 3: return 17; case 4: return 23; case 5: case 6: return 1;
                    case 7: case 8: return 3; case 15: return 20; case 32: case 33: return 8; case 43: return 3; case 75: return 1; default: return 0;
                } }
            case "GetSysColor": return rgbOf(sysColor(e.a(0)));
            case "GetSysColorBrush": { Brush b = new Brush(); b.rgb = sysColor(e.a(0)); return newObj(b); }
            // ---------- rect helpers ----------
            case "PtInRect": { int p = e.a(0); return e.a(1) >= e.mem.r32(p) && e.a(1) < e.mem.r32(p + 8) && e.a(2) >= e.mem.r32(p + 4) && e.a(2) < e.mem.r32(p + 12) ? 1 : 0; }
            case "SetRect": writeRect(e, e.a(0), e.a(1), e.a(2), e.a(3), e.a(4)); return 1;
            case "OffsetRect": { int p = e.a(0); writeRect(e, p, e.mem.r32(p) + e.a(1), e.mem.r32(p + 4) + e.a(2), e.mem.r32(p + 8) + e.a(1), e.mem.r32(p + 12) + e.a(2)); return 1; }
            case "InflateRect": { int p = e.a(0); writeRect(e, p, e.mem.r32(p) - e.a(1), e.mem.r32(p + 4) - e.a(2), e.mem.r32(p + 8) + e.a(1), e.mem.r32(p + 12) + e.a(2)); return 1; }
            case "IntersectRect": {
                int a = e.a(1), b = e.a(2);
                int l = Math.max(e.mem.r32(a), e.mem.r32(b)), t = Math.max(e.mem.r32(a + 4), e.mem.r32(b + 4)), r = Math.min(e.mem.r32(a + 8), e.mem.r32(b + 8)), bt = Math.min(e.mem.r32(a + 12), e.mem.r32(b + 12));
                if (l >= r || t >= bt) { writeRect(e, e.a(0), 0, 0, 0, 0); return 0; }
                writeRect(e, e.a(0), l, t, r, bt); return 1; }
            case "EqualRect": for (int i = 0; i < 16; i += 4) if (e.mem.r32(e.a(0) + i) != e.mem.r32(e.a(1) + i)) return 0; return 1;
            case "CopyRect": for (int i = 0; i < 16; i += 4) e.mem.w32(e.a(0) + i, e.mem.r32(e.a(1) + i)); return 1;
            case "IsRectEmpty": { int p = e.a(0); return e.mem.r32(p) >= e.mem.r32(p + 8) || e.mem.r32(p + 4) >= e.mem.r32(p + 12) ? 1 : 0; }
            case "CharUpperA": return (e.a(0) >>> 16) == 0 ? Character.toUpperCase(e.a(0)) : upperInPlace(e, e.a(0), true);
            case "CharLowerA": return (e.a(0) >>> 16) == 0 ? Character.toLowerCase(e.a(0)) : upperInPlace(e, e.a(0), false);
            case "wsprintfA": { String s = e.format(e.mem.cstr(e.a(1)), e.stackArgs(2)); e.mem.putCstr(e.a(0), s); return s.length(); }
            // ---------- DC / painting ----------
            case "GetDC": case "GetWindowDC": { Wnd w = wins.get(e.a(0)); return regDc(newDc(w)); }
            case "ReleaseDC": { DC d = dcs.remove(e.a(1)); flush(d); return 1; }
            case "BeginPaint": {
                Wnd w = wins.get(e.a(0)); if (w == null) return 0;
                DC d = newDc(w); int h = regDc(d); int ps = e.a(1);
                for (int i = 0; i < 64; i += 4) e.mem.w32(ps + i, 0);
                e.mem.w32(ps, h); e.mem.w32(ps + 4, 1); writeRect(e, ps + 8, 0, 0, w.cw, w.ch);
                if (w.ctl == 0) send(e, w, WM_ERASEBKGND, h, 0);
                return h; }
            case "EndPaint": { DC d = dcs.remove(e.mem.r32(e.a(1))); flush(d); return 1; }
            case "FillRect": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; int p = e.a(1); Brush b = brushOf(e.a(2));
                int l = e.mem.r32(p), t = e.mem.r32(p + 4), r = e.mem.r32(p + 8), bt = e.mem.r32(p + 12);
                if (b != null && !b.hollow) d.surf.fillRect(l + d.ox, t + d.oy, r - l, bt - t, b.rgb);
                flush(d); return 1; }
            case "FrameRect": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; int p = e.a(1); Brush b = brushOf(e.a(2));
                int l = e.mem.r32(p), t = e.mem.r32(p + 4), r = e.mem.r32(p + 8), bt = e.mem.r32(p + 12);
                if (b != null) d.surf.rect(l + d.ox, t + d.oy, r - l, bt - t, -1, b.rgb, 1);
                return 1; }
            case "InvertRect": return 1;
            case "DrawTextA": case "DrawTextW": {
                DC d = dcOf(e.a(0)); if (d == null) return 0;
                String s = n.endsWith("W") ? (e.a(2) < 0 ? e.wstr(e.a(1)) : wsub(e, e.a(1), e.a(2))) : (e.a(2) < 0 ? e.mem.cstr(e.a(1)) : latinSub(e, e.a(1), e.a(2)));
                return drawText(e, d, s, e.a(3), e.a(4)); }
            case "GetStockObject": return 0x30000000 + e.a(0);
            case "CreateSolidBrush": { Brush b = new Brush(); b.rgb = rgbOf(e.a(0)); return newObj(b); }
            case "CreatePen": { Pen p = new Pen(); p.style = e.a(0); p.width = e.a(1); p.rgb = rgbOf(e.a(2)); return newObj(p); }
            case "CreateFontA": case "CreateFontW": { Font f = new Font(); f.height = Math.abs(e.a(0)) == 0 ? 13 : Math.abs(e.a(0)); f.bold = e.a(4) >= 600; f.italic = (e.a(5) & 0xFF) != 0; return newObj(f); }
            case "CreateFontIndirectA": { Font f = new Font(); int p = e.a(0); int h = Math.abs(e.mem.r32(p)); f.height = h == 0 ? 13 : h; f.bold = e.mem.r32(p + 16) >= 600; f.italic = e.mem.r8(p + 20) != 0; return newObj(f); }
            case "SelectObject": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; int h = e.a(1); Object o = obj(h);
                if (o instanceof Pen) { int old = d.pen; d.pen = h; return old; }
                if (o instanceof Brush) { int old = d.brush; d.brush = h; return old; }
                if (o instanceof Font) { int old = d.font; d.font = h; return old; }
                if (o instanceof Bmp) {
                    Bmp b = (Bmp) o; int old = d.bitmap; d.bitmap = h;
                    if (b.bitsPtr != 0) { d.dib = b; d.surf = dummySurface(); } else { d.dib = null; d.surf = b.surf; }
                    d.ox = 0; d.oy = 0; return old; }
                return 0; }
            case "DeleteObject": objs.remove(e.a(0)); return 1;
            case "DeleteDC": dcs.remove(e.a(0)); return 1;
            case "SetBkColor": { DC d = dcOf(e.a(0)); if (d == null) return 0; int old = rgbOf(d.bkColor); d.bkColor = rgbOf(e.a(1)); return old; }
            case "SetBkMode": { DC d = dcOf(e.a(0)); if (d == null) return 0; int old = d.bkMode; d.bkMode = e.a(1); return old; }
            case "SetTextColor": { DC d = dcOf(e.a(0)); if (d == null) return 0; int old = rgbOf(d.textColor); d.textColor = rgbOf(e.a(1)); return old; }
            case "GetTextColor": { DC d = dcOf(e.a(0)); return d == null ? 0 : rgbOf(d.textColor); }
            case "SetTextAlign": { DC d = dcOf(e.a(0)); if (d == null) return 0; int old = d.align; d.align = e.a(1); return old; }
            case "SetROP2": case "SetStretchBltMode": case "SetMapMode": return 1;
            case "SelectClipRgn": case "IntersectClipRect": return 1;
            case "GdiFlush": return 1;
            case "SaveDC": return 1;
            case "RestoreDC": return 1;
            case "TextOutA": { DC d = dcOf(e.a(0)); if (d == null) return 0; return textOut(d, latinSub(e, e.a(3), e.a(4)), e.a(1), e.a(2)); }
            case "TextOutW": { DC d = dcOf(e.a(0)); if (d == null) return 0; return textOut(d, wsub(e, e.a(3), e.a(4)), e.a(1), e.a(2)); }
            case "ExtTextOutA": { DC d = dcOf(e.a(0)); if (d == null) return 0; return textOut(d, latinSub(e, e.a(5), e.a(6)), e.a(1), e.a(2)); }
            case "ExtTextOutW": { DC d = dcOf(e.a(0)); if (d == null) return 0; return textOut(d, wsub(e, e.a(5), e.a(6)), e.a(1), e.a(2)); }
            case "MoveToEx": { DC d = dcOf(e.a(0)); if (d == null) return 0; if (e.a(3) != 0) { e.mem.w32(e.a(3), d.curX); e.mem.w32(e.a(3) + 4, d.curY); } d.curX = e.a(1); d.curY = e.a(2); return 1; }
            case "LineTo": {
                DC d = dcOf(e.a(0)); if (d == null) return 0;
                int pr = penRgb(d); if (pr >= 0) d.surf.line(d.curX + d.ox, d.curY + d.oy, e.a(1) + d.ox, e.a(2) + d.oy, pr, penW(d));
                d.curX = e.a(1); d.curY = e.a(2); flush(d); return 1; }
            case "Rectangle": { DC d = dcOf(e.a(0)); if (d == null) return 0; d.surf.rect(e.a(1) + d.ox, e.a(2) + d.oy, e.a(3) - e.a(1), e.a(4) - e.a(2), fillRgb(d), penRgb(d), penW(d)); flush(d); return 1; }
            case "RoundRect": { DC d = dcOf(e.a(0)); if (d == null) return 0; d.surf.roundRect(e.a(1) + d.ox, e.a(2) + d.oy, e.a(3) - e.a(1), e.a(4) - e.a(2), e.a(5), e.a(6), fillRgb(d), penRgb(d), penW(d)); flush(d); return 1; }
            case "Ellipse": { DC d = dcOf(e.a(0)); if (d == null) return 0; d.surf.ellipse(e.a(1) + d.ox, e.a(2) + d.oy, e.a(3) - e.a(1), e.a(4) - e.a(2), fillRgb(d), penRgb(d), penW(d)); flush(d); return 1; }
            case "SetPixel": case "SetPixelV": { DC d = dcOf(e.a(0)); if (d == null) return -1; d.surf.setPixel(e.a(1) + d.ox, e.a(2) + d.oy, rgbOf(e.a(3))); return e.a(3); }
            case "GetPixel": { DC d = dcOf(e.a(0)); if (d == null) return -1; if (d.dib != null) return rgbOf(dibPixel(e, d.dib, e.a(1), e.a(2))); return rgbOf(d.surf.getPixel(e.a(1) + d.ox, e.a(2) + d.oy)); }
            case "Polygon": case "Polyline": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; int cnt = e.a(2); int[] xs = new int[cnt], ys = new int[cnt];
                for (int i = 0; i < cnt; i++) { xs[i] = e.mem.r32(e.a(1) + 8 * i) + d.ox; ys[i] = e.mem.r32(e.a(1) + 8 * i + 4) + d.oy; }
                boolean poly = n.equals("Polygon");
                d.surf.polygon(xs, ys, cnt, poly, poly ? fillRgb(d) : -1, penRgb(d), penW(d)); flush(d); return 1; }
            case "CreateCompatibleDC": {
                DC d = new DC(); Bmp b = new Bmp(); b.surf = host.createSurface(1, 1); b.w = 1; b.h = 1; d.surf = b.surf; d.bitmap = newObj(b);
                return regDc(d); }
            case "CreateCompatibleBitmap": { Bmp b = new Bmp(); b.w = Math.max(1, e.a(1)); b.h = Math.max(1, e.a(2)); b.surf = host.createSurface(b.w, b.h); return newObj(b); }
            case "CreateDIBSection": {
                int bmi = e.a(1); int w = e.mem.r32(bmi + 4), h = e.mem.r32(bmi + 8), bpp = e.mem.r16(bmi + 14);
                Bmp b = new Bmp(); b.w = w; b.h = Math.abs(h); b.bpp = bpp; b.top = h < 0; b.stride = ((w * bpp + 31) / 32) * 4;
                b.bitsPtr = e.allocZero(b.stride * b.h + 16);
                if (bpp <= 8) { int sz = e.mem.r32(bmi), cnt = Math.min(256, 1 << bpp); b.palette = new int[cnt]; for (int i = 0; i < cnt; i++) b.palette[i] = e.mem.r32(bmi + sz + 4 * i) & 0xFFFFFF; }
                if (e.a(3) != 0) e.mem.w32(e.a(3), b.bitsPtr);
                return newObj(b); }
            case "GetObjectA": {
                Object o = obj(e.a(0));
                if (o instanceof Bmp && e.a(2) != 0) { Bmp b = (Bmp) o; int p = e.a(2); e.mem.w32(p, 0); e.mem.w32(p + 4, b.w); e.mem.w32(p + 8, b.h); e.mem.w32(p + 12, b.w * 4); e.mem.w16(p + 16, 1); e.mem.w16(p + 18, 32); e.mem.w32(p + 20, b.bitsPtr); return 24; }
                return 0; }
            case "BitBlt": {
                DC dst = dcOf(e.a(0)), src = dcOf(e.a(5)); if (dst == null) return 0; int rop = e.a(8);
                if (rop == 0x42) { dst.surf.fillRect(e.a(1) + dst.ox, e.a(2) + dst.oy, e.a(3), e.a(4), 0); flush(dst); return 1; }
                if (rop == 0xFF0062) { dst.surf.fillRect(e.a(1) + dst.ox, e.a(2) + dst.oy, e.a(3), e.a(4), 0xFFFFFF); flush(dst); return 1; }
                if (src == null) return 0;
                return srcPixelsToDest(e, dst, src, e.a(6), e.a(7), e.a(3), e.a(4), e.a(1), e.a(2), e.a(3), e.a(4)); }
            case "StretchBlt": {
                DC dst = dcOf(e.a(0)), src = dcOf(e.a(5)); if (dst == null || src == null) return 0;
                return srcPixelsToDest(e, dst, src, e.a(6), e.a(7), e.a(8), e.a(9), e.a(1), e.a(2), e.a(3), e.a(4)); }
            case "PatBlt": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; int rop = e.a(5);
                int c = rop == 0x42 ? 0 : rop == 0xFF0062 ? 0xFFFFFF : fillRgb(d);
                if (c >= 0) d.surf.fillRect(e.a(1) + d.ox, e.a(2) + d.oy, e.a(3), e.a(4), c);
                flush(d); return 1; }
            case "StretchDIBits": {
                DC d = dcOf(e.a(0)); if (d == null) return 0;
                int[] px = readDib(e, e.a(10), e.a(9), e.a(5), e.a(6), e.a(7), e.a(8), null);
                d.surf.pixels(px, e.a(7), e.a(8), e.a(1) + d.ox, e.a(2) + d.oy, e.a(3), e.a(4)); flush(d); return e.a(8); }
            case "SetDIBitsToDevice": {
                DC d = dcOf(e.a(0)); if (d == null) return 0;
                int[] px = readDib(e, e.a(10), e.a(9), e.a(5), e.a(6), e.a(3), e.a(4), null);
                d.surf.pixels(px, e.a(3), e.a(4), e.a(1) + d.ox, e.a(2) + d.oy, e.a(3), e.a(4)); flush(d); return e.a(4); }
            case "GetTextExtentPoint32A": case "GetTextExtentPointA": case "GetTextExtentPoint32W": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; Font f = fontOf(d);
                String s = n.endsWith("W") ? wsub(e, e.a(1), e.a(2)) : latinSub(e, e.a(1), e.a(2));
                e.mem.w32(e.a(3), d.surf.textWidth(s, f.height, f.bold)); e.mem.w32(e.a(3) + 4, f.height); return 1; }
            case "GetTextMetricsA": {
                DC d = dcOf(e.a(0)); if (d == null) return 0; Font f = fontOf(d); int p = e.a(1);
                for (int i = 0; i < 56; i += 4) e.mem.w32(p + i, 0);
                e.mem.w32(p, f.height); e.mem.w32(p + 4, (int) (f.height * 0.8)); e.mem.w32(p + 8, f.height - (int) (f.height * 0.8));
                int aw = d.surf.textWidth("x", f.height, f.bold); e.mem.w32(p + 20, aw); e.mem.w32(p + 24, aw * 2); e.mem.w32(p + 28, f.bold ? 700 : 400);
                return 1; }
            case "GetDeviceCaps": {
                switch (e.a(1)) { case 8: return 1280; case 10: return 720; case 12: return 32; case 14: return 1; case 88: case 90: return 96; case 24: return -1; default: return 0; } }
            // ---------- misc libraries ----------
            case "timeGetTime": return (int) ((System.nanoTime() - startNs) / 1000000L);
            case "timeBeginPeriod": case "timeEndPeriod": case "PlaySoundA": case "InitCommonControls": case "InitCommonControlsEx": return 1;
            case "ShellExecuteA": return 33;
            case "RegOpenKeyExA": case "RegQueryValueExA": return 2;
            case "RegCloseKey": case "RegSetValueExA": case "CoInitialize": case "CoInitializeEx": case "CoUninitialize": return 0;
            case "RegCreateKeyExA": return 5;
            case "MessageBoxA": host.messageBox(e.mem.cstr(e.a(2)), e.mem.cstr(e.a(1))); return 1;
            case "MessageBoxW": host.messageBox(e.wstr(e.a(2)), e.wstr(e.a(1))); return 1;
            default: throw new EmuException("Unimplemented API: " + n);
        }
    }

    private int upperInPlace(Emulator e, int p, boolean up) {
        String s = e.mem.cstr(p);
        e.mem.putCstr(p, up ? s.toUpperCase() : s.toLowerCase());
        return p;
    }

    private String latinSub(Emulator e, int p, int len) {
        if (len < 0) return e.mem.cstr(p);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) e.mem.r8(p + i));
        return sb.toString();
    }

    private String wsub(Emulator e, int p, int len) {
        if (len < 0) return e.wstr(p);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) e.mem.r16(p + 2 * i));
        return sb.toString();
    }
}
