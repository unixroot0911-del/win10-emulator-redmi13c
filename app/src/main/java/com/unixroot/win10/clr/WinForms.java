package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;
import com.unixroot.win10.core.GuiHost;
import com.unixroot.win10.core.Surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A retained-mode System.Windows.Forms / System.Drawing implementation rendered through the host Surface. */
public final class WinForms implements Bcl.Ext, GuiHost.Input {
    private static final String WF = "System.Windows.Forms.", SD = "System.Drawing.";
    private static final int FH = 14;
    private static final String[] CONTROLS = {"Control", "Form", "Button", "Label", "TextBox", "CheckBox", "RadioButton", "ListBox", "ComboBox", "Panel", "GroupBox",
            "PictureBox", "ProgressBar", "NumericUpDown", "TabControl", "TabPage", "RichTextBox", "ListView", "TreeView", "StatusStrip", "ToolStrip", "MenuStrip",
            "LinkLabel", "UserControl", "FlowLayoutPanel", "TableLayoutPanel", "SplitContainer", "ScrollableControl", "TrackBar", "DateTimePicker", "DataGridView",
            "ToolStripMenuItem", "ToolStripStatusLabel", "ToolStripButton", "ToolStripSeparator", "ToolStripLabel", "ContextMenuStrip", "CheckedListBox", "MaskedTextBox", "WebBrowser"};
    private static final String[] COMPONENTS = {"Timer", "ToolTip", "OpenFileDialog", "SaveFileDialog", "FolderBrowserDialog", "ColorDialog", "FontDialog",
            "NotifyIcon", "BackgroundWorker", "ImageList"};
    private static final String[] ARGS = {"MouseEventArgs", "KeyEventArgs", "KeyPressEventArgs", "PaintEventArgs", "FormClosingEventArgs", "FormClosedEventArgs",
            "CancelEventArgs", "DragEventArgs", "ScrollEventArgs", "LinkLabelLinkClickedEventArgs", "ItemCheckEventArgs", "DataGridViewCellEventArgs",
            "ToolStripItemClickedEventArgs", "TreeViewEventArgs", "UICancelEventArgs", "ListViewItemSelectionChangedEventArgs"};

    final Vm vm;
    final GuiHost host;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ArrayList<Ctl> forms = new ArrayList<>();
    private final ArrayList<Ctl> timers = new ArrayList<>();
    private final HashMap<Integer, Ctl> byWin = new HashMap<>();
    private int nextWin = 1;
    boolean quit;
    ClrObject currentApp, mainWindowObj;
    private Ctl mainForm;

    public static void install(Vm vm, GuiHost host) {
        for (String c : CONTROLS) { Bcl.OBJECT_BACKED.add(WF + c); Bcl.EXTRA_BASES.put(WF + c, c.equals("Control") ? "System.Object" : c.equals("Form") ? WF + "ScrollableControl" : c.equals("ScrollableControl") ? WF + "Control" : WF + "Control"); }
        for (String c : COMPONENTS) { Bcl.OBJECT_BACKED.add(WF + c); Bcl.EXTRA_BASES.put(WF + c, "System.Object"); }
        for (String c : ARGS) { Bcl.OBJECT_BACKED.add(WF + c); Bcl.EXTRA_BASES.put(WF + c, "System.EventArgs"); }
        Bcl.OBJECT_BACKED.add("System.EventArgs");
        Bcl.OBJECT_BACKED.add(SD + "Font");
        WinForms w = new WinForms(vm, host);
        vm.bcl.exts.add(w);
        w.wpf = Wpf.install(vm, w);
    }

    private WinForms(Vm vm, GuiHost host) { this.vm = vm; this.host = host; }

    // ------------------------------------------------------------ value types
    static final class Pt { int x, y; Pt(int x, int y) { this.x = x; this.y = y; } }
    static final class Sz { int w, h; Sz(int w, int h) { this.w = w; this.h = h; } }
    static final class Rc { int x, y, w, h; Rc(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; } }
    static final class Col { int argb; String name; Col(int argb, String n) { this.argb = argb; name = n; } int rgb() { return argb & 0xFFFFFF; } }
    static final class Props { final String kind; final HashMap<String, Object> m = new HashMap<>(); Props(String k) { kind = k; } }
    static final class Gfx { final Surface s; final int ox, oy; Gfx(Surface s, int ox, int oy) { this.s = s; this.ox = ox; this.oy = oy; } }
    static final class CtlList { final Ctl owner; CtlList(Ctl o) { owner = o; } }
    static final class ItemList { final Ctl owner; ItemList(Ctl o) { owner = o; } }
    Wpf wpf;

    static final HashMap<String, Integer> COLORS = new HashMap<>();
    static {
        Object[] c = {"Black", 0x000000, "White", 0xFFFFFF, "Red", 0xFF0000, "Green", 0x008000, "Blue", 0x0000FF, "Yellow", 0xFFFF00, "Gray", 0x808080, "Orange", 0xFFA500,
            "LightGray", 0xD3D3D3, "DarkGray", 0xA9A9A9, "Silver", 0xC0C0C0, "Cyan", 0x00FFFF, "Magenta", 0xFF00FF, "Purple", 0x800080, "Brown", 0xA52A2A, "Pink", 0xFFC0CB,
            "DarkBlue", 0x00008B, "DarkGreen", 0x006400, "DarkRed", 0x8B0000, "LightBlue", 0xADD8E6, "LightGreen", 0x90EE90, "LightYellow", 0xFFFFE0, "Navy", 0x000080,
            "Teal", 0x008080, "Lime", 0x00FF00, "Maroon", 0x800000, "Gold", 0xFFD700, "Transparent", 0xFFFFFF, "WhiteSmoke", 0xF5F5F5, "Gainsboro", 0xDCDCDC,
            "Control", 0xF0F0F0, "ControlText", 0x000000, "Window", 0xFFFFFF, "WindowText", 0x000000, "Highlight", 0x3399FF, "HighlightText", 0xFFFFFF, "ButtonFace", 0xF0F0F0,
            "ControlDark", 0xA0A0A0, "ControlLight", 0xE3E3E3, "GrayText", 0x6D6D6D, "Menu", 0xF0F0F0, "MenuText", 0x000000, "ActiveCaption", 0x99B4D1, "InfoText", 0, "Info", 0xFFFFE1,
            "ControlDarkDark", 0x696969, "ControlLightLight", 0xFFFFFF, "AppWorkspace", 0xABABAB, "Desktop", 0, "HotTrack", 0x0066CC, "ScrollBar", 0xC8C8C8, "WindowFrame", 0x646464};
        for (int i = 0; i < c.length; i += 2) COLORS.put((String) c[i], (Integer) c[i + 1]);
    }

    // ------------------------------------------------------------ controls
    static final class Ctl {
        ClrObject obj; String kind; Ctl parent; ArrayList<Ctl> kids = new ArrayList<>();
        int x, y, w = 100, h = 23; String text = ""; boolean visible = true, enabled = true, checked, multiline, password, pressed, open;
        int back = -1, fore = -1, dock, sel = -1, value, min, max = 100, caret, interval = 100, fontPx = FH; boolean bold;
        ArrayList<Object> items = new ArrayList<>(); HashMap<String, Delegate> ev = new HashMap<>(); HashMap<String, Object> props = new HashMap<>();
        Object tag; String name = ""; boolean isForm, closed; int dialogResult; boolean timerOn; long due;
        int win; GuiHost.HostWindow hw; Ctl acceptButton; boolean modal, dirty = true; int cw = 300, ch = 300; Ctl focus;
        int ax, ay;
    }

