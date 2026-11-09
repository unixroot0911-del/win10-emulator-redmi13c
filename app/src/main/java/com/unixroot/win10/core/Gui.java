package com.unixroot.win10.core;

/** Hook for the window-system part of the Win32 API (user32/gdi32), implemented by the host app. */
public abstract class Gui {
    public abstract Integer nargs(String name);
    public abstract boolean isStdcall(String name);
    public abstract int call(String name, Emulator e);
    public abstract void onExit();
}
