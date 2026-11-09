package com.unixroot.win10;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Standard calculator. */
final class CalcApp extends LinearLayout {
    private final TextView display;
    private double acc;
    private String op;
    private String cur = "0";
    private boolean fresh = true;

    CalcApp(Desktop d) {
        super(d.act);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF1E1E1E);
        display = Ui.label(d.act, "0", 30, Color.WHITE);
        display.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        display.setSingleLine(true);
        display.setPadding(Ui.dp(d.act, 10), Ui.dp(d.act, 6), Ui.dp(d.act, 10), Ui.dp(d.act, 6));
        addView(display, new LayoutParams(Ui.MATCH, 0, 1.2f));
        String[][] rows = {{"C", "⌫", "%", "÷"}, {"7", "8", "9", "×"}, {"4", "5", "6", "−"}, {"1", "2", "3", "+"}, {"±", "0", ".", "="}};
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(d.act);
            for (String key : row) {
                final String k = key;
                boolean opKey = "÷×−+=".contains(k);
                Button b = Ui.button(d.act, k, opKey ? Ui.ACCENT : (Character.isDigit(k.charAt(0)) || k.equals(".") ? 0xFF3B3B3B : 0xFF2B2B2B), Color.WHITE);
                b.setTextSize(17);
                b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { press(k); } });
                r.addView(b, Ui.lpMargins(d.act, 0, Ui.MATCH, 1, 1, 1, 1, 1));
            }
            addView(r, new LayoutParams(Ui.MATCH, 0, 1));
        }
    }

    private double val() {
        try { return Double.parseDouble(cur); } catch (NumberFormatException e) { return 0; }
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "Error";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(java.util.Locale.US, "%.10f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private void apply() {
        if (op == null) { acc = val(); return; }
        double b = val();
        switch (op) {
            case "+": acc += b; break;
            case "−": acc -= b; break;
            case "×": acc *= b; break;
            default: acc = b == 0 ? Double.NaN : acc / b; break;
        }
    }

    private void press(String k) {
        char c = k.charAt(0);
        if (Character.isDigit(c)) {
            if (fresh || cur.equals("0")) cur = k; else if (cur.length() < 16) cur += k;
            fresh = false;
        } else if (k.equals(".")) {
            if (fresh) { cur = "0."; fresh = false; } else if (!cur.contains(".")) cur += ".";
        } else if (k.equals("C")) {
            acc = 0; op = null; cur = "0"; fresh = true;
        } else if (k.equals("⌫")) {
            cur = cur.length() > 1 ? cur.substring(0, cur.length() - 1) : "0";
        } else if (k.equals("±")) {
            cur = cur.startsWith("-") ? cur.substring(1) : (cur.equals("0") ? cur : "-" + cur);
        } else if (k.equals("%")) {
            cur = fmt(val() / 100);
        } else if (k.equals("=")) {
            apply();
            cur = fmt(acc); op = null; fresh = true;
        } else {
            if (!fresh || op == null) apply();
            op = k; cur = fmt(acc); fresh = true;
        }
        display.setText(cur);
    }
}
