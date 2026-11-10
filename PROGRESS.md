# PROGRESS
## Done
- Pure-Java x86-32 interpreter (integer, x87, SSE/SSE2), PE32 loader (relocs, imports, exports, TLS, DLLs from exe folder/System32/cwd), ~250 kernel32/UCRT/msvcrt APIs.
- Win32Gui (user32/gdi32/winmm subset): windows, messages, timers, paint, BUTTON/STATIC/EDIT, GDI. Tested headless with AWT (tools/tests/GTest.java + t_gui.c).
- Android: desktop UI, Terminal, Files (import files/folder), Notepad, Calc, Browser (proxy), Settings, NativeWindow (bitmap view + key bar).
- Exe runs with its folder as cwd/exeDir. CI builds APK -> GitHub Release (latest v1.0-8, run 8 success).
- Tests: tools/tests/run_tests.sh differential (zig vs gcc) 9/9.
## Decisions
- No threads (CreateThread synchronous), no Winsock yet, no DirectX. Not tested on a real phone.
## Next
See TASKS.md. Token used was pasted in chat: user must revoke it.

## .NET (in progress)
- clr/Metadata.java done and verified. Next: TASKS.md item 0b. ModAPI.exe currently reports Unimplemented API _CorExeMain (expected).
- CLR interpreter works on generated demo (loops, recursion, virtual calls, try/catch/finally, strings). Not yet tested: generics-heavy code, WinForms, real-world assemblies. Next: WinForms ext (Bcl.Ext) on Win32Gui/Surface.
