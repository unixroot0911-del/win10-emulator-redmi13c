package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;
import com.unixroot.win10.clr.WinForms.Ctl;

import java.util.ArrayList;
import java.util.HashMap;

/** A WPF subset: element tree, layout panels and XAML loading, rendered through the WinForms control painter. */
public final class Wpf implements Bcl.Ext {
    private static final String WC = "System.Windows.Controls.", W = "System.Windows.", WM = "System.Windows.Media.", WS = "System.Windows.Shapes.";
    static final boolean DEBUG = System.getenv("WPF_DEBUG") != null;
    final Vm vm;
    final WinForms wf;

    /** kind names understood by the layout engine and mapped to painter kinds. */
    private static final HashMap<String, String> PAINT = new HashMap<>();
    private static final java.util.Set<String> PANELS = new java.util.HashSet<>(java.util.Arrays.asList("Grid", "StackPanel", "DockPanel", "Canvas", "WrapPanel", "Border", "ScrollViewer", "UniformGrid", "Viewbox", "UserControl", "ContentControl", "Page", "Expander", "TabItem", "StatusBar", "ToolBar", "Menu", "ListBoxItem", "ComboBoxItem", "Frame", "Popup", "Window"));
    static {
        for (String k : new String[]{"Grid", "StackPanel", "DockPanel", "Canvas", "WrapPanel", "Border", "ScrollViewer", "UniformGrid", "Viewbox", "UserControl", "ContentControl", "Page", "Expander", "TabItem", "StatusBar", "ToolBar", "Menu", "Frame", "Popup"}) PAINT.put(k, "Panel");
        PAINT.put("Window", "Form"); PAINT.put("Button", "Button"); PAINT.put("RepeatButton", "Button"); PAINT.put("ToggleButton", "Button");
        PAINT.put("TextBlock", "Label"); PAINT.put("Label", "Label"); PAINT.put("TextBox", "TextBox"); PAINT.put("PasswordBox", "TextBox"); PAINT.put("RichTextBox", "TextBox");
        PAINT.put("CheckBox", "CheckBox"); PAINT.put("RadioButton", "RadioButton"); PAINT.put("ListBox", "ListBox"); PAINT.put("ListView", "ListBox"); PAINT.put("ComboBox", "ComboBox");
        PAINT.put("ProgressBar", "ProgressBar"); PAINT.put("Slider", "ProgressBar"); PAINT.put("GroupBox", "GroupBox"); PAINT.put("Image", "PictureBox"); PAINT.put("Rectangle", "Panel");
        PAINT.put("Ellipse", "Panel"); PAINT.put("Separator", "Panel"); PAINT.put("TabControl", "Panel"); PAINT.put("DataGrid", "ListBox"); PAINT.put("TreeView", "ListBox");
        PAINT.put("MenuItem", "Label"); PAINT.put("ListBoxItem", "Label"); PAINT.put("ComboBoxItem", "Label"); PAINT.put("TextBlock", "Label");
    }

    public static Wpf install(Vm vm, WinForms wf) {
        for (String k : PAINT.keySet()) {
            String full = ownerOf(k);
            Bcl.OBJECT_BACKED.add(full);
            Bcl.EXTRA_BASES.put(full, k.equals("Window") ? W + "FrameworkElement" : W + "FrameworkElement");
        }
        Bcl.OBJECT_BACKED.add(W + "FrameworkElement"); Bcl.OBJECT_BACKED.add(W + "Application"); Bcl.OBJECT_BACKED.add(W + "UIElement");
        Bcl.EXTRA_BASES.put(W + "Application", "System.Object"); Bcl.EXTRA_BASES.put(W + "FrameworkElement", "System.Object");
        for (String c : new String[]{"RoutedEventArgs", "StartupEventArgs", "ExitEventArgs", "DispatcherUnhandledExceptionEventArgs", "SelectionChangedEventArgs", "TextChangedEventArgs", "MouseButtonEventArgs", "KeyEventArgs", "CancelEventArgs"}) {
            Bcl.OBJECT_BACKED.add(W + c); Bcl.EXTRA_BASES.put(W + c, "System.EventArgs");
        }
        Wpf w = new Wpf(vm, wf);
        vm.bcl.exts.add(w);
        return w;
    }

    private Wpf(Vm vm, WinForms wf) { this.vm = vm; this.wf = wf; }

    static String ownerOf(String kind) {
        switch (kind) {
            case "Window": case "Application": return W + kind;
            case "Rectangle": case "Ellipse": return WS + kind;
            case "Grid": case "StackPanel": case "DockPanel": case "Canvas": case "WrapPanel": case "Border": case "ScrollViewer": case "UniformGrid": case "Viewbox": case "UserControl": case "Page": return WC + kind;
        }
        return WC + kind;
    }

    // ------------------------------------------------------------ element creation
    /** Creates the painter control for a WPF kind and registers default metrics. */
    Ctl make(String kind, ClrObject obj) {
        String paint = PAINT.get(kind);
        if (paint == null) paint = "Panel";
        Ctl c = new Ctl();
        c.kind = paint; c.obj = obj; c.w = 0; c.h = 0;
        c.props.put("wpf", kind);
        if (obj != null) obj.nativeState = c;
        if (kind.equals("Window")) { c.isForm = true; c.visible = false; c.w = 300; c.h = 300; c.cw = 284; c.ch = 261; c.text = ""; }
        return c;
    }

    static String wkind(Ctl c) { Object k = c.props.get("wpf"); return k == null ? "" : (String) k; }

    // ------------------------------------------------------------ property application
    static int parseColor(String v) {
        v = v.trim();
        if (v.startsWith("#")) {
            String h = v.substring(1);
            if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            if (h.length() == 8) h = h.substring(2);
            try { return Integer.parseInt(h, 16); } catch (NumberFormatException e) { return 0; }
        }
        Integer c = WinForms.COLORS.get(v);
        return c == null ? 0 : c;
    }

    static int[] thickness(String v) {
        String[] p = v.trim().split("[ ,]+");
        try {
            if (p.length == 1) { int a = (int) Math.round(Double.parseDouble(p[0])); return new int[]{a, a, a, a}; }
            if (p.length == 2) { int h = (int) Math.round(Double.parseDouble(p[0])), vv = (int) Math.round(Double.parseDouble(p[1])); return new int[]{h, vv, h, vv}; }
            return new int[]{(int) Math.round(Double.parseDouble(p[0])), (int) Math.round(Double.parseDouble(p[1])), (int) Math.round(Double.parseDouble(p[2])), (int) Math.round(Double.parseDouble(p[3]))};
        } catch (RuntimeException e) { return new int[]{0, 0, 0, 0}; }
    }

