package com.unixroot.win10.core;

/** Window system services provided by the host application. */
public interface GuiHost {
    interface HostWindow {
        Surface surface();
        void setTitle(String title);
        void setVisible(boolean visible);
        void close();
    }

    /** Creates a top-level window whose client area is cw x ch pixels. Called from the emulator thread. */
    HostWindow createWindow(Win32Gui gui, int hwnd, String title, int cw, int ch);

    /** Creates an off-screen surface (memory DC bitmap). */
    Surface createSurface(int w, int h);

    /** Shows a message box and blocks until it is dismissed. */
    void messageBox(String title, String text);
}
