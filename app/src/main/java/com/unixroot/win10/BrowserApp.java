package com.unixroot.win10;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Web browser built on the system WebView; honours the proxy configured in Settings. */
final class BrowserApp extends LinearLayout implements Desktop.Closeable {
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private final Desktop d;
    private final WinFrame w;
    private final WebView web;
    private final EditText url;
    private final ProgressBar progress;

    BrowserApp(Desktop desk, WinFrame win, String start) {
        super(desk.act);
        d = desk;
        w = win;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF202124);

        LinearLayout bar = new LinearLayout(d.act);
        bar.setBackgroundColor(0xFF35363A);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = nav("◀"), fwd = nav("▶"), reload = nav("↻"), go = nav("Go");
        back.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (web.canGoBack()) web.goBack(); } });
        fwd.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (web.canGoForward()) web.goForward(); } });
        reload.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { web.reload(); } });
        go.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { navigate(); } });

        url = new EditText(d.act);
        url.setSingleLine(true);
        url.setTextSize(13);
        url.setTextColor(Color.WHITE);
        url.setHint("Search or type a URL");
        url.setHintTextColor(0xFF999999);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setBackground(Ui.rect(d.act, 0xFF202124, 14));
        url.setPadding(Ui.dp(d.act, 12), Ui.dp(d.act, 4), Ui.dp(d.act, 12), Ui.dp(d.act, 4));
        url.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) { navigate(); return true; }
        });
        bar.addView(back); bar.addView(fwd); bar.addView(reload);
        bar.addView(url, Ui.lpMargins(d.act, 0, Ui.WRAP, 1, 4, 2, 4, 2));
        bar.addView(go);
        addView(bar, new LayoutParams(Ui.MATCH, Ui.WRAP));

        progress = new ProgressBar(d.act, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        addView(progress, new LayoutParams(Ui.MATCH, Ui.dp(d.act, 3)));

        web = new WebView(d.act);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(DESKTOP_UA);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String u, Bitmap icon) { url.setText(u); }
            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                String user = Net.prefs(d.act).getString("proxyUser", ""), pass = Net.prefs(d.act).getString("proxyPass", "");
                if (!user.isEmpty() && Net.proxySpec(d.act).contains(host)) h.proceed(user, pass); else h.cancel();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); progress.setVisibility(p >= 100 ? GONE : VISIBLE); }
            @Override public void onReceivedTitle(WebView v, String title) { w.setTitle(title + " - Browser"); }
        });
        addView(web, Ui.lp(Ui.MATCH, 0, 1));
        web.loadUrl(start != null ? start : "https://duckduckgo.com");
    }

    private Button nav(String t) {
        Button b = Ui.button(d.act, t, 0xFF35363A, Color.WHITE);
        b.setLayoutParams(Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 1, 2, 1, 2));
        return b;
    }

    private void navigate() {
        String q = url.getText().toString().trim();
        if (q.isEmpty()) return;
        String target;
        if (q.contains("://")) target = q;
        else if (q.contains(" ") || !q.contains(".")) target = "https://duckduckgo.com/?q=" + Uri.encode(q);
        else target = "https://" + q;
        web.loadUrl(target);
        web.requestFocus();
    }

    @Override public void onClose() {
        web.stopLoading();
        web.destroy();
    }
}