    Ctl C(Object o) {
        if (o instanceof ClrObject && ((ClrObject) o).nativeState instanceof Ctl) return (Ctl) ((ClrObject) o).nativeState;
        return null;
    }

    private boolean isKind(String full, String[] arr) {
        if (!full.startsWith(WF)) return false;
        String n = full.substring(WF.length());
        for (String s : arr) if (s.equals(n)) return true;
        return false;
    }

    Ctl create(ClrObject o, String full) {
        Ctl c = new Ctl();
        c.obj = o; c.kind = full.substring(WF.length());
        o.nativeState = c;
        c.isForm = false;
        switch (c.kind) {
            case "Form": c.isForm = true; c.w = 300; c.h = 300; c.cw = 284; c.ch = 261; c.visible = false; break;
            case "Button": c.w = 75; break;
            case "Label": c.h = 15; c.w = 100; break;
            case "TextBox": c.w = 100; c.h = 20; break;
            case "CheckBox": case "RadioButton": c.w = 104; c.h = 24; break;
            case "ListBox": c.w = 120; c.h = 95; break;
            case "ComboBox": c.w = 121; c.h = 21; break;
            case "Panel": case "GroupBox": case "FlowLayoutPanel": case "TableLayoutPanel": c.w = 200; c.h = 100; break;
            case "ProgressBar": c.w = 100; c.h = 23; break;
            case "Timer": c.interval = 100; break;
        }
        return c;
    }

    // ------------------------------------------------------------ Ext: static fields (enums, colors)
    @Override public Object getStatic(Vm vm, FieldInfo f) {
        String t = f.owner.full, n = f.name;
        if (t.equals(SD + "Color") || t.equals(SD + "SystemColors")) { Integer c = COLORS.get(n); if (c != null) return new Col(0xFF000000 | c, n); }
        if (t.equals(SD + "Brushes")) { Integer c = COLORS.get(n); if (c != null) return brush(c); }
        if (t.equals(SD + "Pens")) { Integer c = COLORS.get(n); if (c != null) return pen(c, 1); }
        if (t.equals(SD + "Point") && n.equals("Empty")) return new Pt(0, 0);
        if (t.equals(SD + "Size") && n.equals("Empty")) return new Sz(0, 0);
        if (t.equals(SD + "Rectangle") && n.equals("Empty")) return new Rc(0, 0, 0, 0);
        if (t.equals(WF + "Application")) { if (n.equals("StartupPath")) return "C:\\"; }
        if (t.startsWith(WF) || t.startsWith(SD)) return enumValue(t.substring(t.lastIndexOf('.') + 1), n);
        return Bcl.NONE;
    }

    private Object enumValue(String type, String n) {
        switch (type + "." + n) {
            case "DockStyle.Top": return 1; case "DockStyle.Bottom": return 2; case "DockStyle.Left": return 3; case "DockStyle.Right": return 4; case "DockStyle.Fill": return 5;
            case "FormStartPosition.CenterScreen": return 1; case "FormStartPosition.CenterParent": return 4;
            case "DialogResult.OK": return 1; case "DialogResult.Cancel": return 2; case "DialogResult.Abort": return 3; case "DialogResult.Retry": return 4;
            case "DialogResult.Ignore": return 5; case "DialogResult.Yes": return 6; case "DialogResult.No": return 7;
            case "AnchorStyles.Top": return 1; case "AnchorStyles.Bottom": return 2; case "AnchorStyles.Left": return 4; case "AnchorStyles.Right": return 8;
            case "FontStyle.Bold": return 1; case "FontStyle.Italic": return 2; case "FontStyle.Underline": return 4; case "FontStyle.Strikeout": return 8;
            case "MessageBoxButtons.OKCancel": return 1; case "MessageBoxButtons.AbortRetryIgnore": return 2; case "MessageBoxButtons.YesNoCancel": return 3; case "MessageBoxButtons.YesNo": return 4; case "MessageBoxButtons.RetryCancel": return 5;
            case "MessageBoxIcon.Error": return 16; case "MessageBoxIcon.Question": return 32; case "MessageBoxIcon.Warning": return 48; case "MessageBoxIcon.Information": return 64;
            case "FormBorderStyle.FixedSingle": return 1; case "FormBorderStyle.Fixed3D": return 2; case "FormBorderStyle.FixedDialog": return 3; case "FormBorderStyle.Sizable": return 4;
            case "HorizontalAlignment.Center": return 2; case "HorizontalAlignment.Right": return 1;
            case "ContentAlignment.MiddleCenter": return 32; case "ContentAlignment.TopCenter": return 2; case "ContentAlignment.MiddleRight": return 64;
            case "Keys.Enter": return 13; case "Keys.Escape": return 27; case "Keys.Space": return 32; case "Keys.Back": return 8; case "Keys.Tab": return 9;
            case "Keys.Left": return 37; case "Keys.Up": return 38; case "Keys.Right": return 39; case "Keys.Down": return 40; case "Keys.Delete": return 46;
            case "MouseButtons.Left": return 0x100000; case "MouseButtons.Right": return 0x200000; case "MouseButtons.Middle": return 0x400000;
            case "CheckState.Checked": return 1; case "CheckState.Indeterminate": return 2;
        }
        if (type.equals("Keys") && n.length() == 1) return (int) n.charAt(0);
        return 0;
    }

    private Props brush(int rgb) { Props p = new Props("Brush"); p.m.put("c", rgb); return p; }
    private Props pen(int rgb, int w) { Props p = new Props("Pen"); p.m.put("c", rgb); p.m.put("w", w); return p; }

    // ------------------------------------------------------------ Ext: calls
    @Override public Object call(Vm vm, ClrMethod m, Object[] a) {
        String owner = m.owner == null ? "" : m.owner.full, n = m.name;
        Object recv = m.hasThis && a.length > 0 ? (a[0] instanceof Ref ? ((Ref) a[0]).get() : a[0]) : null;
        if (m.hasThis && recv != null && !(recv instanceof ClrObject) && !(recv instanceof Pt) && !(recv instanceof Sz) && !(recv instanceof Rc) && !(recv instanceof Col)
                && !(recv instanceof Props) && !(recv instanceof Gfx) && !(recv instanceof CtlList) && !(recv instanceof ItemList) && !(recv instanceof Wpf.ChildList)) return Bcl.NONE;
        if (recv instanceof ClrObject && ((ClrObject) recv).nativeState instanceof Ctl && ((Ctl) ((ClrObject) recv).nativeState).props.containsKey("wpf")) return Bcl.NONE; // WPF objects are handled by Wpf
        boolean wf = owner.startsWith(WF) || owner.startsWith(SD) || owner.startsWith("System.ComponentModel");
        if (recv instanceof ClrObject && ((ClrObject) recv).nativeState instanceof Ctl) wf = true;
        if (recv instanceof ClrObject && ((ClrObject) recv).nativeState instanceof Props) wf = true;
        if (recv instanceof Pt || recv instanceof Sz || recv instanceof Rc || recv instanceof Col || recv instanceof Gfx || recv instanceof CtlList || recv instanceof ItemList || recv instanceof Wpf.ChildList || (recv instanceof Props)) wf = true;
        if (!wf) return Bcl.NONE;
        if (m.ptypes == null) {
            String s = m.sigText.substring(1, m.sigText.length() - 1);
            m.ptypes = s.isEmpty() ? new String[0] : s.split(",(?![^<]*>)");
        }
        if (n.equals(".ctor")) return ctor(m, owner, recv, a);
        if (!m.hasThis) return statics(m, owner, n, a);
        if (recv instanceof Pt) return onPt((Pt) recv, n, a);
        if (recv instanceof Sz) return onSz((Sz) recv, n, a);
        if (recv instanceof Rc) return onRc((Rc) recv, n, a);
        if (recv instanceof Col) return onCol((Col) recv, n, a);
        if (recv instanceof Gfx) return onGfx((Gfx) recv, n, a);
        if (recv instanceof CtlList) return onCtlList(((CtlList) recv).owner, n, a);
        if (recv instanceof Wpf.ChildList) return onCtlList(((Wpf.ChildList) recv).owner, n, a);
        if (recv instanceof ItemList) return onItems(((ItemList) recv).owner, n, a);
        if (recv instanceof Props) return onProps((Props) recv, n, a);
        ClrObject o = (ClrObject) recv;
        if (o.nativeState instanceof Ctl) return onCtl((Ctl) o.nativeState, n, a);
        if (o.nativeState instanceof Props) return onProps((Props) o.nativeState, n, a);
        if (n.equals("BeginInit") || n.equals("EndInit") || n.equals("Dispose") || n.equals("Close")) return null;
        return Bcl.NONE;
    }

