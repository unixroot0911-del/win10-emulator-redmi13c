package com.unixroot.win10.clr;

import com.unixroot.win10.clr.Model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;

/** System.Xml.Linq (XDocument/XElement/XAttribute/XName), System.Diagnostics.StackTrace/StackFrame/MethodBase. */
final class BclXml implements Bcl.Ext {
    private static final String L = "System.Xml.Linq.";
    final Vm vm;
    BclXml(Vm vm) { this.vm = vm; }

    static final class XDoc { Document d; XDoc(Document d) { this.d = d; } }
    static final class XEl { Element e; XEl(Element e) { this.e = e; } @Override public String toString() { return serialize(e); } }
    static final class XAt { Element owner; String name; String value; XAt(Element o, String n, String v) { owner = o; name = n; value = v; } @Override public String toString() { return name + "=\"" + value + "\""; } }
    static final class Frame { final ClrType t; Frame(ClrType t) { this.t = t; } }
    static final class MInfo { final ClrType t; final String name; MInfo(ClrType t, String n) { this.t = t; name = n; } }
    static final class Trace { }

    @Override public Object getStatic(Vm vm, FieldInfo f) { return Bcl.NONE; }

    private static String local(String s) { int i = s.indexOf(':'); return i < 0 ? s : s.substring(i + 1); }
    private static String nm(Object o) { return o == null ? null : local(o.toString()); }

    static String serialize(Node n) {
        try {
            javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
            java.io.StringWriter w = new java.io.StringWriter();
            t.transform(new javax.xml.transform.dom.DOMSource(n), new javax.xml.transform.stream.StreamResult(w));
            return w.toString().trim();
        } catch (Exception e) { return String.valueOf(n.getTextContent()); }
    }