    /** Applies a XAML attribute / property value (as text) to a control. Returns true if known. */
    boolean applyText(Ctl c, String name, String v) {
        String kind = wkind(c);
        switch (name) {
            case "Width": c.props.put("W", Double.parseDouble(v)); if (c.isForm) { c.w = (int) Double.parseDouble(v); c.cw = c.w - 16; } return true;
            case "Height": c.props.put("H", Double.parseDouble(v)); if (c.isForm) { c.h = (int) Double.parseDouble(v); c.ch = c.h - 39; } return true;
            case "MinWidth": c.props.put("MinW", Double.parseDouble(v)); return true;
            case "MinHeight": c.props.put("MinH", Double.parseDouble(v)); return true;
            case "MaxWidth": c.props.put("MaxW", Double.parseDouble(v)); return true;
            case "MaxHeight": c.props.put("MaxH", Double.parseDouble(v)); return true;
            case "Margin": c.props.put("M", thickness(v)); return true;
            case "Padding": c.props.put("P", thickness(v)); return true;
            case "BorderThickness": { int[] t = thickness(v); c.props.put("bt", t[0]); c.props.put("BT", t); return true; }
            case "BorderBrush": c.props.put("bc", parseColor(v)); return true;
            case "HorizontalAlignment": c.props.put("HA", v); return true;
            case "VerticalAlignment": c.props.put("VA", v); return true;
            case "HorizontalContentAlignment": case "VerticalContentAlignment": c.props.put(name, v); return true;
            case "Background": case "Fill": c.back = parseColor(v); c.props.put("hasBack", 1); return true;
            case "Foreground": case "Stroke": c.fore = parseColor(v); return true;
            case "FontSize": c.fontPx = Math.max(8, (int) Math.round(Double.parseDouble(v) * 1.15)); return true;
            case "FontWeight": c.bold = v.equals("Bold") || v.equals("SemiBold") || v.equals("Black"); return true;
            case "Content": case "Text": case "Header": case "Title": case "Source": case "ToolTip":
                if (name.equals("Title")) { c.text = v; return true; }
                if (name.equals("ToolTip") || name.equals("Source")) return true;
                c.text = v; return true;
            case "IsChecked": c.checked = v.equalsIgnoreCase("true"); return true;
            case "IsEnabled": c.enabled = !v.equalsIgnoreCase("false"); return true;
            case "Visibility": c.visible = v.equals("Visible"); c.props.put("Vis", v); return true;
            case "Orientation": c.props.put("Or", v); return true;
            case "Grid.Row": c.props.put("GR", Integer.parseInt(v)); return true;
            case "Grid.Column": c.props.put("GC", Integer.parseInt(v)); return true;
            case "Grid.RowSpan": c.props.put("GRS", Integer.parseInt(v)); return true;
            case "Grid.ColumnSpan": c.props.put("GCS", Integer.parseInt(v)); return true;
            case "DockPanel.Dock": c.props.put("Dock", v); return true;
            case "DockPanel.LastChildFill": c.props.put("LCF", v); return true;
            case "Canvas.Left": c.props.put("CL", Double.parseDouble(v)); return true;
            case "Canvas.Top": c.props.put("CT", Double.parseDouble(v)); return true;
            case "Name": case "x:Name": c.name = v; return true;
            case "Value": try { c.value = (int) Math.round(Double.parseDouble(v)); } catch (NumberFormatException e) { /* ignore */ } return true;
            case "Minimum": c.min = (int) Math.round(Double.parseDouble(v)); return true;
            case "Maximum": c.max = (int) Math.round(Double.parseDouble(v)); return true;
            case "IsReadOnly": case "TextWrapping": case "AcceptsReturn": if (name.equals("AcceptsReturn")) c.multiline = v.equalsIgnoreCase("true"); return true;
            case "SelectedIndex": c.sel = Integer.parseInt(v); return true;
            case "Tag": c.tag = v; return true;
            case "TextAlignment": c.props.put("TextAlign", v.equals("Center") ? 32 : v.equals("Right") ? 64 : 1); return true;
        }
        return false;
    }

