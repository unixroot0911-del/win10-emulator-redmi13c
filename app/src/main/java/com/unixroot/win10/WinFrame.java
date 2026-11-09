package com.unixroot.win10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A draggable, resizable window with minimize / maximize / close buttons. */
final class WinFrame extends LinearLayout {
    private final Desktop desk;
    private final TextView titleView;
    private final FrameLayout body;
    View content;
    TextView taskBtn;
    boolean maximized;
    private int sx, sy, sw, sh;
    private float downX, downY;
    private int startL, startT, startW, startH;

    WinFrame(Desktop d, String title, String glyph) {
        super(d.getContext());
        desk = d;
        Context c = getContext();
        setOrientation(VERTICAL);
        setBackground(Ui.rect(c, 0xFF2B2B2B, 0));
        setPadding(1, 1, 1, 1);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1F1F1F);
        addView(bar, new LayoutParams(Ui.MATCH, Ui.dp(c, 34)));

        TextView icon = Ui.label(c, glyph, 14, Color.WHITE);
        icon.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 6), 0);
        bar.addView(icon);
        titleView = Ui.label(c, title, 13, Color.WHITE);
        titleView.setSingleLine(true);
        bar.addView(titleView, Ui.lp(0, Ui.WRAP, 1));
        bar.addView(winBtn("—", 0, new OnClickListener() { @Override public void onClick(View v) { desk.minimize(WinFrame.this); } }));
        bar.addView(winBtn("▢", 0, new OnClickListener() { @Override public void onClick(View v) { toggleMaximize(); } }));
        bar.addView(winBtn("✕", 0xFFE81123, new OnClickListener() { @Override public void onClick(View v) { desk.close(WinFrame.this); } }));

        bar.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); startL = lp.leftMargin; startT = lp.topMargin; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (maximized) return true;
                        lp.leftMargin = startL + Math.round(e.getRawX() - downX);
                        lp.topMargin = Math.max(0, startT + Math.round(e.getRawY() - downY));
                        setLayoutParams(lp); return true;
                    default: return true;
                }
            }
        });

        body = new FrameLayout(c);
        body.setBackgroundColor(Color.BLACK);
        addView(body, new LayoutParams(Ui.MATCH, 0, 1));

        View handle = new View(c) {
            private final Paint p = new Paint();
            @Override protected void onDraw(Canvas cv) {
                p.setColor(0xFF888888);
                int w = getWidth(), h = getHeight();
                for (int i = 1; i <= 3; i++) cv.drawLine(w - i * w / 4f, h, w, h - i * h / 4f, p);
            }
        };
        handle.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); startW = getWidth(); startH = getHeight();
                        getParent().requestDisallowInterceptTouchEvent(true); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (maximized) return true;
                        lp.width = Math.max(Ui.dp(getContext(), 260), startW + Math.round(e.getRawX() - downX));
                        lp.height = Math.max(Ui.dp(getContext(), 160), startH + Math.round(e.getRawY() - downY));
                        setLayoutParams(lp); return true;
                    default: return true;
                }
            }
        });
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.BOTTOM | Gravity.END);
        handleView = handle;
        handleLp = hl;
    }

    private View handleView;
    private FrameLayout.LayoutParams handleLp;

    void setContent(View v) {
        content = v;
        body.removeAllViews();
        body.addView(v, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        body.addView(handleView, handleLp);
    }

    void setTitle(String t) {
        titleView.setText(t);
        if (taskBtn != null) taskBtn.setText(Desktop.shorten(t));
    }

    private TextView winBtn(String s, final int hoverColor, OnClickListener l) {
        final TextView t = Ui.label(getContext(), s, 14, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LayoutParams(Ui.dp(getContext(), 44), Ui.MATCH));
        t.setOnClickListener(l);
        if (hoverColor != 0) {
            t.setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) v.setBackgroundColor(hoverColor);
                    else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setBackgroundColor(0);
                    return false;
                }
            });
        }
        return t;
    }

    void toggleMaximize() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (!maximized) {
            sx = lp.leftMargin; sy = lp.topMargin; sw = lp.width; sh = lp.height;
            lp.leftMargin = 0; lp.topMargin = 0; lp.width = Ui.MATCH; lp.height = Ui.MATCH;
            maximized = true;
        } else {
            lp.leftMargin = sx; lp.topMargin = sy; lp.width = sw; lp.height = sh;
            maximized = false;
        }
        setLayoutParams(lp);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) desk.focus(this);
        return super.dispatchTouchEvent(ev);
    }
}