    private Document parse(byte[] data) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(data));
        } catch (Exception e) { throw vm.makeThrow("System.Xml.XmlException", String.valueOf(e.getMessage())); }
    }

    private Document newDoc() { try { return javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument(); } catch (Exception e) { throw new RuntimeException(e); } }

    private byte[] readSource(Object src) {
        if (src instanceof String) {
            String s = (String) src;
            try { return java.nio.file.Files.readAllBytes(com.unixroot.win10.core.WinPath.resolve(vm.fsRoot, vm.cwd, s).toPath()); }
            catch (java.io.IOException e) { throw vm.makeThrow("System.IO.FileNotFoundException", "Could not find file '" + s + "'."); }
        }
        throw vm.makeThrow("System.NotSupportedException", "XML source type not supported: " + (src == null ? "null" : src.getClass().getSimpleName()));
    }

    private ArrayList<Object> children(Element e, String name) {
        ArrayList<Object> r = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element && (name == null || local(n.getNodeName()).equals(name))) r.add(new XEl((Element) n));
        return r;
    }
    private void descendants(Element e, String name, ArrayList<Object> out) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) { if (name == null || local(n.getNodeName()).equals(name)) out.add(new XEl((Element) n)); descendants((Element) n, name, out); }
    }
    private ArrayList<Object> attrs(Element e) {
        ArrayList<Object> r = new ArrayList<>();
        org.w3c.dom.NamedNodeMap m = e.getAttributes();
        for (int i = 0; i < m.getLength(); i++) r.add(new XAt(e, m.item(i).getNodeName(), m.item(i).getNodeValue()));
        return r;
    }

    private void addContent(Element parent, Object c) {
        if (c == null) return;
        Document d = parent.getOwnerDocument();
        if (c instanceof XEl) parent.appendChild(d.importNode(((XEl) c).e, true));
        else if (c instanceof XAt) parent.setAttribute(((XAt) c).name, ((XAt) c).value);
        else if (c instanceof Object[]) for (Object o : (Object[]) c) addContent(parent, o);
        else if (c instanceof Iterable && !(c instanceof String)) for (Object o : (Iterable<?>) c) addContent(parent, o);
        else parent.appendChild(d.createTextNode(vm.bcl.str(c)));
    }

    private static int retElem(ClrMethod m) {
        if (m.sig == null || m.sig.length < 3) return 0x1C;
        int i = 1; if ((m.sig[0] & 0x10) != 0) i++;
        int pc = m.sig[i] & 255; i += (pc & 0x80) != 0 ? 2 : 1;
        return i < m.sig.length ? m.sig[i] & 255 : 0x1C;
    }

    private Object convert(String s, int ret) {
        if (s == null) return ret == 0x0E || ret == 0x1C ? null : (Object) 0;
        try {
            switch (ret) {
                case 0x02: return s.trim().equalsIgnoreCase("true") || s.trim().equals("1") ? 1 : 0;
                case 0x08: case 0x09: case 0x06: case 0x07: case 0x04: case 0x05: case 0x03: return Integer.parseInt(s.trim());
                case 0x0A: case 0x0B: return Long.parseLong(s.trim());
                case 0x0C: case 0x0D: return Double.parseDouble(s.trim());
            }
        } catch (NumberFormatException e) { throw vm.makeThrow("System.FormatException", "Input string was not in a correct format."); }
        return s;
    }

    private ClrType entryType() {
        Model.Image img = vm.main;
        for (java.util.Map.Entry<String, Integer> en : img.typeByName.entrySet()) {
            try { ClrType t = vm.typeDef(img, en.getValue()); for (ClrMethod m : t.methods) if (m.isStatic && m.name.equals("Main")) return t; } catch (RuntimeException e) { /* skip */ }
        }
        return null;
    }

    @Override public Object call(Vm vm, ClrMethod m, Object[] a) {
        String owner = m.owner == null ? "" : m.owner.full, n = m.name;
        Object recv = m.hasThis && a.length > 0 ? a[0] : null;
        if (owner.startsWith(L) || recv instanceof XDoc || recv instanceof XEl || recv instanceof XAt) return xml(m, owner, n, recv, a);
        if (owner.equals("System.Diagnostics.StackTrace") || owner.equals("System.Diagnostics.StackFrame") || owner.equals("System.Reflection.MethodBase") || owner.equals("System.Reflection.MethodInfo")
                || recv instanceof Trace || recv instanceof Frame || recv instanceof MInfo) {
            if (n.equals(".ctor")) { if (owner.endsWith("StackFrame")) return new Frame(entryType()); return new Trace(); }
            switch (n) {
                case "GetFrame": return new Frame(entryType());
                case "GetFrames": return new Object[]{new Frame(entryType())};
                case "get_FrameCount": return 1;
                case "ToString": return "   at " + (entryType() == null ? "Main" : entryType().full + ".Main()");
                case "GetMethod": return new MInfo(entryType(), "Main");
                case "GetCurrentMethod": return new MInfo(entryType(), "Main");
                case "get_Name": return recv instanceof MInfo ? ((MInfo) recv).name : "";
                case "get_DeclaringType": case "get_ReflectedType": return recv instanceof MInfo ? ((MInfo) recv).t : null;
                case "GetFileName": return null; case "GetFileLineNumber": case "GetFileColumnNumber": case "GetILOffset": return 0;
            }
        }
        return Bcl.NONE;
    }

    private Object xml(ClrMethod m, String owner, String n, Object recv, Object[] a) {
        String kind = owner.startsWith(L) ? owner.substring(L.length()) : "";
        if (n.equals(".ctor")) {
            switch (kind) {
                case "XElement": case "XStreamingElement": { Document d = newDoc(); Element e = d.createElement(nm(a[1])); d.appendChild(e); if (a.length > 2) for (int i = 2; i < a.length; i++) addContent(e, a[i]); return new XEl(e); }
                case "XAttribute": return new XAt(null, nm(a[1]), a[2] == null ? "" : vm.bcl.str(a[2]));
                case "XDocument": { Document d = newDoc(); XDoc x = new XDoc(d); for (int i = 1; i < a.length; i++) if (a[i] instanceof XEl) d.appendChild(d.importNode(((XEl) a[i]).e, true)); return x; }
            }
            return Bcl.NONE;
        }
        if (!m.hasThis) {
            switch (kind + "::" + n) {
                case "XDocument::Load": return new XDoc(parse(readSource(a[0])));
                case "XDocument::Parse": return new XDoc(parse(((String) a[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                case "XElement::Load": return new XEl(parse(readSource(a[0])).getDocumentElement());
                case "XElement::Parse": return new XEl(parse(((String) a[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8)).getDocumentElement());
                case "XName::Get": return nm(a[0]);
                case "XName::op_Implicit": return nm(a[0]);
                case "XElement::op_Explicit": return convert(a[0] == null ? null : ((XEl) a[0]).e.getTextContent(), retElem(m));
                case "XAttribute::op_Explicit": return convert(a[0] == null ? null : ((XAt) a[0]).value, retElem(m));
            }
            return Bcl.NONE;
        }
        if (recv instanceof String && kind.equals("XName")) {
            switch (n) { case "get_LocalName": case "ToString": return recv; case "get_NamespaceName": return ""; case "get_Namespace": return ""; }
            return Bcl.NONE;
        }
        if (recv instanceof XDoc) {
            Document d = ((XDoc) recv).d;
            switch (n) {
                case "get_Root": return d.getDocumentElement() == null ? null : new XEl(d.getDocumentElement());
                case "Element": { Element r = d.getDocumentElement(); return r != null && local(r.getNodeName()).equals(nm(a[1])) ? new XEl(r) : null; }
                case "Elements": case "Descendants": { ArrayList<Object> r = new ArrayList<>(); Element root = d.getDocumentElement(); if (root != null) { String nme = a.length > 1 ? nm(a[1]) : null; if (nme == null || local(root.getNodeName()).equals(nme)) r.add(new XEl(root)); if (n.equals("Descendants")) descendants(root, nme, r); } return r; }
                case "Save": { try { javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer(); t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes"); t.transform(new javax.xml.transform.dom.DOMSource(d), new javax.xml.transform.stream.StreamResult(com.unixroot.win10.core.WinPath.resolve(vm.fsRoot, vm.cwd, (String) a[1]))); } catch (Exception e) { throw vm.makeThrow("System.IO.IOException", String.valueOf(e.getMessage())); } return null; }
                case "ToString": return serialize(d.getDocumentElement());
                case "Add": if (d.getDocumentElement() == null && a[1] instanceof XEl) d.appendChild(d.importNode(((XEl) a[1]).e, true)); return null;
            }
            return Bcl.NONE;
        }
        if (recv instanceof XEl) {
            Element e = ((XEl) recv).e;
            switch (n) {
                case "get_Name": return local(e.getNodeName());
                case "get_Value": return e.getTextContent();
                case "set_Value": e.setTextContent(vm.bcl.str(a[1])); return null;
                case "Attribute": { String nme = nm(a[1]); org.w3c.dom.Attr at = e.getAttributeNode(nme); if (at == null) { org.w3c.dom.NamedNodeMap mm = e.getAttributes(); for (int i = 0; i < mm.getLength(); i++) if (local(mm.item(i).getNodeName()).equals(nme)) { at = (org.w3c.dom.Attr) mm.item(i); break; } } return at == null ? null : new XAt(e, at.getName(), at.getValue()); }
                case "Attributes": return attrs(e);
                case "Elements": return children(e, a.length > 1 ? nm(a[1]) : null);
                case "Element": { ArrayList<Object> r = children(e, nm(a[1])); return r.isEmpty() ? null : r.get(0); }
                case "Descendants": { ArrayList<Object> r = new ArrayList<>(); descendants(e, a.length > 1 ? nm(a[1]) : null, r); return r; }
                case "DescendantsAndSelf": { ArrayList<Object> r = new ArrayList<>(); r.add(recv); descendants(e, a.length > 1 ? nm(a[1]) : null, r); return r; }
                case "Add": for (int i = 1; i < a.length; i++) addContent(e, a[i]); return null;
                case "SetAttributeValue": if (a[2] == null) e.removeAttribute(nm(a[1])); else e.setAttribute(nm(a[1]), vm.bcl.str(a[2])); return null;
                case "SetElementValue": { ArrayList<Object> r = children(e, nm(a[1])); if (a[2] == null) { for (Object o : r) e.removeChild(((XEl) o).e); } else if (r.isEmpty()) { Element c = e.getOwnerDocument().createElement(nm(a[1])); c.setTextContent(vm.bcl.str(a[2])); e.appendChild(c); } else ((XEl) r.get(0)).e.setTextContent(vm.bcl.str(a[2])); return null; }
                case "get_HasElements": return children(e, null).isEmpty() ? 0 : 1;
                case "get_IsEmpty": return e.getFirstChild() == null ? 1 : 0;
                case "get_Parent": return e.getParentNode() instanceof Element ? new XEl((Element) e.getParentNode()) : null;
                case "Remove": if (e.getParentNode() != null) e.getParentNode().removeChild(e); return null;
                case "ToString": return serialize(e);
                case "Save": { try { javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer(); t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes"); t.transform(new javax.xml.transform.dom.DOMSource(e), new javax.xml.transform.stream.StreamResult(com.unixroot.win10.core.WinPath.resolve(vm.fsRoot, vm.cwd, (String) a[1]))); } catch (Exception x) { throw vm.makeThrow("System.IO.IOException", String.valueOf(x.getMessage())); } return null; }
                case "GetHashCode": return System.identityHashCode(e);
                case "Equals": return a[1] instanceof XEl && ((XEl) a[1]).e == e ? 1 : 0;
            }
            return Bcl.NONE;
        }
        if (recv instanceof XAt) {
            XAt at = (XAt) recv;
            switch (n) {
                case "get_Name": return local(at.name);
                case "get_Value": return at.value;
                case "set_Value": at.value = vm.bcl.str(a[1]); if (at.owner != null) at.owner.setAttribute(at.name, at.value); return null;
                case "Remove": if (at.owner != null) at.owner.removeAttribute(at.name); return null;
                case "ToString": return at.toString();
            }
        }
        return Bcl.NONE;
    }
}
