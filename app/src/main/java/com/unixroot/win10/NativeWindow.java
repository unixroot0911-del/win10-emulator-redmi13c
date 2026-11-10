package com.unixroot.win10;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import com.unixroot.win10.core.GuiHost;
import com.unixroot.win10.core.Surface;
import com.unixroot.win10.core.Win32Gui;

/** Android implementation of the emulated top-level Win32 window: a bitmap-backed view plus a key bar. */
final class NativeWindow extends LinearLayout implements Desktop.Closeable {
    /** Bitmap + Canvas backed Surface. */
    static final class BmpSurface implements Surface {
        final Bitmap bmp;
        final Canvas cv;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        volatile Runnable onFlush;
        long lastFlush;

        BmpSurface(int w, int h) {
            bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            cv = new Canvas(bmp);
            cv.drawColor(Color.WHITE);
        }
        private static int c(int rgb) { return 0xFF000000 | rgb; }
        @Override public int width() { return bmp.getWidth(); }
        @Override public int height() { return bmp.getHeight(); }
        @Override public synchronized void fillRect(int x, int y, int w, int h, int rgb) {
            p.setStyle(Paint.Style.FILL); p.setColor(c(rgb)); cv.drawRect(x, y, x + w, y + h, p);
        }
        private void draw(RectF r, boolean oval, float rx, float ry, int fill, int pen, int pw) {
            if (fill >= 0) { p.setStyle(Paint.Style.FILL); p.setColor(c(fill));
                if (oval) cv.drawOval(r, p); else if (rx > 0) cv.drawRoundRect(r, rx, ry, p); else cv.drawRect(r, p); }
            if (pen >= 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, pw)); p.setColor(c(pen));
                RectF q = new RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f);
                if (oval) cv.drawOval(q, p); else if (rx > 0) cv.drawRoundRect(q, rx, ry, p); else cv.drawRect(q, p); }
        }
        @Override public synchronized void rect(int x, int y, int w, int h, int f, int pen, int pw) { draw(new RectF(x, y, x + w, y + h), false, 0, 0, f, pen, pw); }
        @Override public synchronized void roundRect(int x, int y, int w, int h, int rx, int ry, int f, int pen, int pw) { draw(new RectF(x, y, x + w, y + h), false, rx / 2f, ry / 2f, f, pen, pw); }
        @Override public synchronized void ellipse(int x, int y, int w, int h, int f, int pen, int pw) { draw(new RectF(x, y, x + w, y + h), true, 0, 0, f, pen, pw); }
        @Override public synchronized void line(int x1, int y1, int x2, int y2, int rgb, int w) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, w)); p.setColor(c(rgb)); cv.drawLine(x1 + 0.5f, y1 + 0.5f, x2 + 0.5f, y2 + 0.5f, p);
        }
        @Override public synchronized void polygon(int[] xs, int[] ys, int n, boolean close, int f, int pen, int pw) {
            if (n < 2) return;
            Path path = new Path();
            path.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) path.lineTo(xs[i], ys[i]);
            if (close) path.close();
            if (f >= 0) { p.setStyle(Paint.Style.FILL); p.setColor(c(f)); cv.drawPath(path, p); }
            if (pen >= 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, pw)); p.setColor(c(pen)); cv.drawPath(path, p); }
        }
        private void font(int fh, boolean bold, boolean italic) {
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? (italic ? Typeface.BOLD_ITALIC : Typeface.BOLD) : (italic ? Typeface.ITALIC : Typeface.NORMAL)));
            p.setTextSize(Math.max(8, fh * 0.78f));
        }
        @Override public synchronized void text(String s, int x, int y, int fg, int bg, int fh, boolean bold, boolean italic) {
            font(fh, bold, italic);
            if (bg >= 0) { p.setStyle(Paint.Style.FILL); p.setColor(c(bg)); cv.drawRect(x, y, x + p.measureText(s), y + fh, p); }
            p.setStyle(Paint.Style.FILL); p.setColor(c(fg));
            cv.drawText(s, x, y - p.ascent() * 0.95f, p);
        }
        @Override public synchronized int textWidth(String s, int fh, boolean bold) { font(fh, bold, false); return (int) Math.ceil(p.measureText(s)); }
        @Override public synchronized void blit(Surface src, int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh) {
            if (!(src instanceof BmpSurface)) return;
            p.setStyle(Paint.Style.FILL); p.setColor(Color.BLACK);
            cv.drawBitmap(((BmpSurface) src).bmp, new Rect(sx, sy, sx + sw, sy + sh), new Rect(dx, dy, dx + dw, dy + dh), null);
        }
        @Override public synchronized void pixels(int[] rgb, int w, int h, int dx, int dy, int dw, int dh) {
            if (w <= 0 || h <= 0) return;
            int[] a = new int[w * h];
            for (int i = 0; i < a.length && i < rgb.length; i++) a[i] = 0xFF000000 | rgb[i];
            Bitmap t = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
            cv.drawBitmap(t, null, new Rect(dx, dy, dx + dw, dy + dh), null);
            t.recycle();
        }
        @Override public synchronized int getPixel(int x, int y) {
            if (x < 0 || y < 0 || x >= bmp.getWidth() || y >= bmp.getHeight()) return 0;
            return bmp.getPixel(x, y) & 0xFFFFFF;
        }
        @Override public synchronized void setPixel(int x, int y, int rgb) {
            if (x >= 0 && y >= 0 && x < bmp.getWidth() && y < bmp.getHeight()) bmp.setPixel(x, y, c(rgb));
        }
        @Override public void flush() { Runnable r = onFlush; if (r != null) r.run(); }
    }

    final BmpSurface surface;
    final Win32Gui gui;
    final int hwnd;
    final View screen;
    private final Desktop d;
    WinFrame frame;
    private volatile boolean closedByGuest;

    NativeWindow(final Desktop desk, Win32Gui g, int h, int cw, int ch) {
        super(desk.act);
        d = desk; gui = g; hwnd = h;
        surface = new BmpSurface(cw, ch);
        setOrientation(VERTICAL);
        screen = new View(desk.act) {
            private final Rect dst = new Rect();
            private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
            private float scale = 1f, ox, oy;
            @Override protected void onDraw(Canvas c) {
                c.drawColor(0xFF101010);
                float sx = getWidth() / (float) surface.width(), sy = getHeight() / (float) surface.height();
                scale = Math.min(sx, sy);
                ox = (getWidth() - surface.width() * scale) / 2f;
                oy = (getHeight() - surface.height() * scale) / 2f;
                dst.set((int) ox, (int) oy, (int) (ox + surface.width() * scale), (int) (oy + surface.height() * scale));
                synchronized (surface) { c.drawBitmap(surface.bmp, null, dst, bp); }
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                int x = (int) ((e.getX() - ox) / scale), y = (int) ((e.getY() - oy) / scale);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: requestFocus(); gui.postMouse(hwnd, 0x201, x, y); return true;
                    case MotionEvent.ACTION_MOVE: gui.postMouse(hwnd, 0x200, x, y); return true;
                    case MotionEvent.ACTION_UP: gui.postMouse(hwnd, 0x202, x, y); return true;
                }
                return true;
            }
            @Override public boolean onCheckIsTextEditor() { return true; }
            @Override public InputConnection onCreateInputConnection(EditorInfo ei) {
                ei.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
                ei.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
                return new BaseInputConnection(this, false) {
                    @Override public boolean commitText(CharSequence t, int pos) {
                        for (int i = 0; i < t.length(); i++) typeChar(t.charAt(i));
                        return true;
                    }
                    @Override public boolean deleteSurroundingText(int a, int b) { key(0x08, '\b'); return true; }
                    @Override public boolean sendKeyEvent(KeyEvent ev) { if (ev.getAction() == KeyEvent.ACTION_DOWN) onKeyDown(ev.getKeyCode(), ev); return true; }
                };
            }
            @Override public boolean onKeyDown(int code, KeyEvent ev) {
                int vk = mapKey(code);
                if (vk == 0) {
                    int u = ev.getUnicodeChar();
                    if (u > 0) { typeChar((char) u); return true; }
                    return false;
                }
                char ch = code == KeyEvent.KEYCODE_ENTER ? '\r' : code == KeyEvent.KEYCODE_DEL ? '\b' : code == KeyEvent.KEYCODE_TAB ? '\t' : code == KeyEvent.KEYCODE_SPACE ? ' ' : 0;
                key(vk, ch);
                return true;
            }
            @Override public boolean onKeyUp(int code, KeyEvent ev) { return true; }
        };
        screen.setFocusable(true);
        screen.setFocusableInTouchMode(true);
        addView(screen, new LayoutParams(Ui.MATCH, 0, 1));

        LinearLayout bar = new LinearLayout(desk.act);
        bar.setBackgroundColor(0xFF2D2D2D);
        bar.addView(kb("⌨", 0, '\0', true));
        bar.addView(kb("←", 0x25, '\0', false));
        bar.addView(kb("↑", 0x26, '\0', false));
        bar.addView(kb("↓", 0x28, '\0', false));
        bar.addView(kb("→", 0x27, '\0', false));
        bar.addView(kb("Space", 0x20, ' ', false));
        bar.addView(kb("Enter", 0x0D, '\r', false));
        bar.addView(kb("Esc", 0x1B, (char) 27, false));
        addView(bar, new LayoutParams(Ui.MATCH, Ui.WRAP));

        surface.onFlush = new Runnable() { @Override public void run() { screen.postInvalidate(); } };
    }

    private Button kb(String label, final int vk, final char ch, final boolean ime) {
        Button b = Ui.button(d.act, label, 0xFF444444, Color.WHITE);
        b.setLayoutParams(Ui.lpMargins(d.act, 0, Ui.WRAP, 1, 2, 2, 2, 2));
        b.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (ime) {
                    screen.requestFocus();
                    InputMethodManager m = (InputMethodManager) d.act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (m != null) m.showSoftInput(screen, 0);
                } else key(vk, ch);
            }
        });
        return b;
    }

    private void key(int vk, char ch) { gui.postKey(vk, true, ch); gui.postKey(vk, false, 0); }

    void typeChar(char ch) {
        int vk = Character.isLetter(ch) ? Character.toUpperCase(ch) : Character.isDigit(ch) ? ch : ch == ' ' ? 0x20 : ch == '\n' ? 0x0D : 0;
        key(vk, ch == '\n' ? '\r' : ch);
    }

    static int mapKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x25;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x26;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x27;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x28;
            case KeyEvent.KEYCODE_ENTER: return 0x0D;
            case KeyEvent.KEYCODE_DEL: return 0x08;
            case KeyEvent.KEYCODE_TAB: return 0x09;
            case KeyEvent.KEYCODE_ESCAPE: return 0x1B;
            case KeyEvent.KEYCODE_SPACE: return 0x20;
        }
        return 0;
    }

    @Override public void onClose() { if (!closedByGuest) gui.windowClosed(hwnd); }

    void guestClosed() { closedByGuest = true; if (frame != null) d.close(frame); }
}