    // ------------------------------------------------------------ XAML text loading
    /** Parses XAML text into a control tree. Event attributes are stored as "on:Event" -> handler name. */
    public Ctl loadXaml(String xaml, ClrObject rootObj) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            org.w3c.dom.Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xaml.getBytes("UTF-8")));
            return build(doc.getDocumentElement(), rootObj, null);
        } catch (Exception e) {
            throw vm.makeThrow("System.Windows.Markup.XamlParseException", String.valueOf(e.getMessage()));
        }
    }

    private static String local(String tag) { int i = tag.lastIndexOf(':'); return i < 0 ? tag : tag.substring(i + 1); }

    private Ctl build(org.w3c.dom.Element e, ClrObject rootObj, Ctl parent) {
        String kind = local(e.getTagName());
        ClrObject obj = rootObj;
        if (obj == null) obj = vm.newObject(vm.nativeType(ownerOf(kind)));
        Ctl c = rootObj != null && rootObj.nativeState instanceof Ctl ? (Ctl) rootObj.nativeState : make(kind, obj);
        if (rootObj != null && c.props.get("wpf") == null) c.props.put("wpf", kind);
        org.w3c.dom.NamedNodeMap at = e.getAttributes();
        for (int i = 0; i < at.getLength(); i++) {
            String n = at.item(i).getNodeName(), v = at.item(i).getNodeValue();
            if (n.startsWith("xmlns")) continue;
            if (n.equals("x:Class")) { c.props.put("xclass", v); continue; }
            if (n.equals("cid")) { c.props.put("cid", Integer.parseInt(v)); continue; }
            if (v.startsWith("{")) { applyMarkup(c, n, v); continue; }
            String ln = n.equals("x:Name") ? "Name" : n;
            if (isEventName(ln)) { c.props.put("on:" + ln, v); continue; }
            try { applyText(c, ln, v); } catch (RuntimeException ex) { /* ignore bad value */ }
        }
        // child elements
        org.w3c.dom.NodeList nl = e.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < nl.getLength(); i++) {
            org.w3c.dom.Node n = nl.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE) text.append(n.getNodeValue());
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element ce = (org.w3c.dom.Element) n;
            String cn = local(ce.getTagName());
            if (cn.indexOf('.') > 0) { propertyElement(c, cn, ce); continue; }
            if (cn.endsWith("Definition") || cn.equals("Setter") || cn.equals("Style") || cn.equals("ControlTemplate")) continue;
            Ctl k = build(ce, null, c);
            if (kind.equals("ComboBox") || kind.equals("ListBox") || kind.equals("ListView")) { c.items.add(k.text); if (k.props.containsKey("sel")) c.sel = c.items.size() - 1; }
            else adopt(c, k);
        }
        String t = text.toString().trim();
        if (!t.isEmpty() && c.text.isEmpty() && !PANELS.contains(kind)) c.text = t;
        if (kind.equals("ComboBox") && c.sel >= 0 && c.sel < c.items.size()) c.text = String.valueOf(c.items.get(c.sel));
        return c;
    }

    private void adopt(Ctl parent, Ctl k) { k.parent = parent; parent.kids.add(k); }

    private void propertyElement(Ctl c, String name, org.w3c.dom.Element pe) {
        String prop = name.substring(name.indexOf('.') + 1);
        if (prop.equals("RowDefinitions") || prop.equals("ColumnDefinitions")) {
            ArrayList<String> defs = new ArrayList<>();
            org.w3c.dom.NodeList nl = pe.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof org.w3c.dom.Element) {
                org.w3c.dom.Element d = (org.w3c.dom.Element) nl.item(i);
                String v = d.getAttribute(prop.startsWith("Row") ? "Height" : "Width");
                defs.add(v.isEmpty() ? "*" : v);
            }
            c.props.put(prop.startsWith("Row") ? "Rows" : "Cols", defs);
            return;
        }
        org.w3c.dom.NodeList nl = pe.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof org.w3c.dom.Element) {
            org.w3c.dom.Element ce = (org.w3c.dom.Element) nl.item(i);
            if (prop.equals("Content") || prop.equals("Children")) { adopt(c, build(ce, null, c)); }
            else if (prop.equals("Background") || prop.equals("Foreground") || prop.equals("Fill") || prop.equals("BorderBrush")) {
                String color = ce.getAttribute("Color");
                if (!color.isEmpty()) applyText(c, prop, color);
            }
        }
    }

    private void applyMarkup(Ctl c, String name, String v) {
        // {Binding ...}, {StaticResource ...} etc. are not evaluated; literal colors in {x:Static} are ignored.
    }

    private static boolean isEventName(String n) {
        switch (n) { case "Click": case "Loaded": case "Closing": case "Closed": case "TextChanged": case "SelectionChanged": case "Checked": case "Unchecked": case "MouseDown": case "MouseUp": case "MouseLeftButtonDown": case "MouseLeftButtonUp": case "KeyDown": case "KeyUp": case "ValueChanged": case "Initialized": case "MouseMove": case "GotFocus": case "LostFocus": case "Unloaded": case "SizeChanged": case "MouseDoubleClick": case "Drop": case "PreviewKeyDown": case "MouseEnter": case "MouseLeave": case "Startup": case "Exit": return true; }
        return false;
    }

    /** Binds "on:Event" handler names found in XAML to methods of the code-behind object. */
    public void bindHandlers(Ctl c, ClrObject target) {
        for (java.util.Map.Entry<String, Object> en : new ArrayList<>(c.props.entrySet())) {
            if (!en.getKey().startsWith("on:")) continue;
            String ev = en.getKey().substring(3), mname = (String) en.getValue();
            for (ClrType t = target.type; t != null && !t.isNative; t = t.base)
                for (ClrMethod m : t.methods) if (m.name.equals(mname) && !m.isStatic) {
                    Delegate d = new Delegate(vm.nativeType("System.EventHandler"), target, m);
                    Delegate old = c.ev.get(ev);
                    c.ev.put(ev, old == null ? d : old.combine(d));
                }
        }
        for (Ctl k : c.kids) bindHandlers(k, target);
    }

    // ------------------------------------------------------------ layout
    private static int dim(Ctl c, String key, int fallback) { Object o = c.props.get(key); return o instanceof Double ? (int) Math.round((Double) o) : fallback; }
    private static int[] m(Ctl c, String key) { Object o = c.props.get(key); return o instanceof int[] ? (int[]) o : new int[]{0, 0, 0, 0}; }
    private static boolean explicitW(Ctl c) { return c.props.get("W") instanceof Double; }
    private static boolean explicitH(Ctl c) { return c.props.get("H") instanceof Double; }
    private static String s(Ctl c, String k, String def) { Object o = c.props.get(k); return o instanceof String ? (String) o : def; }
    private static boolean collapsed(Ctl c) { return "Collapsed".equals(c.props.get("Vis")); }

    private int textW(Ctl c, String t) { int mx = 0; for (String l : t.split("\r?\n")) mx = Math.max(mx, (int) Math.ceil(l.length() * c.fontPx * 0.52)); return mx; }
    private int textH(Ctl c, String t) { return t.split("\r?\n").length * (c.fontPx + 3); }

    /** Desired size excluding margin. */
    private int[] measure(Ctl c, int aw, int ah) {
        String wk = wkind(c);
        int[] mg = m(c, "M"), pd = m(c, "P");
        int[] bt = c.props.get("BT") instanceof int[] ? (int[]) c.props.get("BT") : new int[]{0, 0, 0, 0};
        int w, h;
        int innerW = Math.max(0, aw - mg[0] - mg[2]), innerH = Math.max(0, ah - mg[1] - mg[3]);
        if (c.visible == false && collapsed(c)) return new int[]{0, 0};
        switch (wk) {
            case "StackPanel": {
                boolean v = !"Horizontal".equals(s(c, "Or", "Vertical"));
                int tw = 0, th = 0;
                for (Ctl k : c.kids) { if (collapsed(k)) continue; int[] d = measureOuter(k, v ? innerW : Integer.MAX_VALUE / 4, v ? Integer.MAX_VALUE / 4 : innerH); if (v) { tw = Math.max(tw, d[0]); th += d[1]; } else { tw += d[0]; th = Math.max(th, d[1]); } }
                w = tw; h = th; break;
            }
            case "WrapPanel": { int tw = 0, th = 0; for (Ctl k : c.kids) { int[] d = measureOuter(k, innerW, innerH); tw += d[0]; th = Math.max(th, d[1]); } w = Math.min(tw, innerW); h = th; break; }
            case "Canvas": w = 0; h = 0; break;
            case "DockPanel": {
                int tw = 0, th = 0;
                for (Ctl k : c.kids) { int[] d = measureOuter(k, innerW, innerH); String dk = s(k, "Dock", "Left"); if (dk.equals("Top") || dk.equals("Bottom")) { tw = Math.max(tw, d[0]); th += d[1]; } else { tw += d[0]; th = Math.max(th, d[1]); } }
                w = tw; h = th; break;
            }
            case "Grid": case "UniformGrid": {
                int[][] g = gridSizes(c, innerW, innerH, true);
                w = 0; for (int x : g[0]) w += x; h = 0; for (int x : g[1]) h += x; break;
            }
            case "Border": case "ScrollViewer": case "UserControl": case "ContentControl": case "Window": case "Page": case "Viewbox": case "Expander": case "TabItem": case "Frame": case "Popup": case "ListBoxItem": case "ComboBoxItem": {
                int cw = 0, ch = 0;
                for (Ctl k : c.kids) { int[] d = measureOuter(k, innerW - pd[0] - pd[2] - bt[0] - bt[2], innerH - pd[1] - pd[3] - bt[1] - bt[3]); cw = Math.max(cw, d[0]); ch = Math.max(ch, d[1]); }
                w = cw + pd[0] + pd[2] + bt[0] + bt[2]; h = ch + pd[1] + pd[3] + bt[1] + bt[3]; break;
            }
            case "Button": case "ToggleButton": case "RepeatButton": {
                if (!c.kids.isEmpty()) { int[] d = measureOuter(c.kids.get(0), innerW, innerH); w = d[0] + 16; h = d[1] + 8; }
                else { w = textW(c, c.text) + (pd[0] + pd[2] > 0 ? pd[0] + pd[2] : 20); h = textH(c, c.text) + (pd[1] + pd[3] > 0 ? pd[1] + pd[3] : 8); }
                break;
            }
            case "TextBlock": case "Label": case "MenuItem": w = textW(c, c.text) + pd[0] + pd[2]; h = textH(c, c.text) + pd[1] + pd[3]; break;
            case "TextBox": case "PasswordBox": w = Math.max(textW(c, c.text) + 8, 20); h = textH(c, c.text) + 8; break;
            case "CheckBox": case "RadioButton": w = textW(c, c.text) + 22; h = Math.max(18, c.fontPx + 4); break;
            case "ListBox": case "ListView": case "ComboBox": case "DataGrid": case "TreeView": {
                int mw = textW(c, c.text); for (Object it : c.items) mw = Math.max(mw, textW(c, String.valueOf(it)));
                w = mw + 24; h = wk.equals("ComboBox") ? c.fontPx + 10 : Math.max(60, c.items.size() * 16 + 4); break;
            }
            case "ProgressBar": case "Slider": w = 100; h = 18; break;
            case "GroupBox": { int cw = 0, ch = 0; for (Ctl k : c.kids) { int[] d = measureOuter(k, innerW, innerH); cw = Math.max(cw, d[0]); ch = Math.max(ch, d[1]); } w = cw + 12; h = ch + 24; break; }
            case "TabControl": { int cw = 0, ch = 0; for (Ctl k : c.kids) { int[] d = measureOuter(k, innerW, innerH); cw = Math.max(cw, d[0]); ch = Math.max(ch, d[1]); } w = cw; h = ch + 26; break; }
            case "Image": case "Rectangle": case "Ellipse": w = 0; h = 0; break;
            case "Separator": w = 1; h = 1; break;
            default: w = 0; h = 0;
        }
        if (explicitW(c)) w = dim(c, "W", w);
        if (explicitH(c)) h = dim(c, "H", h);
        w = Math.max(w, dim(c, "MinW", 0)); h = Math.max(h, dim(c, "MinH", 0));
        if (c.props.get("MaxW") instanceof Double) w = Math.min(w, dim(c, "MaxW", w));
        if (c.props.get("MaxH") instanceof Double) h = Math.min(h, dim(c, "MaxH", h));
        return new int[]{w, h};
    }

    private int[] measureOuter(Ctl c, int aw, int ah) {
        if (collapsed(c)) return new int[]{0, 0};
        int[] mg = m(c, "M");
        int[] d = measure(c, aw, ah);
        c.props.put("DW", d[0]); c.props.put("DH", d[1]);
        return new int[]{d[0] + mg[0] + mg[2], d[1] + mg[1] + mg[3]};
    }

    private static final class Def { int kind; double v; int size; } // kind 0 px,1 auto,2 star

    private ArrayList<Def> defs(Ctl c, String key, int count) {
        ArrayList<Def> out = new ArrayList<>();
        Object o = c.props.get(key);
        if (o instanceof ArrayList) for (Object x : (ArrayList<?>) o) {
            String t = ((String) x).trim(); Def d = new Def();
            if (t.equalsIgnoreCase("Auto")) d.kind = 1;
            else if (t.endsWith("*")) { d.kind = 2; String n = t.substring(0, t.length() - 1); d.v = n.isEmpty() ? 1 : Double.parseDouble(n); }
            else { d.kind = 0; try { d.v = Double.parseDouble(t); } catch (NumberFormatException e) { d.kind = 1; } }
            out.add(d);
        }
        if (out.isEmpty()) { Def d = new Def(); d.kind = 2; d.v = 1; out.add(d); }
        return out;
    }

    private int[][] gridSizes(Ctl c, int aw, int ah, boolean forMeasure) {
        ArrayList<Def> cols = defs(c, "Cols", 1), rows = defs(c, "Rows", 1);
        if (wkind(c).equals("UniformGrid")) { int n = c.kids.size(); int cc = Math.max(1, (int) Math.ceil(Math.sqrt(n))); cols.clear(); rows.clear(); for (int i = 0; i < cc; i++) { Def d = new Def(); d.kind = 2; d.v = 1; cols.add(d); } for (int i = 0; i < (n + cc - 1) / cc; i++) { Def d = new Def(); d.kind = 2; d.v = 1; rows.add(d); } for (int i = 0; i < c.kids.size(); i++) { c.kids.get(i).props.put("GC", i % cc); c.kids.get(i).props.put("GR", i / cc); } }
        int[][] res = new int[2][];
        for (int pass = 0; pass < 2; pass++) {
            ArrayList<Def> ds = pass == 0 ? cols : rows;
            for (Def d : ds) d.size = d.kind == 0 ? (int) d.v : 0;
            for (Ctl k : c.kids) {
                if (collapsed(k)) continue;
                int idx = Math.min(ds.size() - 1, Math.max(0, k.props.get(pass == 0 ? "GC" : "GR") instanceof Integer ? (Integer) k.props.get(pass == 0 ? "GC" : "GR") : 0));
                int span = k.props.get(pass == 0 ? "GCS" : "GRS") instanceof Integer ? (Integer) k.props.get(pass == 0 ? "GCS" : "GRS") : 1;
                int[] d = measureOuter(k, aw, ah);
                if (span == 1 && ds.get(idx).kind != 0) ds.get(idx).size = Math.max(ds.get(idx).size, ds.get(idx).kind == 1 || forMeasure ? d[pass] : 0);
            }
            if (!forMeasure) {
                int fixed = 0; double stars = 0;
                for (Def d : ds) { if (d.kind != 2) fixed += d.size; else stars += d.v; }
                int rem = Math.max(0, (pass == 0 ? aw : ah) - fixed);
                for (Def d : ds) if (d.kind == 2) d.size = stars == 0 ? 0 : (int) Math.round(rem * d.v / stars);
            }
            res[pass] = new int[ds.size()];
            for (int i = 0; i < ds.size(); i++) res[pass][i] = ds.get(i).size;
        }
        return res;
    }

    /** Positions child within the slot respecting alignment and explicit sizes. */
    private void place(Ctl k, int sx, int sy, int sw, int sh) {
        int[] mg = m(k, "M");
        int dw = k.props.get("DW") instanceof Integer ? (Integer) k.props.get("DW") : 0, dh = k.props.get("DH") instanceof Integer ? (Integer) k.props.get("DH") : 0;
        int availW = Math.max(0, sw - mg[0] - mg[2]), availH = Math.max(0, sh - mg[1] - mg[3]);
        String ha = s(k, "HA", "Stretch"), va = s(k, "VA", "Stretch");
        int w = ha.equals("Stretch") && !explicitW(k) ? availW : Math.min(dw, availW > 0 ? Math.max(availW, dw) : dw);
        int h = va.equals("Stretch") && !explicitH(k) ? availH : dh;
        if (ha.equals("Stretch") && explicitW(k)) w = dw;
        if (va.equals("Stretch") && explicitH(k)) h = dh;
        if (c0(k)) { w = Math.min(w, availW > 0 ? availW : w); }
        int x = sx + mg[0] + (ha.equals("Center") ? (availW - w) / 2 : ha.equals("Right") ? availW - w : 0);
        int y = sy + mg[1] + (va.equals("Center") ? (availH - h) / 2 : va.equals("Bottom") ? availH - h : 0);
        k.x = x; k.y = y; k.w = Math.max(0, w); k.h = Math.max(0, h);
        k.dock = 0;
        arrange(k);
    }
    private boolean c0(Ctl k) { return false; }

    /** Lays out children of c within its already-set w/h. */
    private void arrange(Ctl c) {
        String wk = wkind(c);
        int[] pd = m(c, "P");
        int[] bt = c.props.get("BT") instanceof int[] ? (int[]) c.props.get("BT") : new int[]{0, 0, 0, 0};
        int ox = pd[0] + bt[0], oy = pd[1] + bt[1], iw = Math.max(0, c.w - pd[0] - pd[2] - bt[0] - bt[2]), ih = Math.max(0, c.h - pd[1] - pd[3] - bt[1] - bt[3]);
        if (c.isForm) { iw = c.cw; ih = c.ch; ox = 0; oy = 0; }
        switch (wk) {
            case "StackPanel": {
                boolean v = !"Horizontal".equals(s(c, "Or", "Vertical"));
                int pos = 0;
                for (Ctl k : c.kids) {
                    if (collapsed(k)) { k.visible = false; continue; }
                    int[] mg = m(k, "M"); int dw = (Integer) k.props.get("DW"), dh = (Integer) k.props.get("DH");
                    if (v) { place(k, ox, oy + pos, iw, dh + mg[1] + mg[3]); pos += dh + mg[1] + mg[3]; }
                    else { place(k, ox + pos, oy, dw + mg[0] + mg[2], ih); pos += dw + mg[0] + mg[2]; }
                }
                break;
            }
            case "WrapPanel": {
                int x = 0, y = 0, rowH = 0;
                for (Ctl k : c.kids) { int[] d = measureOuter(k, iw, ih); if (x + d[0] > iw && x > 0) { x = 0; y += rowH; rowH = 0; } place(k, ox + x, oy + y, d[0], d[1]); x += d[0]; rowH = Math.max(rowH, d[1]); }
                break;
            }
            case "Canvas": {
                for (Ctl k : c.kids) { int[] d = measureOuter(k, iw, ih); k.x = ox + dim(k, "CL", 0); k.y = oy + dim(k, "CT", 0); k.w = d[0] - m(k, "M")[0] - m(k, "M")[2]; k.h = d[1] - m(k, "M")[1] - m(k, "M")[3]; arrange(k); }
                break;
            }
            case "DockPanel": {
                int l = 0, t = 0, r = iw, b = ih;
                for (int i = 0; i < c.kids.size(); i++) {
                    Ctl k = c.kids.get(i); int[] d = measureOuter(k, iw, ih);
                    boolean last = i == c.kids.size() - 1 && !"False".equalsIgnoreCase(s(c, "LCF", "True"));
                    String dk = s(k, "Dock", "Left");
                    if (last) { place(k, ox + l, oy + t, r - l, b - t); break; }
                    switch (dk) {
                        case "Top": place(k, ox + l, oy + t, r - l, d[1]); t += d[1]; break;
                        case "Bottom": place(k, ox + l, oy + b - d[1], r - l, d[1]); b -= d[1]; break;
                        case "Right": place(k, ox + r - d[0], oy + t, d[0], b - t); r -= d[0]; break;
                        default: place(k, ox + l, oy + t, d[0], b - t); l += d[0];
                    }
                }
                break;
            }
            case "Grid": case "UniformGrid": {
                int[][] g = gridSizes(c, iw, ih, false);
                int[] cx = new int[g[0].length + 1], ry = new int[g[1].length + 1];
                for (int i = 0; i < g[0].length; i++) cx[i + 1] = cx[i] + g[0][i];
                for (int i = 0; i < g[1].length; i++) ry[i + 1] = ry[i] + g[1][i];
                for (Ctl k : c.kids) {
                    if (collapsed(k)) { k.visible = false; continue; }
                    int ci = Math.min(g[0].length - 1, k.props.get("GC") instanceof Integer ? (Integer) k.props.get("GC") : 0), ri = Math.min(g[1].length - 1, k.props.get("GR") instanceof Integer ? (Integer) k.props.get("GR") : 0);
                    int cs = Math.min(g[0].length - ci, k.props.get("GCS") instanceof Integer ? (Integer) k.props.get("GCS") : 1), rs = Math.min(g[1].length - ri, k.props.get("GRS") instanceof Integer ? (Integer) k.props.get("GRS") : 1);
                    measureOuter(k, cx[ci + cs] - cx[ci], ry[ri + rs] - ry[ri]);
                    place(k, ox + cx[ci], oy + ry[ri], cx[ci + cs] - cx[ci], ry[ri + rs] - ry[ri]);
                }
                break;
            }
            case "GroupBox": for (Ctl k : c.kids) { measureOuter(k, iw - 12, ih - 24); place(k, 6, 18, iw - 0, ih - 20); } break;
            case "TabControl": { boolean first = true; for (Ctl k : c.kids) { k.visible = first; first = false; measureOuter(k, iw, ih - 26); place(k, 0, 26, iw, ih - 26); } break; }
            case "Button": case "ToggleButton": case "RepeatButton": for (Ctl k : c.kids) { measureOuter(k, iw, ih); place(k, 0, 0, c.w, c.h); k.props.put("HA", "Center"); } break;
            default:
                for (Ctl k : c.kids) { if (collapsed(k)) { k.visible = false; continue; } measureOuter(k, iw, ih); place(k, ox, oy, iw, ih); }
        }
    }

    /** Computes layout for a window's content area. Call before painting. */
    public void layoutWindow(Ctl win) {
        measureOuter(win, win.cw, win.ch);
        win.dock = 0;
        arrange(win);
    }


    // ------------------------------------------------------------ compiled XAML (BAML) loading
    private byte[] findBaml(String uri) {
        String u = uri.replace('\\', '/');
        int i = u.indexOf(";component/");
        if (i >= 0) u = u.substring(i + 11);
        while (u.startsWith("/")) u = u.substring(1);
        String key = u.toLowerCase();
        if (key.endsWith(".xaml")) key = key.substring(0, key.length() - 5) + ".baml";
        for (Model.Image img : new Model.Image[]{vm.main}) {
            if (img == null) continue;
            for (String rn : img.md.managedResourceNames()) {
                if (!rn.toLowerCase().endsWith(".g.resources") && !rn.toLowerCase().endsWith(".resources")) continue;
                byte[] blob = img.md.managedResource(rn);
                if (blob == null) continue;
                byte[] b = Baml.resources(blob).get(key);
                if (b != null) return b;
            }
        }
        return null;
    }

    void loadComponent(ClrObject target, String uri) {
        byte[] baml = findBaml(uri);
        if (baml == null) throw vm.makeThrow("System.IO.IOException", "Cannot locate resource '" + uri + "'.");
        Baml.Result res;
        try { res = Baml.toXaml(baml); }
        catch (RuntimeException e) { throw vm.makeThrow("System.Windows.Markup.XamlParseException", "BAML read failed for " + uri + ": " + e.getMessage()); }
        if (System.getenv("WPF_DEBUG") != null) System.err.println(res.xaml);
        Ctl root = loadXaml(res.xaml, target);
        connect(root, target);
    }

    private void connect(Ctl c, ClrObject target) {
        Object cid = c.props.get("cid");
        if (cid instanceof Integer) callConnect(target, (Integer) cid, c.obj);
        for (Ctl k : new ArrayList<>(c.kids)) connect(k, target);
    }

    private void callConnect(ClrObject target, int id, Object el) {
        for (ClrType t = target.type; t != null && !t.isNative; t = t.base)
            for (ClrMethod m : t.methods)
                if (!m.isStatic && m.nparams == 2 && (m.name.equals("System.Windows.Markup.IComponentConnector.Connect") || m.name.equals("Connect"))) { vm.invoke(m, new Object[]{target, id, el}); return; }
    }

    /** Creates and shows the window named by Application.StartupUri. Returns its object or null. */
    ClrObject createStartupWindow(String uri) {
        byte[] baml = findBaml(uri);
        if (baml == null) return null;
        Baml.Result res = Baml.toXaml(baml);
        Model.Image img = vm.main;
        Integer row = img.typeByName.get(res.rootType);
        if (row == null) return null;
        ClrType t = vm.typeDef(img, row);
        vm.ensureInit(t);
        ClrObject o = vm.newObject(t);
        for (ClrMethod m : t.methods) if (m.name.equals(".ctor") && m.nparams == 0 && !m.isStatic) { vm.invoke(m, new Object[]{o}); break; }
        return o;
    }

    // ------------------------------------------------------------ Ext (code-behind API)
    @Override public Object getStatic(Vm vm, FieldInfo f) {
        String t = f.owner.full, n = f.name;
        if (t.startsWith(W) && !t.startsWith(W + "Forms")) {
            if (t.equals(WM + "Brushes") || t.equals(WM + "Colors")) { Integer c = WinForms.COLORS.get(n); if (c != null) return c; }
            return 0;
        }
        return Bcl.NONE;
    }

    private static boolean ours(String owner) { return owner.startsWith(W) && !owner.startsWith(W + "Forms") && !owner.startsWith(W + "Markup") || owner.startsWith(WC) || owner.startsWith(WM) || owner.startsWith(WS); }

    @Override public Object call(Vm vm, ClrMethod m, Object[] a) {
        String owner = m.owner == null ? "" : m.owner.full, n = m.name;
        if (DEBUG && owner.startsWith("System.Windows")) System.err.println("[wpf] " + owner + "::" + n);
        Object recv = m.hasThis && a.length > 0 ? (a[0] instanceof Ref ? ((Ref) a[0]).get() : a[0]) : null;
        if (recv instanceof Disp) return onDisp((Disp) recv, n, a, false);
        if (owner.equals(W + "Threading.Dispatcher") && !m.hasThis) { switch (n) { case "get_CurrentDispatcher": case "FromThread": return disp; case "Run": case "ExitAllFrames": return onDisp(disp, n, a, true); } }
        if (recv instanceof ResDict) return onRes((ResDict) recv, n, a);
        if (recv instanceof ClrObject && ((ClrObject) recv).nativeState instanceof ResDict) return onRes((ResDict) ((ClrObject) recv).nativeState, n, a);
        if (n.equals(".ctor") && owner.equals(W + "ResourceDictionary")) {
            if (a[0] == null) return new ResDict();
            ((ClrObject) a[0]).nativeState = new ResDict(); return null;
        }
        if ((recv instanceof Integer || recv instanceof int[]) && m.hasThis && ours(owner)) {
            switch (n) {
                case "Freeze": case "set_Opacity": case "set_Transform": return null;
                case "CanFreeze": return 1; case "get_IsFrozen": return 1;
                case "get_Color": case "Clone": case "CloneCurrentValue": return recv;
                case "get_Opacity": return 1.0;
                case "get_R": return (byte) (((Integer) recv >> 16) & 255); case "get_G": return (byte) (((Integer) recv >> 8) & 255); case "get_B": return (byte) ((Integer) recv & 255); case "get_A": return (byte) 255;
            }
        }
        Ctl c = recv instanceof ClrObject && ((ClrObject) recv).nativeState instanceof Ctl ? (Ctl) ((ClrObject) recv).nativeState : null;
        boolean wpfObj = c != null && c.props.containsKey("wpf");
        if (!ours(owner) && !wpfObj) return Bcl.NONE;
        if (n.equals(".ctor")) {
            if (a[0] instanceof ClrObject) {
                String kind = owner.substring(owner.lastIndexOf('.') + 1);
                if (PAINT.containsKey(kind) || kind.equals("FrameworkElement") || kind.equals("UIElement")) { if (((ClrObject) a[0]).nativeState == null) make(PAINT.containsKey(kind) ? kind : "Border", (ClrObject) a[0]); return null; }
                if (kind.equals("Application")) { Ctl app = new Ctl(); app.kind = "App"; app.props.put("wpf", "Application"); ((ClrObject) a[0]).nativeState = app; wf.currentApp = (ClrObject) a[0]; return null; }
                if (((ClrObject) a[0]).nativeState == null) ((ClrObject) a[0]).nativeState = new WinForms.Props(kind);
                return null;
            }
            if (a[0] == null) {
                String kind = owner.substring(owner.lastIndexOf('.') + 1);
                if (owner.equals(W + "Application") || (PAINT.containsKey(kind) && !owner.equals(W + "Uri"))) {
                    ClrObject o = vm.newObject(vm.nativeType(owner));
                    if (kind.equals("Application")) { Ctl app = new Ctl(); app.kind = "App"; app.props.put("wpf", "Application"); o.nativeState = app; wf.currentApp = o; }
                    else make(kind, o);
                    return o;
                }
                switch (owner) {
                    case W + "Thickness": { Object[] v = new Object[]{a[1], a.length > 2 ? a[2] : a[1], a.length > 3 ? a[3] : a[1], a.length > 4 ? a[4] : a[1]}; int[] t = new int[4]; for (int i = 0; i < 4; i++) t[i] = (int) Math.round(((Number) v[i]).doubleValue()); return t; }
                    case W + "Uri": case "System.Uri": return a[1];
                    case WM + "SolidColorBrush": return a[1] instanceof Integer ? a[1] : (a[1] instanceof WinForms.Col ? (Object) ((WinForms.Col) a[1]).rgb() : (Object) 0);
                    case WM + "Color": return 0;
                }
                return new WinForms.Props(kind);
            }
        }
        if (!m.hasThis) return statics(m, owner, n, a);
        if (recv instanceof int[]) return thicknessOp((int[]) recv, n, a);
        if (c == null) return Bcl.NONE;
        return onElement(c, (ClrObject) recv, n, a);
    }

    private Object thicknessOp(int[] t, String n, Object[] a) {
        switch (n) { case "get_Left": return (double) t[0]; case "get_Top": return (double) t[1]; case "get_Right": return (double) t[2]; case "get_Bottom": return (double) t[3]; }
        return Bcl.NONE;
    }

    private Object statics(ClrMethod m, String owner, String n, Object[] a) {
        switch (owner + "::" + n) {
            case W + "Application::get_Current": return wf.currentApp;
            case W + "Application::LoadComponent": { if (a.length < 2 || !(a[0] instanceof ClrObject)) return Bcl.NONE; loadComponent((ClrObject) a[0], String.valueOf(a[1])); return null; }
            case WM + "Color::FromRgb": return (num(a[0]) & 255) << 16 | (num(a[1]) & 255) << 8 | (num(a[2]) & 255);
            case WM + "Color::FromArgb": return (num(a[1]) & 255) << 16 | (num(a[2]) & 255) << 8 | (num(a[3]) & 255);
            case WM + "ColorConverter::ConvertFromString": return parseColor((String) a[0]);
            case WC + "Grid::SetRow": { Ctl k = ctl(a[0]); if (k != null) k.props.put("GR", num(a[1])); return null; }
            case WC + "Grid::SetColumn": { Ctl k = ctl(a[0]); if (k != null) k.props.put("GC", num(a[1])); return null; }
            case WC + "Grid::SetRowSpan": { Ctl k = ctl(a[0]); if (k != null) k.props.put("GRS", num(a[1])); return null; }
            case WC + "Grid::SetColumnSpan": { Ctl k = ctl(a[0]); if (k != null) k.props.put("GCS", num(a[1])); return null; }
            case WC + "Canvas::SetLeft": { Ctl k = ctl(a[0]); if (k != null) k.props.put("CL", ((Number) a[1]).doubleValue()); return null; }
            case WC + "Canvas::SetTop": { Ctl k = ctl(a[0]); if (k != null) k.props.put("CT", ((Number) a[1]).doubleValue()); return null; }
            case WC + "DockPanel::SetDock": { Ctl k = ctl(a[0]); if (k != null) k.props.put("Dock", new String[]{"Left", "Top", "Right", "Bottom"}[num(a[1]) & 3]); return null; }
            case W + "MessageBox::Show": {
                String text = a.length > 0 && a[0] instanceof String ? (String) a[0] : "", cap = a.length > 1 && a[1] instanceof String ? (String) a[1] : "";
                vm.console.messageBox(cap, text); return 1;
            }
        }
        return Bcl.NONE;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
    private Ctl ctl(Object o) { return o instanceof ClrObject && ((ClrObject) o).nativeState instanceof Ctl ? (Ctl) ((ClrObject) o).nativeState : null; }

    private Object onElement(Ctl c, ClrObject self, String n, Object[] a) {
        if (c.kind.equals("App")) return onApp(c, self, n, a);
        if (n.startsWith("add_")) {
            String e = n.substring(4);
            if (e.equals("MouseLeftButtonDown") || e.equals("MouseDown")) e = "MouseLeftButtonDown";
            Delegate d = (Delegate) a[1]; Delegate old = c.ev.get(e); c.ev.put(e, old == null ? d : old.combine(d)); return null;
        }
        if (n.startsWith("remove_")) { return null; }
        switch (n) {
            case "InitializeComponent": return null;
            case "get_Content": return c.kids.isEmpty() ? (Object) c.text : c.kids.get(0).obj;
            case "set_Content": case "set_Header": {
                Object v = a[1];
                if (v instanceof ClrObject && ((ClrObject) v).nativeState instanceof Ctl) { for (Ctl k : c.kids) k.parent = null; c.kids.clear(); wf.addChild(c, (Ctl) ((ClrObject) v).nativeState); }
                else c.text = v == null ? "" : vm.bcl.str(v);
                wf.dirty(c); return null;
            }
            case "get_Text": return c.text; case "set_Text": c.text = a[1] == null ? "" : (String) a[1]; wf.fire(c, "TextChanged", null); wf.dirty(c); return null;
            case "get_Title": return c.text; case "set_Title": c.text = (String) a[1]; if (c.hw != null) c.hw.setTitle(c.text); return null;
            case "get_Children": return new ChildList(c);
            case "get_Items": return new WinForms.ItemList(c);
            case "get_IsChecked": return c.checked ? 1 : 0; case "set_IsChecked": c.checked = a[1] != null && num(a[1]) != 0; wf.dirty(c); return null;
            case "get_IsEnabled": return c.enabled ? 1 : 0; case "set_IsEnabled": c.enabled = num(a[1]) != 0; wf.dirty(c); return null;
            case "get_Visibility": return c.visible ? 0 : 2; case "set_Visibility": { int v = num(a[1]); c.visible = v == 0; c.props.put("Vis", v == 0 ? "Visible" : v == 1 ? "Hidden" : "Collapsed"); wf.dirty(c); return null; }
            case "get_Width": return c.props.get("W") instanceof Double ? c.props.get("W") : (Object) (double) c.w; case "set_Width": c.props.put("W", ((Number) a[1]).doubleValue()); if (c.isForm) { c.w = num(a[1]); c.cw = c.w - 16; } return null;
            case "get_Height": return c.props.get("H") instanceof Double ? c.props.get("H") : (Object) (double) c.h; case "set_Height": c.props.put("H", ((Number) a[1]).doubleValue()); if (c.isForm) { c.h = num(a[1]); c.ch = c.h - 39; } return null;
            case "get_ActualWidth": return (double) c.w; case "get_ActualHeight": return (double) c.h;
            case "set_Margin": c.props.put("M", a[1] instanceof int[] ? a[1] : new int[]{0, 0, 0, 0}); wf.dirty(c); return null;
            case "set_Padding": c.props.put("P", a[1] instanceof int[] ? a[1] : new int[]{0, 0, 0, 0}); wf.dirty(c); return null;
            case "set_HorizontalAlignment": c.props.put("HA", new String[]{"Left", "Center", "Right", "Stretch"}[num(a[1]) & 3]); wf.dirty(c); return null;
            case "set_VerticalAlignment": c.props.put("VA", new String[]{"Top", "Center", "Bottom", "Stretch"}[num(a[1]) & 3]); wf.dirty(c); return null;
            case "set_Background": case "set_Fill": if (a[1] instanceof Integer) c.back = (Integer) a[1]; wf.dirty(c); return null;
            case "set_Foreground": if (a[1] instanceof Integer) c.fore = (Integer) a[1]; wf.dirty(c); return null;
            case "set_FontSize": c.fontPx = Math.max(8, (int) Math.round(((Number) a[1]).doubleValue() * 1.15)); wf.dirty(c); return null;
            case "set_FontWeight": case "set_FontFamily": case "set_ToolTip": case "set_Cursor": case "set_Style": case "set_Icon": case "set_ResizeMode": case "set_WindowStartupLocation": case "set_Topmost": case "set_ShowInTaskbar": case "set_SizeToContent": case "set_WindowStyle": return null;
            case "set_Orientation": c.props.put("Or", num(a[1]) == 1 ? "Horizontal" : "Vertical"); return null;
            case "set_Name": c.name = (String) a[1]; return null; case "get_Name": return c.name;
            case "get_Tag": return c.tag; case "set_Tag": c.tag = a[1]; return null;
            case "get_SelectedIndex": return c.sel; case "set_SelectedIndex": c.sel = num(a[1]); if (c.sel >= 0 && c.sel < c.items.size() && c.kind.equals("ComboBox")) c.text = vm.bcl.str(c.items.get(c.sel)); wf.dirty(c); return null;
            case "get_SelectedItem": return c.sel >= 0 && c.sel < c.items.size() ? c.items.get(c.sel) : null;
            case "get_Value": return (double) c.value; case "set_Value": c.value = (int) Math.round(((Number) a[1]).doubleValue()); wf.dirty(c); return null;
            case "set_Minimum": c.min = num(a[1]); return null; case "set_Maximum": c.max = num(a[1]); return null;
            case "Show": case "Activate": wf.showForm(c); return null;
            case "ShowDialog": return wf.showDialog(c) instanceof Integer && (Integer) wf.showDialog(c) != 0 ? 1 : 0;
            case "Close": wf.closeForm(c); return null;
            case "Hide": wf.hideForm(c); return null;
            case "get_DialogResult": return c.dialogResult == 1 ? 1 : c.dialogResult == 0 ? null : (Object) 0; case "set_DialogResult": c.dialogResult = a[1] == null ? 0 : (num(a[1]) != 0 ? 1 : 2); wf.closeForm(c); return null;
            case "FindName": { Ctl f = find(c, (String) a[1]); return f == null ? null : f.obj; }
            case "Focus": wf.dirty(c); return 1;
            case "get_DataContext": return c.props.get("dc"); case "set_DataContext": c.props.put("dc", a[1]); return null;
            case "UpdateLayout": case "InvalidateVisual": case "BeginInit": case "EndInit": wf.dirty(c); return null;
            case "get_Parent": return c.parent == null ? null : c.parent.obj;
            case "get_Dispatcher": return disp;
            case "get_IsLoaded": return 1;
            case "get_Resources": return resOf(c);
            case "set_Resources": if (a[1] instanceof ResDict) c.props.put("res", a[1]); return null;
            case "FindResource": { Object v = findRes(c, a[1]); if (v == null) throw vm.makeThrow("System.Windows.ResourceReferenceKeyNotFoundException", "Cannot find resource named '" + a[1] + "'."); return v; }
            case "TryFindResource": return findRes(c, a[1]);
            case "GetHashCode": return System.identityHashCode(c);
            case "Equals": return c.obj == a[1] ? 1 : 0;
            case "ToString": return W + "Controls." + wkind(c);
        }
        if (n.startsWith("set_") || n.equals("BeginInit") || n.equals("EndInit")) return null;
        return Bcl.NONE;
    }

    private Ctl find(Ctl c, String name) { if (name.equals(c.name)) return c; for (Ctl k : c.kids) { Ctl r = find(k, name); if (r != null) return r; } return null; }

    private Object onApp(Ctl c, ClrObject self, String n, Object[] a) {
        switch (n) {
            case "Run": { Ctl w = a.length > 1 ? ctl(a[1]) : null; if (w == null && wf.mainWindowObj == null && c.props.get("StartupUri") != null) wf.mainWindowObj = createStartupWindow((String) c.props.get("StartupUri")); if (w == null && wf.mainWindowObj != null) w = ctl(wf.mainWindowObj); if (w != null) wf.runLoop(w); return 0; }
            case "Shutdown": wf.quit = true; return null;
            case "get_MainWindow": return wf.mainWindowObj; case "set_MainWindow": wf.mainWindowObj = (ClrObject) a[1]; return null;
            case "get_Resources": return resOf(c);
            case "set_Resources": if (a[1] instanceof ResDict) c.props.put("res", a[1]); return null;
            case "FindResource": { Object v = findRes(c, a[1]); if (v == null) throw vm.makeThrow("System.Windows.ResourceReferenceKeyNotFoundException", "Cannot find resource named '" + a[1] + "'."); return v; }
            case "TryFindResource": return findRes(c, a[1]);
            case "get_Dispatcher": return disp;
            case "InitializeComponent": return null;
            case "set_StartupUri": c.props.put("StartupUri", String.valueOf(a[1])); return null;
        }
        if (n.startsWith("add_") || n.startsWith("remove_") || n.startsWith("set_")) return null;
        return Bcl.NONE;
    }

    /** System.Windows.Threading.Dispatcher: runs delegates inline / on the UI queue. */
    public static final class Disp { final java.util.concurrent.CountDownLatch quit = new java.util.concurrent.CountDownLatch(1); }
    final Disp disp = new Disp();

    private Object onDisp(Disp d, String n, Object[] a, boolean isStatic) {
        int base = isStatic ? 0 : 1;
        switch (n) {
            case "Invoke": case "BeginInvoke": case "InvokeAsync": {
                Delegate del = null; int di = -1;
                for (int i = base; i < a.length; i++) if (a[i] instanceof Delegate) { del = (Delegate) a[i]; di = i; break; }
                if (del == null) return null;
                ArrayList<Object> rest = new ArrayList<>();
                for (int i = di + 1; i < a.length; i++) { if (a[i] instanceof Object[]) for (Object o : (Object[]) a[i]) rest.add(o); else rest.add(a[i]); }
                final Delegate fd = del; final Object[] args = rest.toArray();
                if (n.equals("Invoke")) return vm.interp.invokeDelegate(fd, args);
                wf.post(() -> { try { vm.interp.invokeDelegate(fd, args); } catch (Interp.Stop | Interp.Exit e) { throw e; } catch (RuntimeException e) { vm.console.write("\n[dispatcher] " + e + "\n"); } });
                return new WinForms.Props("DispatcherOperation");
            }
            case "CheckAccess": return 1;
            case "VerifyAccess": return null;
            case "Run": try { if (Thread.currentThread() == vm.mainThread) wf.runLoop(null); else d.quit.await(); } catch (InterruptedException e) { throw new Interp.Stop(); } return null;
            case "InvokeShutdown": case "BeginInvokeShutdown": case "ExitAllFrames": d.quit.countDown(); if (Thread.currentThread() != vm.mainThread) { /* ends a secondary dispatcher */ } return null;
            case "get_HasShutdownStarted": return d.quit.getCount() == 0 ? 1 : 0;
            case "get_Thread": return null;
            case "GetHashCode": return 1;
        }
        return Bcl.NONE;
    }

    /** WPF ResourceDictionary. */
    public static final class ResDict {
        final java.util.LinkedHashMap<Object, Object> map = new java.util.LinkedHashMap<>();
        final ArrayList<Object> merged = new ArrayList<>();
        Object source;
    }

    private static Object rkey(Object k) { return k instanceof ClrObject || k == null ? k : k.toString(); }

    private ResDict resOf(Ctl c) { Object r = c.props.get("res"); if (r == null) { r = new ResDict(); c.props.put("res", r); } return (ResDict) r; }

    private Object findRes(Ctl c, Object key) {
        for (Ctl p = c; p != null; p = p.parent) { ResDict d = (ResDict) p.props.get("res"); if (d != null) { Object v = lookup(d, key); if (v != null) return v; } }
        if (wf.currentApp != null && wf.currentApp.nativeState instanceof Ctl) { ResDict d = (ResDict) ((Ctl) wf.currentApp.nativeState).props.get("res"); if (d != null) return lookup(d, key); }
        return null;
    }

    private Object lookup(ResDict d, Object key) {
        Object v = d.map.get(rkey(key)); if (v != null) return v;
        for (Object m : d.merged) if (m instanceof ResDict) { v = lookup((ResDict) m, key); if (v != null) return v; }
        return null;
    }

    private Object onRes(ResDict d, String n, Object[] a) {
        switch (n) {
            case "get_Item": return lookup(d, a[1]);
            case "set_Item": d.map.put(rkey(a[1]), a[2]); return null;
            case "Add": d.map.put(rkey(a[1]), a[2]); return null;
            case "Remove": d.map.remove(rkey(a[1])); return null;
            case "Clear": d.map.clear(); return null;
            case "Contains": case "ContainsKey": return lookup(d, a[1]) != null ? 1 : 0;
            case "get_Count": return d.map.size();
            case "get_Keys": return new ArrayList<Object>(d.map.keySet());
            case "get_Values": return new ArrayList<Object>(d.map.values());
            case "get_MergedDictionaries": return d.merged;
            case "get_Source": return d.source;
            case "set_Source": d.source = a[1]; return null;
            case "BeginInit": case "EndInit": return null;
            case "GetHashCode": return System.identityHashCode(d);
            case "Equals": return d == a[1] ? 1 : 0;
        }
        return Bcl.NONE;
    }

    /** Children collection of a WPF panel. */
    public static final class ChildList { final Ctl owner; ChildList(Ctl o) { owner = o; } }
}
