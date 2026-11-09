package com.unixroot.win10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view-building helpers shared by all apps. */
final class Ui {
    static final int ACCENT = 0xFF0078D7;
    static final int DARK = 0xFF1F1F1F;
    static final int PANEL = 0xFF2B2B2B;

    private Ui() { }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rect(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView label(Context c, String text, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Context c, String text, int bg, int fg) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(13);
        b.setBackground(rect(c, bg, 3));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams lpMargins(Context c, int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static TextView mono(Context c, int sp) {
        TextView t = new TextView(c);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(204, 204, 204));
        return t;
    }

    static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(c, heightDp)));
        return v;
    }

    static void center(TextView t) { t.setGravity(Gravity.CENTER); }
}