    private Object ctor(ClrMethod m, String owner, Object recv, Object[] a) {
        if (recv == null) { // newobj of a non-object-backed type
            switch (owner) {
                case SD + "Point": return new Pt(num(a[1]), num(a[2]));
                case SD + "PointF": return new Pt(num(a[1]), num(a[2]));
                case SD + "Size": case SD + "SizeF": return new Sz(num(a[1]), num(a[2]));
                case SD + "Rectangle": case SD + "RectangleF": return new Rc(num(a[1]), num(a[2]), num(a[3]), num(a[4]));
                case SD + "SolidBrush": return brush(((Col) a[1]).rgb());
                case SD + "Pen": return pen(a[1] instanceof Col ? ((Col) a[1]).rgb() : 0, a.length > 2 ? num(a[2]) : 1);
                case WF + "Padding": return new Props("Padding");
            }
            if (owner.startsWith(WF) || owner.startsWith(SD) || owner.startsWith("System.ComponentModel")) return new Props(owner.substring(owner.lastIndexOf('.') + 1));
            return Bcl.NONE;
        }
        if (recv instanceof ClrObject) {
            ClrObject o = (ClrObject) recv;
            if (isKind(owner, CONTROLS)) { if (o.nativeState == null) create(o, owner); return null; }
            if (isKind(owner, COMPONENTS)) { Ctl c = create(o, owner); if (owner.endsWith("Timer")) timers.add(c); return null; }
            if (owner.equals(SD + "Font") ) { Props p = new Props("Font"); p.m.put("size", a.length > 2 && a[2] instanceof Double ? ((Double) a[2]) : 8.25); o.nativeState = p; return null; }
            if (owner.startsWith(WF) || owner.startsWith("System.EventArgs")) { if (o.nativeState == null) o.nativeState = new Props(owner); return null; }
        }
        return Bcl.NONE;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : o instanceof Character ? (Character) o : 0; }
    private static Object bool(boolean b) { return b ? 1 : 0; }

    private Object onPt(Pt p, String n, Object[] a) {
        switch (n) { case "get_X": return p.x; case "get_Y": return p.y; case "set_X": p.x = num(a[1]); return null; case "set_Y": p.y = num(a[1]); return null; case "get_IsEmpty": return bool(p.x == 0 && p.y == 0); case "ToString": return "{X=" + p.x + ",Y=" + p.y + "}"; case "Equals": return bool(a[1] instanceof Pt && ((Pt) a[1]).x == p.x && ((Pt) a[1]).y == p.y); }
        return Bcl.NONE;
    }
    private Object onSz(Sz p, String n, Object[] a) {
        switch (n) { case "get_Width": return p.w; case "get_Height": return p.h; case "set_Width": p.w = num(a[1]); return null; case "set_Height": p.h = num(a[1]); return null; case "ToString": return "{Width=" + p.w + ", Height=" + p.h + "}"; }
        return Bcl.NONE;
    }
    private Object onRc(Rc r, String n, Object[] a) {
        switch (n) { case "get_X": case "get_Left": return r.x; case "get_Y": case "get_Top": return r.y; case "get_Width": return r.w; case "get_Height": return r.h;
            case "get_Right": return r.x + r.w; case "get_Bottom": return r.y + r.h; case "Contains": return bool(num(a[1]) >= r.x && num(a[1]) < r.x + r.w && num(a[2]) >= r.y && num(a[2]) < r.y + r.h); }
        return Bcl.NONE;
    }
    private Object onCol(Col c, String n, Object[] a) {
        switch (n) { case "get_R": return c.argb >> 16 & 255; case "get_G": return c.argb >> 8 & 255; case "get_B": return c.argb & 255; case "get_A": return c.argb >>> 24;
            case "ToArgb": return c.argb; case "get_Name": return c.name == null ? Integer.toHexString(c.argb) : c.name; case "ToString": return "Color [" + (c.name == null ? "A=" + (c.argb >>> 24) + ", R=" + (c.argb >> 16 & 255) + ", G=" + (c.argb >> 8 & 255) + ", B=" + (c.argb & 255) : c.name) + "]";
            case "Equals": return bool(a[1] instanceof Col && ((Col) a[1]).argb == c.argb); case "GetHashCode": return c.argb; }
        return Bcl.NONE;
    }

    private Object onProps(Props p, String n, Object[] a) {
        if (n.startsWith("get_")) { Object v = p.m.get(n.substring(4)); return v == null ? defaultProp(n.substring(4)) : v; }
        if (n.startsWith("set_")) { p.m.put(n.substring(4), a[1]); return null; }
        if (n.startsWith("add_") || n.startsWith("remove_")) return null;
        switch (n) { case "Dispose": case "Close": case "BeginInit": case "EndInit": case "SuspendLayout": case "ResumeLayout": case "PerformLayout": return null; case "ToString": return p.kind; case "GetHashCode": return System.identityHashCode(p); case "Equals": return bool(p == a[1]); }
        return null;
    }
    private static Object defaultProp(String n) { return n.startsWith("Is") || n.startsWith("Has") || n.equals("Handled") || n.equals("Cancel") ? (Object) 0 : n.equals("Count") ? (Object) 0 : null; }

