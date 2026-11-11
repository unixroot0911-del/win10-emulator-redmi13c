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
- WinForms ext (clr/WinForms.java): Form/Button/Label/TextBox/CheckBox/RadioButton/ListBox/ComboBox/Panel/GroupBox/ProgressBar/Timer, Graphics, MessageBox, events, Application.Run. Verified with IL demo wf.exe (tools/tests/ClrGui.java). Untested: real apps, menus (MenuStrip), dialogs, generics-heavy code, threads.
- Winsock (core/WinSock.java): socket/connect/send/recv/bind/listen/accept/getaddrinfo/gethostbyname via java.net; proxy: HTTP CONNECT or SOCKS from Settings (Net.applyToWinSock). Verified vs local HTTP server and CONNECT proxy (tools/tests/t_net.c). netdemo.exe sample: `netdemo.exe host port`. Managed (.NET) sockets/WebClient/HttpWebRequest NOT yet done.

## WPF layer (Wpf.java)
- Done: XAML-text loader, layout engine (Grid/StackPanel/DockPanel/Canvas/WrapPanel/Border), WPF element API on WinForms painter, code-behind handler binding. Verified headless (tools/tests XamlGui harness -> PNG).
- NOT done: BAML reader (Application.LoadComponent returns unimplemented), styles/templates/bindings/resources, shapes drawing. Real WPF apps (e.g. ModAPI.exe) still likely fail at LoadComponent.
- Next: BAML parser (record types from memory, no sample data), then styles/resources.
- System.Uri/UriKind implemented (BclExt). ModAPI.exe next failure unknown; iterate on 'Unimplemented .NET' messages from the user's screenshots.

## BAML loader (Baml.java, BamlTables.java)
- Ported from dotnet/wpf Baml2006Reader (fetched from GitHub raw; KnownTypes tables generated into BamlTables.java). Baml -> XAML text -> Wpf.loadXaml onto existing code-behind object; ConnectionId -> IComponentConnector.Connect(id, element) so event hookups from guest code run. StartupUri -> creates window class from BAML root type. .resources container reader in Baml.resources().
- Verified only with a synthetic BAML written by a Python writer (self-consistent), NOT with a real compiled WPF app. Set env WPF_DEBUG=1 to print generated XAML.
- Ignored: styles/templates/resources/bindings/StaticResource, shapes, many enum properties (only HA/VA/Visibility/Orientation/Dock/TextAlignment mapped).
- Next: iterate on real ModAPI.exe errors from user screenshots.

## Updates over previous install
- APK now signed with fixed key app/update-key.jks (committed on purpose; before this each CI run used a random debug key). versionCode = GitHub run number. First build with this key requires ONE uninstall of older versions; later builds update in place.
