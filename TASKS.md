# TASKS (priority order)
0. .NET support (user request: ModAPI.exe = WinForms .NET Framework app, entry _CorExeMain). Plan:
   a. [DONE] clr/Metadata.java: PE+CLI header, all metadata tables/heaps (validated on Python.Runtime.dll).
   b/c/d(basic). [DONE] Vm/Interp/Bcl: type resolution, CIL interpreter (exceptions, delegates, structs, boxing), string/collections/Math/IO BCL. Verified with tools/make_dotnet.py demo (tools/tests/ClrTest.java). TerminalApp routes managed exes to the Vm. Unimplemented APIs throw NotSupportedException 'Unimplemented .NET API: X::Y' -> add to Bcl.
   b. Signature decoder, type/method resolution (TypeDef/TypeRef/MemberRef/TypeSpec/MethodSpec, generics).
   c. CIL decoder + interpreter (stack machine, exceptions try/catch/finally, delegates, generics, boxing).
   d. Native BCL in Java (no mscorlib available): Object, String, Console, Math, List<T>, Dictionary, StringBuilder, IO.File, ...
   e. System.Windows.Forms subset on top of Win32Gui/Surface (Form, Button, Label, TextBox, ListBox, MessageBox).
   f. Hook Emulator: if PE has CLI header (or imports _CorExeMain) run via CLR instead of x86.
   Test blocker: no C# compiler in sandbox -> write tiny IL assembler (python) or use pythonnet's Python.Runtime.dll for parsing only.
1. Verify on real device (user feedback on guidemo.exe, hello.exe, GUI input/keyboard).
2. Winsock (socket/connect/send/recv/getaddrinfo) via Java sockets + proxy.
3. Real threads (CreateThread, events, WaitForSingleObject) with per-thread Cpu.
4. More user32/gdi32 (menus, dialogs, ListBox/ComboBox, SetPixel perf), MMX.
5. Broader CRT (C++ runtime: new/delete, exceptions SEH), more kernel32.
