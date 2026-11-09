package com.unixroot.win10;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 42;
    private Desktop desktop;
    private Vfs vfs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        vfs = new Vfs(this);
        desktop = new Desktop(this, vfs);
        setContentView(desktop);
        immersive();
        Net.applyWebViewProxy(this);
        desktop.post(new Runnable() {
            @Override public void run() { desktop.open("Terminal", null); }
        });
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @SuppressWarnings("deprecation")
    void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = "imported.bin";
        Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            try { if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0); } finally { c.close(); }
        }
        name = name.replace("/", "_").replace("\\", "_");
        File dst = vfs.resolve("C:\\Users\\Public\\Downloads\\" + name);
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            Toast.makeText(this, "Imported to C:\\Users\\Public\\Downloads\\" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        desktop.fsChanged();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        desktop.handleBack();
    }
}
