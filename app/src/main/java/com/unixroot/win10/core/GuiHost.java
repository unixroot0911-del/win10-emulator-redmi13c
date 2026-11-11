package com.unixroot.win10.core;

/** Window system services provided by the host application. */
public interface GuiHost {
    /** Events delivered by the host window to the guest GUI layer. */
    interface Input {
        void postMouse(int hwnd, int msg, int x, int y);
        void postKey(int vk, boolean down, int ch);
        void windowClosed(int hwnd);
        void windowActivated(int hwnd);
    }

    interface HostWindow {
        Surface surface();
        void setTitle(String title);
        void setVisible(boolean visible);
        void close();
    }

    /** Creates a top-level window whose client area is cw x ch pixels. Called from the emulator thread. */
    HostWindow createWindow(Input gui, int hwnd, String title, int cw, int ch);

    /** Creates an off-screen surface (memory DC bitmap). */
    Surface createSurface(int w, int h);

    /** Shows a message box and blocks until it is dismissed. */
    void messageBox(String title, String text);
}