    // ------------------------------------------------------------ statics
    private Object statics(ClrMethod m, String owner, String n, Object[] a) {
        switch (owner + "::" + n) {
            case WF + "Application::EnableVisualStyles": case WF + "Application::SetCompatibleTextRenderingDefault": case WF + "Application::SetHighDpiMode":
            case WF + "Application::add_ApplicationExit": case WF + "Application::add_ThreadException": case WF + "Application::SetUnhandledExceptionMode": return null;
            case WF + "Application::Run": runLoop(a.length > 0 ? C(a[0]) : null); return null;
            case WF + "Application::Exit": case WF + "Application::ExitThread": quit = true; return null;
            case WF + "Application::DoEvents": pump(0); return null;
            case WF + "Application::get_StartupPath": return "C:\\";
            case WF + "Application::get_ExecutablePath": return "C:\\" + vm.main.name + ".exe";
            case WF + "Application::get_ProductVersion": return "1.0.0.0";
            case WF + "Application::get_ProductName": return vm.main.name;
            case WF + "MessageBox::Show": return messageBox(m, a);
            case SD + "Color::FromArgb": {
                if (a.length == 1) return new Col(num(a[0]), null);
                if (a.length == 2) return new Col(a[0] instanceof Number ? ((Col) a[1]).argb & 0xFFFFFF | num(a[0]) << 24 : 0, null);
                if (a.length == 3) return new Col(0xFF000000 | (num(a[0]) & 255) << 16 | (num(a[1]) & 255) << 8 | (num(a[2]) & 255), null);
                return new Col((num(a[0]) & 255) << 24 | (num(a[1]) & 255) << 16 | (num(a[2]) & 255) << 8 | (num(a[3]) & 255), null);
            }
            case SD + "Color::FromName": { Integer c = COLORS.get((String) a[0]); return new Col(0xFF000000 | (c == null ? 0 : c), (String) a[0]); }
            case SD + "Color::op_Equality": return bool(a[0] instanceof Col && a[1] instanceof Col && ((Col) a[0]).argb == ((Col) a[1]).argb);
            case SD + "Color::op_Inequality": return bool(!(a[0] instanceof Col && a[1] instanceof Col && ((Col) a[0]).argb == ((Col) a[1]).argb));
            case SD + "Point::op_Equality": return bool(((Pt) a[0]).x == ((Pt) a[1]).x && ((Pt) a[0]).y == ((Pt) a[1]).y);
            case SD + "Rectangle::FromLTRB": return new Rc(num(a[0]), num(a[1]), num(a[2]) - num(a[0]), num(a[3]) - num(a[1]));
            case WF + "Cursor::get_Current": case WF + "Cursor::set_Current": return null;
            case WF + "Control::get_ModifierKeys": return 0;
            case WF + "Control::get_MousePosition": return new Pt(0, 0);
            case WF + "Screen::get_PrimaryScreen": { Props p = new Props("Screen"); p.m.put("Bounds", new Rc(0, 0, 1280, 720)); p.m.put("WorkingArea", new Rc(0, 0, 1280, 690)); return p; }
            case WF + "Clipboard::SetText": case WF + "Clipboard::Clear": return null;
            case WF + "Clipboard::GetText": return "";
            case WF + "Clipboard::ContainsText": return 0;
        }
        return Bcl.NONE;
    }

    private Object messageBox(ClrMethod m, Object[] a) {
        String text = null, cap = "";
        int btn = 0, icon = 0, k = 0;
        for (int i = 0; i < a.length; i++) {
            String pt = i < m.ptypes.length ? m.ptypes[i] : "";
            if (pt.equals("String")) { if (text == null) text = (String) a[i]; else cap = (String) a[i]; }
            else if (pt.equals("MessageBoxButtons")) btn = num(a[i]);
            else if (pt.equals("MessageBoxIcon")) icon = num(a[i]);
        }
        vm.console.messageBox(cap == null ? "" : cap, text == null ? "" : text);
        switch (btn) { case 3: case 4: return 6; case 2: return 5; case 5: return 4; default: return 1; }
    }

    // ------------------------------------------------------------ control methods
    Object onCtl(Ctl c, String n, Object[] a) {
        if (n.startsWith("add_")) { String e = n.substring(4); Delegate d = (Delegate) a[1]; Delegate old = c.ev.get(e); c.ev.put(e, old == null ? d : old.combine(d)); return null; }
        if (n.startsWith("remove_")) { String e = n.substring(7); Delegate old = c.ev.get(e); if (old != null) { Delegate r = old.remove((Delegate) a[1]); if (r == null) c.ev.remove(e); else c.ev.put(e, r); } return null; }
        switch (n) {
            case "get_Text": return c.text; case "set_Text": c.text = a[1] == null ? "" : (String) a[1]; if (c.isForm && c.hw != null) c.hw.setTitle(c.text); dirty(c); fire(c, "TextChanged", null); return null;
            case "get_Name": return c.name; case "set_Name": c.name = (String) a[1]; return null;
            case "get_Tag": return c.tag; case "set_Tag": c.tag = a[1]; return null;
            case "get_Left": return c.x; case "set_Left": c.x = num(a[1]); dirty(c); return null;
            case "get_Top": return c.y; case "set_Top": c.y = num(a[1]); dirty(c); return null;
            case "get_Right": return c.x + c.w; case "get_Bottom": return c.y + c.h;
            case "get_Width": return c.isForm ? c.w : c.w; case "set_Width": c.w = num(a[1]); if (c.isForm) c.cw = c.w - 16; dirty(c); return null;
            case "get_Height": return c.h; case "set_Height": c.h = num(a[1]); if (c.isForm) c.ch = c.h - 39; dirty(c); return null;
            case "get_Location": return new Pt(c.x, c.y); case "set_Location": c.x = ((Pt) a[1]).x; c.y = ((Pt) a[1]).y; dirty(c); return null;
            case "get_Size": return new Sz(c.w, c.h);
            case "set_Size": c.w = ((Sz) a[1]).w; c.h = ((Sz) a[1]).h; if (c.isForm) { c.cw = c.w - 16; c.ch = c.h - 39; } dirty(c); return null;
            case "get_ClientSize": return new Sz(c.isForm ? c.cw : c.w, c.isForm ? c.ch : c.h);
            case "set_ClientSize": c.cw = ((Sz) a[1]).w; c.ch = ((Sz) a[1]).h; c.w = c.cw + 16; c.h = c.ch + 39; dirty(c); return null;
            case "get_ClientRectangle": return new Rc(0, 0, c.isForm ? c.cw : c.w, c.isForm ? c.ch : c.h);
            case "get_Bounds": return new Rc(c.x, c.y, c.w, c.h);
            case "SetBounds": c.x = num(a[1]); c.y = num(a[2]); c.w = num(a[3]); c.h = num(a[4]); dirty(c); return null;
            case "get_Visible": return bool(c.visible); case "set_Visible": if (c.isForm) { if (num(a[1]) != 0) showForm(c); else hideForm(c); } else { c.visible = num(a[1]) != 0; dirty(c); } return null;
            case "get_Enabled": return bool(c.enabled); case "set_Enabled": c.enabled = num(a[1]) != 0; dirty(c); return null;
            case "get_Checked": return bool(c.checked); case "set_Checked": c.checked = num(a[1]) != 0; dirty(c); return null;
            case "get_CheckState": return c.checked ? 1 : 0; case "set_CheckState": c.checked = num(a[1]) != 0; dirty(c); return null;
            case "get_BackColor": return new Col(0xFF000000 | (c.back < 0 ? 0xF0F0F0 : c.back), null); case "set_BackColor": c.back = ((Col) a[1]).rgb(); dirty(c); return null;
            case "get_ForeColor": return new Col(0xFF000000 | (c.fore < 0 ? 0 : c.fore), null); case "set_ForeColor": c.fore = ((Col) a[1]).rgb(); dirty(c); return null;
            case "get_Dock": return c.dock; case "set_Dock": c.dock = num(a[1]); dirty(c); return null;
            case "get_Parent": return c.parent == null ? null : c.parent.obj;
            case "get_Controls": return new CtlList(c);
            case "get_Items": return new ItemList(c);
            case "get_Multiline": return bool(c.multiline); case "set_Multiline": c.multiline = num(a[1]) != 0; return null;
            case "set_UseSystemPasswordChar": c.password = num(a[1]) != 0; return null;
            case "set_PasswordChar": c.password = num(a[1]) != 0; return null;
            case "get_SelectedIndex": return c.sel; case "set_SelectedIndex": c.sel = num(a[1]); if (c.sel >= 0 && c.sel < c.items.size() && c.kind.equals("ComboBox")) c.text = Bcl.class == null ? "" : vm.bcl.str(c.items.get(c.sel)); dirty(c); return null;
            case "get_SelectedItem": return c.sel >= 0 && c.sel < c.items.size() ? c.items.get(c.sel) : null;
            case "set_SelectedItem": c.sel = c.items.indexOf(a[1]); dirty(c); return null;
            case "get_Value": return c.value; case "set_Value": c.value = Math.max(c.min, Math.min(c.max, num(a[1]))); dirty(c); return null;
            case "get_Minimum": return c.min; case "set_Minimum": c.min = num(a[1]); return null;
            case "get_Maximum": return c.max; case "set_Maximum": c.max = num(a[1]); return null;
            case "get_Interval": return c.interval; case "set_Interval": c.interval = Math.max(1, num(a[1])); return null;
            case "get_Enabled0": return null;
            case "Start": c.timerOn = true; c.due = System.currentTimeMillis() + c.interval; return null;
            case "Stop": c.timerOn = false; return null;
            case "set_FontSize": return null;
            case "get_Font": { Props p = new Props("Font"); p.m.put("Size", c.fontPx * 0.58); return p; }
            case "set_Font": { Object f = a[1] instanceof ClrObject ? ((ClrObject) a[1]).nativeState : a[1]; if (f instanceof Props) { Object s = ((Props) f).m.get("size"); if (s instanceof Number) c.fontPx = Math.max(8, (int) Math.round(((Number) s).doubleValue() * 1.71)); } dirty(c); return null; }
            case "get_AcceptButton": return c.acceptButton == null ? null : c.acceptButton.obj; case "set_AcceptButton": c.acceptButton = C(a[1]); return null;
            case "get_DialogResult": return c.dialogResult; case "set_DialogResult": c.dialogResult = num(a[1]); if (c.dialogResult != 0 && c.isForm) closeForm(c); else if (c.dialogResult != 0 && c.parent != null) { Ctl f = formOf(c); if (f != null) { f.dialogResult = c.dialogResult; closeForm(f); } } return null;
            case "get_IsDisposed": return bool(c.closed); case "get_InvokeRequired": return 0; case "get_Handle": return System.identityHashCode(c); case "get_IsHandleCreated": return 1;
            case "get_Focused": { Ctl f = formOf(c); return bool(f != null && f.focus == c); }
            case "get_TabIndex": return 0; case "get_Text0": return null;
            case "get_Count": return c.items.size();
            case "Show": case "Activate": if (c.isForm) showForm(c); else { c.visible = true; dirty(c); } return null;
            case "Hide": if (c.isForm) hideForm(c); else { c.visible = false; dirty(c); } return null;
            case "ShowDialog": return showDialog(c);
            case "Close": closeForm(c); return null;
            case "Dispose": c.closed = true; c.timerOn = false; return null;
            case "Focus": case "Select": { Ctl f = formOf(c); if (f != null) f.focus = c; dirty(c); return 1; }
            case "Invalidate": case "Refresh": case "Update": dirty(c); return null;
            case "Invoke": return vm.interp.invokeDelegate((Delegate) a[1], a.length > 2 && a[2] instanceof Object[] ? (Object[]) a[2] : new Object[0]);
            case "BeginInvoke": { final Object[] args = a.length > 2 && a[2] instanceof Object[] ? (Object[]) a[2] : new Object[0]; final Delegate d = (Delegate) a[1]; queue.add(() -> vm.interp.invokeDelegate(d, args)); return null; }
            case "BringToFront": case "SendToBack": case "SuspendLayout": case "ResumeLayout": case "PerformLayout": case "CreateControl": case "BeginInit": case "EndInit": return null;
            case "Add": return null;
            case "AppendText": c.text += a[1]; dirty(c); return null;
            case "Clear": if (c.kind.equals("TextBox")) c.text = ""; c.items.clear(); dirty(c); return null;
            case "PerformClick": fire(c, "Click", null); return null;
            case "ToString": return WF + c.kind + ", Text: " + c.text;
            case "GetHashCode": return System.identityHashCode(c);
            case "Equals": return bool(c.obj == a[1]);
            case "CreateGraphics": { Ctl f = formOf(c); return f != null && f.hw != null ? new Gfx(f.hw.surface(), absX(c), absY(c)) : null; }
            case "PointToScreen": case "PointToClient": return a[1];
            case "get_PreferredSize": return new Sz(c.w, c.h);
            case "get_Handle0": return null;
        }
        if (n.startsWith("get_")) { Object v = c.props.get(n.substring(4)); return v != null ? v : defaultProp(n.substring(4)); }
        if (n.startsWith("set_")) { c.props.put(n.substring(4), a[1]); return null; }
        return null; // unknown method on a WinForms control: ignore rather than abort the program
    }

