package com.unixroot.win10.clr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reader for compiled WPF markup (BAML, Baml2006 layout) that emits XAML text; also a .resources reader. Layout ported from dotnet/wpf. */
final class Baml {
    static final class Result { String xaml; String rootType; }

    // ------------------------------------------------------------ .resources container
    static Map<String, byte[]> resources(byte[] b) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try {
            Rd r = new Rd(b);
            if (r.i32() != 0xBEEFCACE) return out;
            r.i32(); int hs = r.i32(); r.pos += hs;
            int ver = r.i32(); int count = r.i32(); int tc = r.i32();
            for (int i = 0; i < tc; i++) r.str();
            if ((r.pos & 7) != 0) r.pos += 8 - (r.pos & 7);
            r.pos += count * 4; // hashes
            int[] nameOff = new int[count];
            for (int i = 0; i < count; i++) nameOff[i] = r.i32();
            int dataStart = r.i32();
            int nameStart = r.pos;
            for (int i = 0; i < count; i++) {
                Rd n = new Rd(b); n.pos = nameStart + nameOff[i];
                int len = n.v7();
                String name = new String(b, n.pos, len, StandardCharsets.UTF_16LE); n.pos += len;
                int doff = n.i32();
                Rd d = new Rd(b); d.pos = dataStart + doff;
                int code = d.v7();
                if (ver >= 2 && (code == 0x20 || code == 0x21)) {
                    int l = d.i32(); byte[] v = new byte[l]; System.arraycopy(b, d.pos, v, 0, l); out.put(name.toLowerCase(), v);
                } else if (ver < 2 || code >= 0x40) {
                    // typed object (v1 or user type): try int32 length + bytes (Stream / byte[] payloads)
                    int l = d.i32(); if (l >= 0 && d.pos + l <= b.length) { byte[] v = new byte[l]; System.arraycopy(b, d.pos, v, 0, l); out.put(name.toLowerCase(), v); }
                }
            }
        } catch (RuntimeException e) { /* partial result */ }
        return out;
    }

    private static final class Rd {
        final byte[] b; int pos;
        Rd(byte[] b) { this.b = b; }
        int u8() { return b[pos++] & 255; }
        int i16() { int v = (short) ((b[pos] & 255) | (b[pos + 1] & 255) << 8); pos += 2; return v; }
        int i32() { int v = (b[pos] & 255) | (b[pos + 1] & 255) << 8 | (b[pos + 2] & 255) << 16 | (b[pos + 3] & 255) << 24; pos += 4; return v; }
        int v7() { int v = 0, s = 0, x; do { x = u8(); v |= (x & 127) << s; s += 7; } while ((x & 128) != 0); return v; }
        String str() { int l = v7(); String s = new String(b, pos, l, StandardCharsets.UTF_8); pos += l; return s; }
    }

    // ------------------------------------------------------------ BAML -> XAML
    private static final class Node {
        String tag; boolean discard, prop, unwrap; String clr;
        final ArrayList<String[]> attrs = new ArrayList<>();
        final StringBuilder body = new StringBuilder();
    }

    private final Rd r;
    private final HashMap<Integer, String> assemblies = new HashMap<>(), strings = new HashMap<>(), typeNames = new HashMap<>();
    private final HashMap<Integer, Object[]> attrInfo = new HashMap<>();
    private final ArrayList<Node> stack = new ArrayList<>();
    private String rootType, rootXaml;
    private Baml(byte[] b) { r = new Rd(b); }

    static Result toXaml(byte[] baml) {
        Baml p = new Baml(baml);
        p.run();
        Result res = new Result(); res.xaml = p.rootXaml; res.rootType = p.rootType;
        return res;
    }

    private String typeName(int id) { if (id < 0) { int k = -id; return k < BamlTables.TYPES.length ? BamlTables.TYPES[k] : "Unknown" + id; } String n = typeNames.get(id); return n == null ? "Unknown" + id : n; }
    private static String simple(String full) { int i = full.lastIndexOf('.'); String s = i < 0 ? full : full.substring(i + 1); int p = s.lastIndexOf('+'); return p < 0 ? s : s.substring(p + 1); }
    private String[] prop(int id) {
        if (id < 0) { int k = -id; if (k < BamlTables.PROP_NAME.length) return new String[]{simple(typeName(BamlTables.PROP_TYPE[k] * -1)), BamlTables.PROP_NAME[k]}; return new String[]{"", "Unknown"}; }
        Object[] a = attrInfo.get(id);
        return a == null ? new String[]{"", "Unknown"} : new String[]{simple(typeName((Integer) a[0])), (String) a[1]};
    }
    private String str(int id) { if (id >= 0) { String s = strings.get(id); return s == null ? "" : s; } return id == -1 ? "Name" : id == -2 ? "Uid" : ""; }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private int recSize() { int off = r.pos; int v = r.v7(); int enc = r.pos - off; return enc == 1 ? v : v - enc + 1; }
    private Node top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    private static final java.util.Set<String> DISCARD_PROPS = new java.util.HashSet<>(java.util.Arrays.asList("Resources", "Triggers", "Style", "Template", "ItemTemplate", "ContentTemplate", "ItemsPanel", "ControlTemplate", "DataContext", "CommandBindings", "InputBindings", "Effect", "RenderTransform", "LayoutTransform", "Clip", "OpacityMask", "ContextMenu", "ToolTip", "Behaviors", "TaskbarItemInfo"));
    private static final java.util.Set<String> UNWRAP_PROPS = new java.util.HashSet<>(java.util.Arrays.asList("Children", "Content", "Items", "Child", "Inlines", "Blocks", "Text", "Header", "Document", "Document"));
    private static final java.util.Set<String> DISCARD_TYPES = new java.util.HashSet<>(java.util.Arrays.asList("Style", "ControlTemplate", "DataTemplate", "HierarchicalDataTemplate", "ResourceDictionary", "Storyboard", "Setter", "Trigger", "DataTrigger", "EventSetter", "ItemsPanelTemplate", "SolidColorBrush", "LinearGradientBrush", "RadialGradientBrush", "ImageBrush", "DropShadowEffect", "BlurEffect", "RotateTransform", "ScaleTransform", "TranslateTransform", "TransformGroup", "Binding", "StaticResourceExtension", "DynamicResourceExtension", "RelativeSource", "ColumnDefinitionCollection", "RowDefinitionCollection"));

    private void push(Node n) { stack.add(n); }
    private void endElement() {
        if (stack.isEmpty()) return;
        Node n = stack.remove(stack.size() - 1);
        Node p = top();
        if (n.discard) return;
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(n.tag);
        for (String[] a : n.attrs) sb.append(' ').append(a[0]).append("=\"").append(esc(a[1])).append('"');
        if (n.body.length() == 0) sb.append("/>"); else sb.append('>').append(n.body).append("</").append(n.tag).append('>');
        if (p == null) rootXaml = sb.toString(); else if (!p.discard) p.body.append(sb);
    }
    private void endProp() {
        if (stack.isEmpty()) return;
        Node n = stack.remove(stack.size() - 1);
        Node p = top();
        if (n.discard || p == null || p.discard) return;
        if (n.unwrap) p.body.append(n.body);
        else if (n.tag.endsWith("Definitions")) {
            p.body.append('<').append(n.tag).append('>').append(n.body).append("</").append(n.tag).append('>');
        } else if (n.body.length() > 0) p.body.append('<').append(n.tag).append('>').append(n.body).append("</").append(n.tag).append('>');
    }
    private void startProp(int id) {
        String[] pn = prop(id);
        Node n = new Node(); n.prop = true; Node p = top();
        n.tag = (p != null ? p.tag : pn[0]) + "." + pn[1];
        n.discard = DISCARD_PROPS.contains(pn[1]) || (p != null && p.discard);
        n.unwrap = UNWRAP_PROPS.contains(pn[1]);
        if (pn[1].equals("RowDefinitions") || pn[1].equals("ColumnDefinitions")) { n.unwrap = false; n.discard = false; n.tag = "Grid." + pn[1]; }
        push(n);
    }
    private void setAttr(String name, String value) {
        Node n = top(); if (n == null || n.discard || n.prop) return;
        n.attrs.add(new String[]{name, value});
    }
    private String attrName(int id) {
        String[] pn = prop(id); Node n = top();
        String owner = pn[0];
        if (owner.equals("Grid") || owner.equals("DockPanel") || owner.equals("Canvas")) return owner + "." + pn[1];
        return pn[1];
    }

    private static String enumText(String prop, int v) {
        switch (prop) {
            case "HorizontalAlignment": case "HorizontalContentAlignment": return new String[]{"Left", "Center", "Right", "Stretch"}[v & 3];
            case "VerticalAlignment": case "VerticalContentAlignment": return new String[]{"Top", "Center", "Bottom", "Stretch"}[v & 3];
            case "Visibility": return new String[]{"Visible", "Hidden", "Collapsed"}[Math.min(2, Math.max(0, v))];
            case "Orientation": return v == 0 ? "Horizontal" : "Vertical";
            case "Dock": return new String[]{"Left", "Top", "Right", "Bottom"}[v & 3];
            case "TextAlignment": return new String[]{"Left", "Right", "Center", "Justify"}[v & 3];
            case "FontWeight": return v >= 700 ? "Bold" : "Normal";
        }
        return null;
    }

    private void run() {
        int hl = r.i32(); r.pos += hl + 12;
        outer:
        while (r.pos < r.b.length) {
            int t = r.u8();
            switch (t) {
                case 1: r.pos += 6; break;
                case 2: break outer;
                case 3: { // ElementStart
                    int id = r.i16(); r.u8();
                    String full = typeName(id); Node n = new Node();
                    n.tag = simple(full); n.clr = full;
                    n.discard = DISCARD_TYPES.contains(n.tag) || (top() != null && top().discard);
                    if (rootType == null) rootType = full;
                    push(n); break;
                }
                case 4: endElement(); break;
                case 5: { // Property
                    recSize(); int id = r.i16(); String v = r.str();
                    String pn = attrName(id); setAttr(pn, v); break;
                }
                case 6: { // PropertyCustom
                    int rs = recSize(); int id = r.i16(); int ser = r.i16() & ~0x4000; int len = rs - 5;
                    byte[] d = new byte[Math.max(0, len)]; System.arraycopy(r.b, r.pos, d, 0, d.length); r.pos += d.length;
                    String[] pn = prop(id); String val = null;
                    if (ser == 744 && d.length >= 1) { // brush
                        if (d[0] == 1 && d.length >= 5) { long argb = (d[1] & 255L) | (d[2] & 255L) << 8 | (d[3] & 255L) << 16 | (d[4] & 255L) << 24; val = String.format("#%08X", argb & 0xFFFFFFFFL); }
                        else if (d[0] == 2) { Rd s = new Rd(d); s.pos = 1; val = s.str(); }
                    } else if (ser == 46 && d.length >= 1) val = d[0] != 0 ? "True" : "False";
                    else if (ser == 195) { int v = 0; for (int i = 0; i < Math.min(4, d.length); i++) v |= (d[i] & 255) << (8 * i); val = enumText(pn[1], v); }
                    else if (ser == 615) { Rd s = new Rd(d); val = s.str(); }
                    if (val != null) setAttr(attrName(id), val);
                    break;
                }
                case 7: case 9: case 11: case 13: startProp(r.i16()); break;
                case 8: case 10: case 12: case 14: endProp(); break;
                case 15: recSize(); r.str(); r.i32(); r.i32(); break;
                case 16: { recSize(); String s = r.str(); Node n = top(); if (n != null && !n.discard) n.body.append(esc(s)); break; }
                case 17: recSize(); { String s = r.str(); r.i16(); Node n = top(); if (n != null && !n.discard && !n.prop) { /* initialization text */ n.body.append(esc(s)); } } break;
                case 20: { recSize(); String prefix = r.str(); String ns = r.str(); int c = r.i16(); r.pos += c * 2; break; }
                case 27: recSize(); r.str(); r.str(); r.i16(); break;
                case 25: { recSize(); String v = r.str(); int sid = r.i16(); String name = str(sid); if (name.equals("Name")) setAttr("Name", v); break; }
                case 28: { recSize(); int id = r.i16(); assemblies.put(id, r.str()); break; }
                case 29: { recSize(); int id = r.i16(); r.i16(); typeNames.put(id, r.str()); break; }
                case 30: { recSize(); int id = r.i16(); r.i16(); typeNames.put(id, r.str()); r.i16(); break; }
                case 31: { recSize(); int id = r.i16(); int owner = r.i16(); r.u8(); attrInfo.put(id, new Object[]{owner, r.str()}); break; }
                case 32: { recSize(); int id = r.i16(); strings.put(id, r.str()); break; }
                case 33: r.i16(); r.i16(); break;
                case 34: r.i16(); r.i16(); break;
                case 35: r.i16(); r.i16(); r.i16(); break;
                case 36: { recSize(); int id = r.i16(); String v = r.str(); r.i16(); setAttr(attrName(id), v); break; }
                case 37: r.i32(); break;
                case 38: recSize(); r.i16(); r.i32(); r.u8(); r.u8(); break;
                case 39: r.i16(); r.u8(); r.i32(); r.u8(); r.u8(); break;
                case 40: { r.i16(); r.u8(); r.i32(); r.u8(); r.u8(); Node n = new Node(); n.tag = "Key"; n.discard = true; push(n); break; }
                case 41: endElement(); break;
                case 42: { Node n = new Node(); n.tag = "Ctor"; n.discard = true; n.prop = true; push(n); break; }
                case 43: if (!stack.isEmpty()) stack.remove(stack.size() - 1); break;
                case 44: r.i16(); break;
                case 45: { int cid = r.i32(); setAttr("cid", String.valueOf(cid)); break; }
                case 46: r.i16(); break;
                case 48: { r.i16(); r.u8(); Node n = new Node(); n.tag = "SR"; n.discard = true; push(n); break; }
                case 49: endElement(); break;
                case 50: r.i16(); break;
                case 51: { recSize(); String s = str(r.i16()); Node n = top(); if (n != null && !n.discard) n.body.append(esc(s)); break; }
                case 52: recSize(); r.str(); r.i16(); break;
                case 53: r.i32(); r.i32(); break;
                case 54: r.i32(); break;
                case 55: r.u8(); r.i16(); break;
                case 56: r.i16(); r.i16(); break;
                default: throw new IllegalStateException("Unsupported BAML record " + t + " at " + (r.pos - 1));
            }
        }
    }
}
