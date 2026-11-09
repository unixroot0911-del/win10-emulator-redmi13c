package com.unixroot.win10;

import android.graphics.Color;
import android.widget.ScrollView;
import android.widget.TextView;

/** About / honest capability list. */
final class AboutApp extends ScrollView {
    AboutApp(Desktop d) {
        super(d.act);
        setBackgroundColor(0xFF1E1E1E);
        TextView t = Ui.label(d.act, "Win10 Emulator 1.0\n\n"
                + "Everything here is original code: the desktop, the apps and the x86 emulator.\n\n"
                + "x86 core\n"
                + "  - 32-bit integer CPU interpreter (most user-mode instructions)\n"
                + "  - PE32 loader with import table\n"
                + "  - Win32/CRT API layer: console, files, heap, time, MessageBox, printf, malloc...\n\n"
                + "What runs\n"
                + "  - Small 32-bit console programs that use those APIs\n\n"
                + "What does not run (yet)\n"
                + "  - Programs needing the full Windows DLL set (GUI apps, Chrome, games)\n"
                + "  - 64-bit programs, floating point / SSE instructions, threads, DirectX\n\n"
                + "Use Files > Import to bring your own .exe, then run it in Terminal. Unknown APIs are reported by name so they can be added.\n", 13, Color.WHITE);
        int p = Ui.dp(d.act, 14);
        t.setPadding(p, p, p, p);
        addView(t);
    }
}
