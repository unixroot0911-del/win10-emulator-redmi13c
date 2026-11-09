package com.unixroot.win10;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Proxy and appearance settings. */
final class SettingsApp extends ScrollView {
    private final Desktop d;
    private final EditText proxy, user, pass;
    private final TextView status;

    SettingsApp(Desktop desk) {
        super(desk.act);
        d = desk;
        setBackgroundColor(0xFF1E1E1E);
        LinearLayout col = new LinearLayout(d.act);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(d.act, 12);
        col.setPadding(pad, pad, pad, pad);
        addView(col);

        col.addView(Ui.label(d.act, "Network proxy", 16, Color.WHITE));
        col.addView(Ui.label(d.act, "Applies to the Browser and to the CURL command.\nExamples:  http://1.2.3.4:8080    socks5://1.2.3.4:1080", 11, 0xFFAAAAAA));
        SharedPreferences p = Net.prefs(d.act);
        proxy = field("Proxy (host:port or scheme://host:port)", p.getString("proxy", ""), false);
        user = field("Username (optional)", p.getString("proxyUser", ""), false);
        pass = field("Password (optional)", p.getString("proxyPass", ""), true);
        col.addView(proxy);
        col.addView(user);
        col.addView(pass);

        LinearLayout row = new LinearLayout(d.act);
        Button apply = Ui.button(d.act, "Apply", Ui.ACCENT, Color.WHITE);
        Button clear = Ui.button(d.act, "Disable proxy", 0xFF555555, Color.WHITE);
        Button test = Ui.button(d.act, "Test connection", 0xFF3A7D44, Color.WHITE);
        apply.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        clear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { proxy.setText(""); save(); } });
        test.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { test(); } });
        row.addView(apply, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 0, 6, 6, 6));
        row.addView(clear, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 0, 6, 6, 6));
        row.addView(test, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 0, 6, 0, 6));
        col.addView(row);
        status = Ui.label(d.act, "", 12, 0xFF9CDCFE);
        col.addView(status);

        col.addView(Ui.spacer(d.act, 14));
        col.addView(Ui.label(d.act, "Desktop background", 16, Color.WHITE));
        LinearLayout wp = new LinearLayout(d.act);
        int[] colors = {0xFF1E6FD0, 0xFF6A3093, 0xFF2E9C6A, 0xFF6B6B6B};
        for (int i = 0; i < colors.length; i++) {
            final int idx = i;
            Button b = Ui.button(d.act, "   ", colors[i], Color.WHITE);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.setWallpaper(idx); } });
            wp.addView(b, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 0, 6, 8, 6));
        }
        col.addView(wp);
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(d.act);
        e.setHint(hint);
        e.setHintTextColor(0xFF777777);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        return e;
    }

    private void save() {
        Net.prefs(d.act).edit().putString("proxy", proxy.getText().toString().trim())
                .putString("proxyUser", user.getText().toString()).putString("proxyPass", pass.getText().toString()).apply();
        status.setText(Net.applyWebViewProxy(d.act));
    }

    private void test() {
        save();
        status.setText(status.getText() + "\nTesting http://example.com ...");
        new Thread(new Runnable() { @Override public void run() {
            String r;
            try { String s = Net.fetch(d.act, "http://example.com/", 200); r = "Success: " + s.substring(0, Math.min(10, s.length())).trim(); }
            catch (Exception e) { r = "Failed: " + e; }
            final String msg = r;
            d.act.runOnUiThread(new Runnable() { @Override public void run() { status.setText(status.getText() + "\n" + msg); } });
        } }).start();
    }
}