    private Object onCtlList(Ctl owner, String n, Object[] a) {
        switch (n) {
            case "Add": { Ctl k = C(a[1]); if (k != null) addChild(owner, k); return null; }
            case "AddRange": { for (Object o : (Object[]) a[1]) { Ctl k = C(o); if (k != null) addChild(owner, k); } return null; }
            case "Remove": { Ctl k = C(a[1]); if (k != null) { owner.kids.remove(k); k.parent = null; dirty(owner); } return null; }
            case "Clear": for (Ctl k : owner.kids) k.parent = null; owner.kids.clear(); dirty(owner); return null;
            case "get_Count": return owner.kids.size();
            case "get_Item": return owner.kids.get(num(a[1])).obj;
            case "Contains": return bool(owner.kids.contains(C(a[1])));
            case "SetChildIndex": return null;
            case "GetEnumerator": { ArrayList<Object> l = new ArrayList<>(); for (Ctl k : owner.kids) l.add(k.obj); return new Bcl.EnmFactory().of(l.iterator()); }
        }
        return null;
    }

    private Object onItems(Ctl c, String n, Object[] a) {
        switch (n) {
            case "Add": c.items.add(a[1]); dirty(c); return c.items.size() - 1;
            case "AddRange": for (Object o : (Object[]) a[1]) c.items.add(o); dirty(c); return null;
            case "Clear": c.items.clear(); c.sel = -1; dirty(c); return null;
            case "get_Count": return c.items.size();
            case "get_Item": return c.items.get(num(a[1]));
            case "set_Item": c.items.set(num(a[1]), a[2]); dirty(c); return null;
            case "Contains": return bool(c.items.contains(a[1]));
            case "IndexOf": return c.items.indexOf(a[1]);
            case "Remove": c.items.remove(a[1]); dirty(c); return null;
            case "RemoveAt": c.items.remove(num(a[1])); dirty(c); return null;
            case "Insert": c.items.add(num(a[1]), a[2]); dirty(c); return null;
            case "GetEnumerator": return new Bcl.EnmFactory().of(new ArrayList<>(c.items).iterator());
        }
        return null;
    }

    void addChild(Ctl owner, Ctl k) { if (k.parent != null) k.parent.kids.remove(k); k.parent = owner; owner.kids.add(k); dirty(owner); }
    Ctl formOf(Ctl c) { while (c != null && !c.isForm) c = c.parent; return c; }
    void dirty(Ctl c) { Ctl f = formOf(c); if (f != null) f.dirty = true; }
    int absX(Ctl c) { int x = 0; for (Ctl p = c; p != null && !p.isForm; p = p.parent) x += p.x; return x; }
    int absY(Ctl c) { int y = 0; for (Ctl p = c; p != null && !p.isForm; p = p.parent) y += p.y; return y; }

    // ------------------------------------------------------------ events
    Object fire(Ctl c, String ev, Object args) {
        Delegate d = c.ev.get(ev);
        Object a = args != null ? args : vm.newObject(vm.nativeType("System.EventArgs"));
        if (d != null) vm.interp.invokeDelegate(d, new Object[]{c.obj, a});
        return a;
    }

