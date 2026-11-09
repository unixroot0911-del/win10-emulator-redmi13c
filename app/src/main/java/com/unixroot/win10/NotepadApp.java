package com.unixroot.win10;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;

/** Plain text editor. */
final class NotepadApp extends LinearLayout {
    private final Desktop d;
    private final WinFrame w;
    private final EditText edit;
    private File file;

    NotepadApp(Desktop desk, WinFrame win, String path) {
        super(desk.act);
        d = desk;
        w = win;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(d.act);
        bar.setBackgroundColor(0xFFEEEEEE);
        Button save = Ui.button(d.act, "Save", Ui.ACCENT, Color.WHITE);
        Button saveAs = Ui.button(d.act, "Save as...", 0xFF666666, Color.WHITE);
        Button clear = Ui.button(d.act, "New", 0xFF666666, Color.WHITE);
        save.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { save(); } });
        saveAs.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { saveAs(); } });
        clear.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { file = null; edit.setText(""); w.setTitle("Untitled - Notepad"); } });
        bar.addView(save, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 4, 3, 2, 3));
        bar.addView(saveAs, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 2, 3, 2, 3));
        bar.addView(clear, Ui.lpMargins(d.act, Ui.WRAP, Ui.WRAP, 0, 2, 3, 4, 3));
        addView(bar, new LayoutParams(Ui.MATCH, Ui.WRAP));

        edit = new EditText(d.act);
        edit.setTextColor(Color.BLACK);
        edit.setBackground(null);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextSize(13);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setPadding(Ui.dp(d.act, 6), Ui.dp(d.act, 6), Ui.dp(d.act, 6), Ui.dp(d.act, 6));
        addView(edit, Ui.lp(Ui.MATCH, 0, 1));

        if (path != null) {
            File f = d.vfs.resolve(path);
            if (f.isFile()) { file = f; edit.setText(Vfs.readText(f)); w.setTitle(f.getName() + " - Notepad"); }
        }
    }

    private void save() {
        if (file == null) { saveAs(); return; }
        Toast.makeText(d.act, Vfs.writeText(file, edit.getText().toString()) ? "Saved" : "Save failed", Toast.LENGTH_SHORT).show();
    }

    private void saveAs() {
        final EditText name = new EditText(d.act);
        name.setText(file != null ? d.vfs.toWin(file) : "C:\\Users\\Public\\Documents\\note.txt");
        new AlertDialog.Builder(d.act).setTitle("Save as").setView(name)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        String p = name.getText().toString().trim();
                        if (p.isEmpty()) return;
                        if (!p.contains("\\") && !p.contains(":")) p = "C:\\Users\\Public\\Documents\\" + p;
                        File f = d.vfs.resolve(p);
                        if (f.getParentFile() != null) f.getParentFile().mkdirs();
                        if (Vfs.writeText(f, edit.getText().toString())) {
                            file = f;
                            w.setTitle(f.getName() + " - Notepad");
                            d.fsChanged();
                        } else Toast.makeText(d.act, "Save failed", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
