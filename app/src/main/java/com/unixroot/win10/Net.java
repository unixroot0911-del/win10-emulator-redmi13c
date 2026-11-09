package com.unixroot.win10;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;

/** Proxy settings (shared by the browser and the terminal) and a tiny HTTP client. */
final class Net {
    private Net() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("win10", Context.MODE_PRIVATE);
    }

    static String proxySpec(Context c) { return prefs(c).getString("proxy", ""); }

    /** Apply the saved proxy to every WebView in the process. Returns a status message. */
    static String applyWebViewProxy(Context c) {
        String spec = proxySpec(c).trim();
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return "This WebView version does not support proxy override (update Android System WebView).";
        }
        Handler h = new Handler(Looper.getMainLooper());
        java.util.concurrent.Executor ex = h::post;
        try {
            if (spec.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(ex, () -> { });
                return "Proxy disabled (direct connection).";
            }
            String rule = spec.contains("://") ? spec : "http://" + spec;
            ProxyConfig cfg = new ProxyConfig.Builder().addProxyRule(rule).bypassSimpleHostnames().build();
            ProxyController.getInstance().setProxyOverride(cfg, ex, () -> { });
            return "Proxy set: " + rule;
        } catch (RuntimeException e) {
            return "Proxy error: " + e.getMessage();
        }
    }

    private static Proxy javaProxy(String spec) {
        if (spec == null || spec.trim().isEmpty()) return Proxy.NO_PROXY;
        String s = spec.trim();
        boolean socks = s.startsWith("socks");
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        String host = colon > 0 ? s.substring(0, colon) : s;
        int port = 8080;
        if (colon > 0) {
            try { port = Integer.parseInt(s.substring(colon + 1)); } catch (NumberFormatException ignored) { }
        }
        return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    /** Blocking HTTP GET through the configured proxy. Call from a background thread. */
    static String fetch(Context c, String url, int maxBytes) throws Exception {
        final SharedPreferences p = prefs(c);
        final String user = p.getString("proxyUser", ""), pass = p.getString("proxyPass", "");
        if (!user.isEmpty()) {
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) return new PasswordAuthentication(user, pass.toCharArray());
                    return null;
                }
            });
        }
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection(javaProxy(proxySpec(c)));
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "Win10Emulator/1.0");
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while (bo.size() < maxBytes && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        return "HTTP " + code + "\r\n\r\n" + new String(bo.toByteArray(), "UTF-8");
    }
}
