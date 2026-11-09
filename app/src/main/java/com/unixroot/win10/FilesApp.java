package com.unixroot.win10;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** File explorer for drive C:. */
final class FilesApp extends LinearLayout implements Desktop.FsListener {
    private final Desktop d;
    private final WinFrame w;
    private final TextView pathView;
    private final ListView list;
    private final ArrayAdapter<String> adapter;
    private final ArrayList<File> entries = new ArrayList<>();
    private File dir;

    FilesApp(Desktop desk, WinFrame win, String path) {
        super(desk.act);
        d = desk;
        w = win;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF202020);
        dir = d.vfs.resolve(path != null ? path : "C:\\Users\\Public");
        if (!dir.isDirectory()) dir = d.vfs.root;

        LinearLayout bar = new LinearLayout(d.act);
        bar.setBackgroundColor(0xFF2D2D2D);
        bar.addView(btn("\u2B06 Up", new OnClickListener() { @Override public void onClick(View v) { up(); } }));
        bar.addView(btn("+ Folder", new OnClickListener() { @Override public void onClick(View v) { newFolder(); } }));
        bar.addView(btn("Import", new OnClickListener() { @Override public void onClick(View v) { d.act.pickFile(); } }));
        bar.addView(btn("\u21BB", new OnClickListener() { @Override public void onClick(View v) { refresh(); } }));
        addView(bar, new LayoutParams(Ui.MATCH, Ui.WRAP));

        pathView = Ui.label(d.act, "", 12, 0xFFBBBBBB);
        pathView.setPadding(Ui.dp(d.act, 8), Ui.dp(d.act, 4), Ui.dp(d.act, 8), Ui.dp(d.act, 4));
        addView(pathView, new LayoutParams(Ui.MATCH, Ui.WRAP));

        list = new ListView(d.act);
        adapter = new ArrayAdapter<String>(d.act, android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(14);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { open(entries.get(pos)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { confirmDelete(entries.get(pos)); return true; }
        });
        addView(list, Ui.lp(Ui.MATCH, 0, 1));
        refresh();
    }

    private Button btn(String text, OnClickListener l) {
        Button b = Ui.button(d.act, text, 0xFF444444, Color.WHITE);
        b.setOnClickListener(l);
        b.setLayoutParams(Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 3, 3, 3, 3));
        return b;
    }

    @Override public void onFsChanged() { refresh(); }

    private void refresh() {
        entries.clear();
        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            entries.addAll(Arrays.asList(kids));
        }
        adapter.clear();
        for (File f : entries) {
            adapter.add((f.isDirectory() ? "\uD83D\uDCC1  " : icon(f) + "  ") + f.getName() + (f.isFile() ? "   (" + size(f.length()) + ")" : ""));
        }
        adapter.notifyDataSetChanged();
        pathView.setText(d.vfs.toWin(dir));
        w.setTitle(dir.equals(d.vfs.root) ? "Files - C:\\" : "Files - " + dir.getName());
    }

    private static String icon(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".exe") ? "\u2699" : n.endsWith(".txt") ? "\uD83D\uDCC4" : "\uD83D\uDCC3";
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void up() {
        if (!dir.equals(d.vfs.root) && dir.getParentFile() != null) { dir = dir.getParentFile(); refresh(); }
    }

    private void open(File f) {
        if (f.isDirectory()) { dir = f; refresh(); return; }
        String n = f.getName().toLowerCase(Locale.US);
        String wp = d.vfs.toWin(f);
        if (n.endsWith(".exe")) d.open("Terminal", wp);
        else if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".ini") || n.endsWith(".md") || n.endsWith(".bat") || n.endsWith(".cfg") || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".csv")) d.open("Notepad", wp);
        else if (n.endsWith(".html") || n.endsWith(".htm")) d.open("Browser", "file://" + f.getAbsolutePath());
        else Toast.makeText(d.act, "No app is associated with this file type", Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(d.act).setTitle("Delete").setMessage("Delete " + f.getName() + "?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) { Vfs.deleteRec(f); d.fsChanged(); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void newFolder() {
        final EditText name = new EditText(d.act);
        name.setText("New folder");
        new AlertDialog.Builder(d.act).setTitle("New folder").setView(name)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        String n = name.getText().toString().trim().replace("\\", "").replace("/", "");
                        if (!n.isEmpty()) new File(dir, n).mkdirs();
                        d.fsChanged();
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
