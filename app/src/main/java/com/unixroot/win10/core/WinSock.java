package com.unixroot.win10.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;

/** ws2_32 / winsock emulation on top of java.net, with HTTP-CONNECT and SOCKS proxy support. */
public final class WinSock {
    public static final String SPEC = "WSAStartup:2s,WSACleanup:0s,socket:3s,closesocket:1s,connect:3s,send:4s,recv:4s,bind:3s,listen:2s,accept:3s,shutdown:2s,"
            + "setsockopt:5s,getsockopt:5s,ioctlsocket:3s,WSAGetLastError:0s,WSASetLastError:1s,htons:1s,ntohs:1s,htonl:1s,ntohl:1s,inet_addr:1s,inet_ntoa:1s,"
            + "gethostbyname:1s,getaddrinfo:4s,freeaddrinfo:1s,select:5s,gethostname:2s,sendto:6s,recvfrom:6s,getpeername:3s,getsockname:3s,WSAIoctl:9s,"
            + "__WSAFDIsSet:2s,WSASocketA:6s,WSASocketW:6s,GetAddrInfoW:4s,FreeAddrInfoW:1s,InternetOpenA:5s,InternetOpenW:5s";

    /** Proxy configuration, set by the host app. type is "http", "socks" or "" (direct). */
    public static volatile String proxyType = "", proxyHost = "", proxyUser = "", proxyPass = "";
    public static volatile int proxyPort;

    private static final class Sock { Socket s; ServerSocket srv; InputStream in; OutputStream out; boolean eof; }

    private final HashMap<Integer, Sock> socks = new HashMap<>();
    private final HashMap<Integer, String> fakeHosts = new HashMap<>();
    private int nextSock = 0x100, nextFake = 1, wsaError;

    public static boolean handles(String n) { return SPEC.contains(n + ":") && (SPEC.startsWith(n + ":") || SPEC.contains("," + n + ":")); }

    private static int swap16(int v) { return (v & 0xFF) << 8 | v >> 8 & 0xFF; }
    private static int swap32(int v) { return Integer.reverseBytes(v); }