    ClrObject eventArgs(String type, Object... kv) {
        ClrObject o = vm.newObject(vm.nativeType(WF + type));
        Props p = new Props(type);
        for (int i = 0; i < kv.length; i += 2) p.m.put((String) kv[i], kv[i + 1]);
        o.nativeState = p;
        return o;
    }

    // ------------------------------------------------------------ windows
    void showForm(Ctl f) {
        if (f.hw == null) {
            f.win = nextWin++;
            byWin.put(f.win, f);
            f.hw = host.createWindow(this, f.win, f.text, f.cw, f.ch);
            if (!forms.contains(f)) forms.add(f);
            fire(f, "Load", null);
            fire(f, "Shown", null);
        }
        f.visible = true; f.dirty = true;
        f.hw.setVisible(true);
        if (mainForm == null) mainForm = f;
    }

    void hideForm(Ctl f) { f.visible = false; if (f.hw != null) f.hw.setVisible(false); }

    void closeForm(Ctl f) {
        if (!f.isForm || f.closed) return;
        ClrObject ea = eventArgs("FormClosingEventArgs", "Cancel", 0, "CloseReason", 0);
        fire(f, "FormClosing", ea);
        Object cancel = ((Props) ea.nativeState).m.get("Cancel");
        if (cancel instanceof Number && ((Number) cancel).intValue() != 0) return;
        f.closed = true; f.visible = false;
        fire(f, "FormClosed", eventArgs("FormClosedEventArgs", "CloseReason", 0));
        if (f.hw != null) { f.hw.close(); byWin.remove(f.win); f.hw = null; }
        forms.remove(f);
        if (f == mainForm) quit = true;
    }

    Object showDialog(Ctl f) {
        f.modal = true; f.closed = false;
        showForm(f);
        while (!f.closed && !vm.stopped) pump(50);
        f.modal = false;
        return f.dialogResult;
    }

    void runLoop(Ctl f) {
        if (f != null) { mainForm = f; showForm(f); }
        quit = false;
        while (!quit && !vm.stopped && !forms.isEmpty()) pump(30);
        if (vm.stopped) throw new Interp.Stop();
    }

    /** Processes pending host events, timers and repaints for up to waitMs. */
    void post(Runnable r) { queue.add(r); }

    void pump(long waitMs) {
        try {
            Runnable r = waitMs > 0 ? queue.poll(waitMs, TimeUnit.MILLISECONDS) : queue.poll();
            while (r != null) { r.run(); r = queue.poll(); }
        } catch (InterruptedException e) { throw new Interp.Stop(); }
        long now = System.currentTimeMillis();
        for (Ctl t : new ArrayList<>(timers)) if (t.timerOn && now >= t.due) { t.due = now + t.interval; fire(t, "Tick", null); }
        for (Ctl f : new ArrayList<>(forms)) if (f.dirty && f.hw != null && f.visible) { f.dirty = false; paintForm(f); }
    }

    // host -> guest input (called on the UI thread)
    @Override public void postMouse(final int win, final int msg, final int x, final int y) { queue.add(() -> mouse(byWin.get(win), msg, x, y)); }
    @Override public void postKey(final int vk, final boolean down, final int ch) { queue.add(() -> key(vk, down, ch)); }
    @Override public void windowClosed(final int win) { queue.add(() -> { Ctl f = byWin.get(win); if (f != null) { f.hw = null; closeForm(f); } }); }
    @Override public void windowActivated(final int win) { }

    private Ctl activeForm() { for (int i = forms.size() - 1; i >= 0; i--) if (forms.get(i).visible && forms.get(i).hw != null) return forms.get(i); return null; }

    private Ctl hit(Ctl c, int x, int y) {
        for (int i = c.kids.size() - 1; i >= 0; i--) {
            Ctl k = c.kids.get(i);
            if (!k.visible) continue;
            Rc r = layoutRect(k);
            if (x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h) { Ctl h = hit(k, x - r.x, y - r.y); return h != null ? h : k; }
        }
        return null;
    }

    private Ctl pressedCtl;

    private void mouse(Ctl f, int msg, int x, int y) {
        if (f == null) return;
        Ctl open = openCombo(f);
        Ctl t = hit(f, x, y);
        if (msg == 0x201) {
            if (open != null) {
                int ax = absX(open), ay = absY(open);
                if (x >= ax && x < ax + open.w && y >= ay + open.h && y < ay + open.h + open.items.size() * 16) { open.sel = (y - ay - open.h) / 16; Object it = open.items.get(open.sel); open.text = vm.bcl.str(it); open.open = false; fire(open, "SelectedIndexChanged", null); fire(open, "SelectionChanged", null); fire(open, "SelectedValueChanged", null); f.dirty = true; return; }
                open.open = false; f.dirty = true;
            }
            if (t == null) t = f;
            if (!t.enabled) return;
            f.focus = t; pressedCtl = t; t.pressed = true; f.dirty = true;
            fire(t, "MouseLeftButtonDown", null);
            fire(t, "MouseDown", eventArgs("MouseEventArgs", "X", x - absX(t), "Y", y - absY(t), "Button", 0x100000, "Clicks", 1));
            if (t.kind.equals("TextBox")) t.caret = t.text.length();
            if (t.kind.equals("ListBox")) { int i = (y - absY(t) - 2) / 16; if (i >= 0 && i < t.items.size()) { t.sel = i; fire(t, "SelectedIndexChanged", null); fire(t, "SelectionChanged", null); } }
        } else if (msg == 0x202) {
            Ctl p = pressedCtl; pressedCtl = null;
            if (p == null) return;
            p.pressed = false; f.dirty = true;
            fire(p, "MouseUp", eventArgs("MouseEventArgs", "X", x - absX(p), "Y", y - absY(p), "Button", 0x100000, "Clicks", 1));
            if (t == p && p.enabled) click(p, f);
        } else if (msg == 0x200 && t != null) {
            fire(t, "MouseMove", eventArgs("MouseEventArgs", "X", x - absX(t), "Y", y - absY(t), "Button", 0, "Clicks", 0));
        }
    }

    private Ctl openCombo(Ctl c) { if (c.kind.equals("ComboBox") && c.open) return c; for (Ctl k : c.kids) { Ctl r = openCombo(k); if (r != null) return r; } return null; }

    private void click(Ctl c, Ctl f) {
        switch (c.kind) {
            case "CheckBox": c.checked = !c.checked; fire(c, "CheckedChanged", null); fire(c, c.checked ? "Checked" : "Unchecked", null); break;
            case "RadioButton": if (!c.checked) { if (c.parent != null) for (Ctl s : c.parent.kids) if (s.kind.equals("RadioButton")) s.checked = false; c.checked = true; fire(c, "CheckedChanged", null); } break;
            case "ComboBox": c.open = !c.open; break;
        }
        fire(c, "Click", eventArgs("MouseEventArgs", "X", 0, "Y", 0, "Button", 0x100000, "Clicks", 1));
        f.dirty = true;
        if (c.kind.equals("Button") && c.props.get("DialogResult") instanceof Number && ((Number) c.props.get("DialogResult")).intValue() != 0) { f.dialogResult = ((Number) c.props.get("DialogResult")).intValue(); closeForm(f); }
    }

