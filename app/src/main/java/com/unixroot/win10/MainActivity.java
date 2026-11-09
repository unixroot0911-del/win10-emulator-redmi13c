package com.unixroot.win10;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 42, REQ_TREE = 43;
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
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @SuppressWarnings("deprecation")
    void pickFolder() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_PICK) {
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) importFile(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) importFile(data.getData());
            desktop.fsChanged();
        } else if (req == REQ_TREE && data.getData() != null) {
            final Uri tree = data.getData();
            Toast.makeText(this, "Importing folder...", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() { @Override public void run() {
                final String name;
                final int[] count = new int[1];
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                String nm = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), "imported_folder");
                name = nm.replace("/", "_").replace("\\", "_");
                File dst = vfs.resolve("C:\\Users\\Public\\Downloads\\" + name);
                dst.mkdirs();
                try { copyTree(tree, rootId, dst, count); } catch (Exception e) { count[0] = -1; }
                runOnUiThread(new Runnable() { @Override public void run() {
                    Toast.makeText(MainActivity.this, count[0] < 0 ? "Folder import failed" : "Imported " + count[0] + " files to C:\\Users\\Public\\Downloads\\" + name, Toast.LENGTH_LONG).show();
                    desktop.fsChanged();
                } });
            } }).start();
        }
    }

    private String displayName(Uri u, String fallback) {
        String name = fallback;
        Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            try { if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0); } finally { c.close(); }
        }
        return name;
    }

    private void importFile(Uri u) {
        String name = displayName(u, "imported.bin").replace("/", "_").replace("\\", "_");
        File dst = vfs.resolve("C:\\Users\\Public\\Downloads\\" + name);
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            Toast.makeText(this, "Imported to C:\\Users\\Public\\Downloads\\" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyTree(Uri tree, String docId, File dst, int[] count) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
        if (c == null) return;
        java.util.ArrayList<String[]> kids = new java.util.ArrayList<>();
        try { while (c.moveToNext()) kids.add(new String[]{c.getString(0), c.getString(1), c.getString(2)}); } finally { c.close(); }
        for (String[] k : kids) {
            String nm = k[1].replace("/", "_").replace("\\", "_");
            File out = new File(dst, nm);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(k[2])) { out.mkdirs(); copyTree(tree, k[0], out, count); }
            else {
                try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, k[0])); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) os.write(buf, 0, n);
                    count[0]++;
                }
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        desktop.handleBack();
    }
}