    private Socket open(String host, int port) throws IOException {
        String t = proxyType;
        if (t.isEmpty() || proxyHost.isEmpty()) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 20000);
            return s;
        }
        if (t.equals("socks")) {
            Socket s = new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort)));
            s.connect(InetSocketAddress.createUnresolved(host, port), 20000);
            return s;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(proxyHost, proxyPort), 20000);
        String req = "CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n";
        if (!proxyUser.isEmpty()) req += "Proxy-Authorization: Basic " + java.util.Base64.getEncoder().encodeToString((proxyUser + ":" + proxyPass).getBytes("UTF-8")) + "\r\n";
        req += "\r\n";
        s.getOutputStream().write(req.getBytes("US-ASCII"));
        s.getOutputStream().flush();
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) { head.append((char) c); if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break; if (head.length() > 8192) break; }
        String first = head.toString().split("\r\n")[0];
        if (!first.contains(" 200")) { s.close(); throw new IOException("proxy refused: " + first); }
        return s;
    }

    private String hostFor(int ip) {
        String h = fakeHosts.get(ip);
        if (h != null) return h;
        return (ip & 0xFF) + "." + (ip >> 8 & 0xFF) + "." + (ip >> 16 & 0xFF) + "." + (ip >>> 24);
    }

    /** Resolves a name to an IPv4 address in network byte order as stored in memory (little-endian int of bytes). */
    private int resolve(String host) {
        try {
            String t = proxyType;
            if (!t.isEmpty() && !proxyHost.isEmpty() && !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                int id = nextFake++;
                int ip = 198 | 18 << 8 | (id >> 8 & 0xFF) << 16 | (id & 0xFF) << 24;
                fakeHosts.put(ip, host);
                return ip;
            }
            byte[] b = InetAddress.getByName(host).getAddress();
            if (b.length != 4) { for (InetAddress a : InetAddress.getAllByName(host)) if (a.getAddress().length == 4) { b = a.getAddress(); break; } }
            if (b.length != 4) return 0;
            return (b[0] & 0xFF) | (b[1] & 0xFF) << 8 | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
        } catch (IOException e) { return 0; }
    }

    private int err(int code) { wsaError = code; return -1; }

    private int staticStr;

    public int call(String n, Emulator e) {
        Memory m = e.mem;
        switch (n) {
            case "WSAStartup": { int d = e.a(1); m.w16(d, 0x0202); m.w16(d + 2, 0x0202); m.putCstr(d + 4, "Win10Emu WinSock"); return 0; }
            case "WSACleanup": return 0;
            case "socket": case "WSASocketA": case "WSASocketW": { int h = nextSock++; socks.put(h, new Sock()); return h; }
            case "closesocket": { Sock s = socks.remove(e.a(0)); if (s == null) return err(10038); try { if (s.s != null) s.s.close(); if (s.srv != null) s.srv.close(); } catch (IOException ex) { /* ignore */ } return 0; }
            case "shutdown": { Sock s = socks.get(e.a(0)); if (s == null) return err(10038); try { if (s.s != null) { if (e.a(1) == 1 || e.a(1) == 2) s.s.shutdownOutput(); } } catch (IOException ex) { /* ignore */ } return 0; }
            case "connect": {
                Sock s = socks.get(e.a(0));
                if (s == null) return err(10038);
                int sa = e.a(1);
                int port = swap16(m.r16(sa + 2));
                int ip = m.r32(sa + 4);
                String host = hostFor(ip);
                try {
                    s.s = open(host, port);
                    s.in = s.s.getInputStream(); s.out = s.s.getOutputStream();
                    return 0;
                } catch (java.net.ConnectException ex) { return err(10061); }
                catch (java.net.SocketTimeoutException ex) { return err(10060); }
                catch (IOException ex) { return err(10061); }
            }
            case "send": {
                Sock s = socks.get(e.a(0));
                if (s == null || s.out == null) return err(10057);
                int len = e.a(2);
                try { s.out.write(m.read(e.a(1), len)); s.out.flush(); return len; } catch (IOException ex) { return err(10054); }
            }
            case "recv": {
                Sock s = socks.get(e.a(0));
                if (s == null || s.in == null) return err(10057);
                int len = e.a(2);
                if (len <= 0) return 0;
                try {
                    byte[] b = new byte[Math.min(len, 65536)];
                    int r = s.in.read(b);
                    if (r < 0) { s.eof = true; return 0; }
                    m.write(e.a(1), b, 0, r);
                    return r;
                } catch (IOException ex) { return err(10054); }
            }
            case "sendto": return err(10045);
            case "recvfrom": return err(10045);
            case "bind": {
                Sock s = socks.get(e.a(0)); if (s == null) return err(10038);
                int sa = e.a(1);
                try { s.srv = new ServerSocket(swap16(m.r16(sa + 2))); return 0; } catch (IOException ex) { return err(10048); }
            }
            case "listen": return 0;
            case "accept": {
                Sock s = socks.get(e.a(0)); if (s == null || s.srv == null) return err(10038);
                try {
                    Socket c = s.srv.accept();
                    Sock ns = new Sock(); ns.s = c; ns.in = c.getInputStream(); ns.out = c.getOutputStream();
                    int h = nextSock++; socks.put(h, ns);
                    return h;
                } catch (IOException ex) { return err(10004); }
            }
            case "setsockopt": case "ioctlsocket": case "WSAIoctl": return 0;
            case "getsockopt": { int p = e.a(3); if (p != 0) m.w32(p, 0); return 0; }
            case "getpeername": case "getsockname": return err(10057);
            case "WSAGetLastError": return wsaError;
            case "WSASetLastError": wsaError = e.a(0); return 0;
            case "htons": case "ntohs": return swap16(e.a(0) & 0xFFFF);
            case "htonl": case "ntohl": return swap32(e.a(0));
            case "inet_addr": {
                String[] p = m.cstr(e.a(0)).split("\\.");
                if (p.length != 4) return -1;
                try { return Integer.parseInt(p[0]) | Integer.parseInt(p[1]) << 8 | Integer.parseInt(p[2]) << 16 | Integer.parseInt(p[3]) << 24; } catch (NumberFormatException ex) { return -1; }
            }
            case "inet_ntoa": {
                int ip = e.a(0);
                if (staticStr == 0) staticStr = e.allocZero(32);
                m.putCstr(staticStr, (ip & 0xFF) + "." + (ip >> 8 & 0xFF) + "." + (ip >> 16 & 0xFF) + "." + (ip >>> 24));
                return staticStr;
            }
            case "gethostname": { m.putCstr(e.a(0), "WIN10-EMU"); return 0; }
            case "gethostbyname": {
                String host = m.cstr(e.a(0));
                int ip = resolve(host);
                if (ip == 0) { wsaError = 11001; return 0; }
                int he = e.allocZero(32), name = e.allocZero(host.length() + 1), list = e.allocZero(8), addr = e.allocZero(4), aliases = e.allocZero(4);
                m.putCstr(name, host); m.w32(addr, ip); m.w32(list, addr); m.w32(he, name); m.w32(he + 4, aliases); m.w16(he + 8, 2); m.w16(he + 10, 4); m.w32(he + 12, list);
                return he;
            }
            case "getaddrinfo": case "GetAddrInfoW": {
                String host = n.equals("getaddrinfo") ? (e.a(0) == 0 ? "127.0.0.1" : m.cstr(e.a(0))) : (e.a(0) == 0 ? "127.0.0.1" : e.wstr(e.a(0)));
                String svc = e.a(1) == 0 ? "0" : n.equals("getaddrinfo") ? m.cstr(e.a(1)) : e.wstr(e.a(1));
                int ip = resolve(host);
                if (ip == 0) return 11001;
                int port;
                try { port = Integer.parseInt(svc); } catch (NumberFormatException ex) { port = svc.equals("https") ? 443 : svc.equals("http") ? 80 : 0; }
                int ai = e.allocZero(32), sa = e.allocZero(16);
                m.w16(sa, 2); m.w16(sa + 2, swap16(port)); m.w32(sa + 4, ip);
                m.w32(ai + 4, 2); m.w32(ai + 8, 1); m.w32(ai + 12, 6); m.w32(ai + 16, 16); m.w32(ai + 24, sa);
                m.w32(e.a(3), ai);
                return 0;
            }
            case "freeaddrinfo": case "FreeAddrInfoW": return 0;
            case "select": {
                // report every listed socket as ready (read: data or EOF; write: always)
                int count = 0;
                for (int k = 0; k < 3; k++) {
                    int set = e.a(1 + k);
                    if (set == 0) continue;
                    int c = m.r32(set);
                    count += c;
                }
                return count;
            }
            case "__WSAFDIsSet": { int set = e.a(1); int c = m.r32(set); for (int i = 0; i < c; i++) if (m.r32(set + 4 + 4 * i) == e.a(0)) return 1; return 0; }
            case "InternetOpenA": case "InternetOpenW": return 0;
        }
        throw new EmuException("Unimplemented API: " + n);
    }
}