    private void key(int vk, boolean down, int ch) {
        Ctl f = activeForm();
        if (f == null || !down) return;
        Ctl t = f.focus;
        if (t == null) { t = f; }
        ClrObject ke = eventArgs("KeyEventArgs", "KeyCode", vk, "KeyValue", vk, "Handled", 0, "Control", 0, "Shift", 0, "Alt", 0, "KeyData", vk);
        fire(f, "KeyDown", ke);
        if (t != f) fire(t, "KeyDown", ke);
        if (num(((Props) ke.nativeState).m.get("Handled")) != 0) return;
        if (ch != 0) {
            ClrObject kp = eventArgs("KeyPressEventArgs", "KeyChar", ch, "Handled", 0);
            fire(f, "KeyPress", kp);
            if (t != f) fire(t, "KeyPress", kp);
            if (num(((Props) kp.nativeState).m.get("Handled")) != 0) return;
        }
        if (t.kind.equals("TextBox") || t.kind.equals("RichTextBox")) {
            String s = t.text;
            if (ch == 8) { if (!s.isEmpty()) { t.text = s.substring(0, s.length() - 1); fire(t, "TextChanged", null); } }
            else if (ch == '\r') { if (t.multiline) { t.text = s + "\r\n"; fire(t, "TextChanged", null); } else if (f.acceptButton != null) click(f.acceptButton, f); }
            else if (ch >= 32) { t.text = s + (char) ch; fire(t, "TextChanged", null); }
        } else if (t.kind.equals("Button") && (ch == '\r' || ch == ' ')) click(t, f);
        else if ((t.kind.equals("CheckBox") || t.kind.equals("RadioButton")) && ch == ' ') click(t, f);
        else if (ch == '\r' && f.acceptButton != null) click(f.acceptButton, f);
        else if (ch == 27) { for (Ctl k : allCtl(f)) if (k == f.props.get("CancelButton")) click(k, f); }
        else if (t.kind.equals("ListBox") && (vk == 38 || vk == 40)) { t.sel = Math.max(0, Math.min(t.items.size() - 1, t.sel + (vk == 40 ? 1 : -1))); fire(t, "SelectedIndexChanged", null); }
        f.dirty = true;
    }

    private ArrayList<Ctl> allCtl(Ctl c) { ArrayList<Ctl> r = new ArrayList<>(); for (Ctl k : c.kids) { r.add(k); r.addAll(allCtl(k)); } return r; }

    // ------------------------------------------------------------ layout & painting
    private Rc layoutRect(Ctl k) {
        if (k.dock == 0 || k.parent == null) return new Rc(k.x, k.y, k.w, k.h);
        Ctl p = k.parent;
        int pw = p.isForm ? p.cw : p.w, ph = p.isForm ? p.ch : p.h;
        int l = 0, t = 0, r = pw, b = ph;
        for (Ctl s : p.kids) {
            if (!s.visible || s.dock == 0) continue;
            int sw = s.w, sh = s.h;
            Rc res;
            switch (s.dock) {
                case 1: res = new Rc(l, t, r - l, sh); t += sh; break;
                case 2: res = new Rc(l, b - sh, r - l, sh); b -= sh; break;
                case 3: res = new Rc(l, t, sw, b - t); l += sw; break;
                case 4: res = new Rc(r - sw, t, sw, b - t); r -= sw; break;
                default: res = new Rc(l, t, r - l, b - t);
            }
            if (s == k) return res;
        }
        return new Rc(k.x, k.y, k.w, k.h);
    }

    private void paintForm(Ctl f) {
        if (f.props.containsKey("wpf") && wpf != null) wpf.layoutWindow(f);
        Surface s = f.hw.surface();
        s.fillRect(0, 0, s.width(), s.height(), f.back < 0 ? 0xF0F0F0 : f.back);
        for (Ctl k : f.kids) paintCtl(s, k, 0, 0);
        Ctl open = openCombo(f);
        if (open != null) {
            int ax = absX(open), ay = absY(open) + open.h;
            s.rect(ax, ay, open.w, open.items.size() * 16 + 2, 0xFFFFFF, 0x646464, 1);
            for (int i = 0; i < open.items.size(); i++) { if (i == open.sel) s.fillRect(ax + 1, ay + 1 + i * 16, open.w - 2, 16, 0x3399FF); s.text(vm.bcl.str(open.items.get(i)), ax + 3, ay + 2 + i * 16, i == open.sel ? 0xFFFFFF : 0, -1, 13, false, false); }
        }
        // Paint event / OnPaint
        Gfx g = new Gfx(s, 0, 0);
        if (f.ev.containsKey("Paint") || userOverrides(f.obj, "OnPaint")) {
            ClrObject pe = eventArgs("PaintEventArgs", "Graphics", g, "ClipRectangle", new Rc(0, 0, f.cw, f.ch));
            if (userOverrides(f.obj, "OnPaint")) vm.callByName(f.obj, "OnPaint", pe); else fire(f, "Paint", pe);
        }
        for (Ctl k : allCtl(f)) if (k.ev.containsKey("Paint") && k.visible) { fire(k, "Paint", eventArgs("PaintEventArgs", "Graphics", new Gfx(s, absX(k), absY(k)), "ClipRectangle", new Rc(0, 0, k.w, k.h))); }
        s.flush();
    }

    private boolean userOverrides(ClrObject o, String name) {
        for (ClrType t = o.type; t != null && !t.isNative; t = t.base) for (ClrMethod m : t.methods) if (m.name.equals(name) && !m.isStatic) return true;
        return false;
    }

    private void paintCtl(Surface s, Ctl k, int ox, int oy) {
        if (!k.visible) return;
        Rc r = layoutRect(k);
        int x = ox + r.x, y = oy + r.y, w = r.w, h = r.h;
        int fh = k.fontPx;
        int fg = k.fore < 0 ? (k.enabled ? 0x000000 : 0x6D6D6D) : k.fore;
        switch (k.kind) {
            case "Button": {
                int face = k.pressed ? 0xCCE4F7 : 0xE1E1E1;
                s.rect(x, y, w, h, k.back >= 0 ? k.back : face, k.pressed ? 0x005499 : 0xADADAD, 1);
                int tw = s.textWidth(k.text, fh, false);
                s.text(k.text, x + (w - tw) / 2, y + (h - fh) / 2, fg, -1, fh, false, false);
                break;
            }
            case "Label": case "LinkLabel": {
                if (k.back >= 0) s.fillRect(x, y, w, h, k.back);
                int al = k.props.get("TextAlign") instanceof Number ? ((Number) k.props.get("TextAlign")).intValue() : 1;
                int tw = s.textWidth(k.text, fh, false);
                int tx = (al == 32 || al == 2 || al == 512) ? x + (w - tw) / 2 : (al == 64 || al == 4 || al == 1024) ? x + w - tw : x;
                int ty = (al == 32 || al == 16 || al == 64) ? y + (h - fh) / 2 : y;
                String[] lines = k.text.split("\r?\n");
                for (int i = 0; i < lines.length; i++) s.text(lines[i], tx, ty + i * (fh + 2), k.kind.equals("LinkLabel") ? 0x0066CC : fg, -1, fh, false, false);
                break;
            }
            case "TextBox": case "RichTextBox": case "MaskedTextBox": {
                s.rect(x, y, w, h, k.back >= 0 ? k.back : 0xFFFFFF, 0x7A7A7A, 1);
                String t = k.password ? "*".repeat(k.text.length()) : k.text;
                String[] lines = t.split("\r?\n", -1);
                for (int i = 0; i < lines.length && (i == 0 || k.multiline); i++) s.text(lines[i], x + 3, y + 3 + i * (fh + 2), fg, -1, fh, false, false);
                Ctl f = formOf(k);
                if (f != null && f.focus == k) { int ly = k.multiline ? lines.length - 1 : 0; int cx = x + 3 + s.textWidth(lines[Math.max(0, lines.length - 1)], fh, false); s.fillRect(cx, y + 3 + ly * (fh + 2), 1, fh, 0); }
                break;
            }
            case "CheckBox": case "RadioButton": {
                boolean rb = k.kind.equals("RadioButton");
                int by = y + (h - 13) / 2;
                if (rb) { s.ellipse(x, by, 13, 13, 0xFFFFFF, 0x333333, 1); if (k.checked) s.ellipse(x + 3, by + 3, 7, 7, 0x1A1A1A, -1, 1); }
                else { s.rect(x, by, 13, 13, 0xFFFFFF, 0x333333, 1); if (k.checked) { s.line(x + 3, by + 6, x + 5, by + 9, 0x1A1A1A, 2); s.line(x + 5, by + 9, x + 10, by + 3, 0x1A1A1A, 2); } }
                s.text(k.text, x + 18, y + (h - fh) / 2, fg, -1, fh, false, false);
                break;
            }
            case "ListBox": case "CheckedListBox": {
                s.rect(x, y, w, h, 0xFFFFFF, 0x828790, 1);
                for (int i = 0; i < k.items.size() && 2 + (i + 1) * 16 <= h + 16; i++) {
                    if (i == k.sel) s.fillRect(x + 1, y + 2 + i * 16, w - 2, 16, 0x3399FF);
                    s.text(vm.bcl.str(k.items.get(i)), x + 3, y + 3 + i * 16, i == k.sel ? 0xFFFFFF : fg, -1, 13, false, false);
                }
                break;
            }
            case "ComboBox": {
                s.rect(x, y, w, h, 0xFFFFFF, 0x7A7A7A, 1);
                s.text(k.text, x + 3, y + (h - fh) / 2, fg, -1, fh, false, false);
                s.fillRect(x + w - 17, y + 1, 16, h - 2, 0xE5E5E5);
                s.polygon(new int[]{x + w - 12, x + w - 6, x + w - 9}, new int[]{y + h / 2 - 2, y + h / 2 - 2, y + h / 2 + 2}, 3, true, 0x333333, -1, 1);
                break;
            }
            case "GroupBox": {
                s.rect(x, y + 7, w, h - 7, -1, 0xDCDCDC, 1);
                int tw = s.textWidth(k.text, fh, false);
                s.fillRect(x + 6, y, tw + 4, fh, 0xF0F0F0);
                s.text(k.text, x + 8, y, fg, -1, fh, false, false);
                break;
            }
            case "Panel": case "FlowLayoutPanel": case "TableLayoutPanel": case "UserControl": case "TabPage":
                if (k.back >= 0) s.fillRect(x, y, w, h, k.back);
                if (k.props.get("bc") instanceof Integer && k.props.get("bt") instanceof Integer && (Integer) k.props.get("bt") > 0) s.rect(x, y, w, h, -1, (Integer) k.props.get("bc"), (Integer) k.props.get("bt"));
                break;
            case "PictureBox": s.rect(x, y, w, h, k.back >= 0 ? k.back : 0xF0F0F0, -1, 1); break;
            case "ProgressBar": {
                s.rect(x, y, w, h, 0xE6E6E6, 0xBCBCBC, 1);
                int span = Math.max(1, k.max - k.min);
                s.fillRect(x + 1, y + 1, (w - 2) * (k.value - k.min) / span, h - 2, 0x06B025);
                break;
            }
            case "MenuStrip": case "StatusStrip": case "ToolStrip": s.fillRect(x, y, w, h, 0xF0F0F0); break;
            default: if (k.back >= 0) s.fillRect(x, y, w, h, k.back);
        }
        for (Ctl c : k.kids) paintCtl(s, c, x, y);
    }

    // ------------------------------------------------------------ Graphics
    private int rgbOf(Object o) { if (o instanceof Props) return ((Number) ((Props) o).m.get("c")).intValue() & 0xFFFFFF; if (o instanceof Col) return ((Col) o).rgb(); return 0; }

    private Object onGfx(Gfx g, String n, Object[] a) {
        Surface s = g.s; int ox = g.ox, oy = g.oy;
        switch (n) {
            case "Clear": s.fillRect(0, 0, s.width(), s.height(), rgbOf(a[1])); return null;
            case "FillRectangle": { int[] b = box(a, 2); s.fillRect(ox + b[0], oy + b[1], b[2], b[3], rgbOf(a[1])); return null; }
            case "DrawRectangle": { int[] b = box(a, 2); s.rect(ox + b[0], oy + b[1], b[2] + 1, b[3] + 1, -1, rgbOf(a[1]), penW(a[1])); return null; }
            case "FillEllipse": { int[] b = box(a, 2); s.ellipse(ox + b[0], oy + b[1], b[2], b[3], rgbOf(a[1]), -1, 1); return null; }
            case "DrawEllipse": { int[] b = box(a, 2); s.ellipse(ox + b[0], oy + b[1], b[2], b[3], -1, rgbOf(a[1]), penW(a[1])); return null; }
            case "DrawLine": { if (a[2] instanceof Pt) s.line(ox + ((Pt) a[2]).x, oy + ((Pt) a[2]).y, ox + ((Pt) a[3]).x, oy + ((Pt) a[3]).y, rgbOf(a[1]), penW(a[1])); else s.line(ox + num(a[2]), oy + num(a[3]), ox + num(a[4]), oy + num(a[5]), rgbOf(a[1]), penW(a[1])); return null; }
            case "DrawString": {
                String t = (String) a[1]; Props font = a[2] instanceof ClrObject ? (Props) ((ClrObject) a[2]).nativeState : (Props) a[2];
                int fh = font != null && font.m.get("size") instanceof Number ? (int) Math.round(((Number) font.m.get("size")).doubleValue() * 1.71) : FH;
                int x = a[4] instanceof Pt ? ((Pt) a[4]).x : num(a[4]), y = a[4] instanceof Pt ? ((Pt) a[4]).y : num(a[5]);
                if (a[4] instanceof Rc) { x = ((Rc) a[4]).x; y = ((Rc) a[4]).y; }
                s.text(t, ox + x, oy + y, rgbOf(a[3]), -1, fh, false, false);
                return null;
            }
            case "MeasureString": { String t = (String) a[1]; return new Sz(s.textWidth(t, FH, false), FH + 2); }
            case "DrawPolygon": case "FillPolygon": { Object[] pts = (Object[]) a[2]; int[] xs = new int[pts.length], ys = new int[pts.length]; for (int i = 0; i < pts.length; i++) { xs[i] = ox + ((Pt) pts[i]).x; ys[i] = oy + ((Pt) pts[i]).y; } boolean fill = n.startsWith("Fill"); s.polygon(xs, ys, pts.length, true, fill ? rgbOf(a[1]) : -1, fill ? -1 : rgbOf(a[1]), 1); return null; }
            case "DrawImage": case "SetClip": case "ResetClip": case "Dispose": case "TranslateTransform": return null;
        }
        return null;
    }

    private int penW(Object p) { return p instanceof Props && ((Props) p).m.get("w") instanceof Number ? ((Number) ((Props) p).m.get("w")).intValue() : 1; }
    private int[] box(Object[] a, int i) {
        if (a[i] instanceof Rc) { Rc r = (Rc) a[i]; return new int[]{r.x, r.y, r.w, r.h}; }
        return new int[]{num(a[i]), num(a[i + 1]), num(a[i + 2]), num(a[i + 3])};
    }
}
