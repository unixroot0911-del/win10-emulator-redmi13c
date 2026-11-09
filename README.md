# Win10 Emulator (Android)

Original Android app: Windows 10 style desktop (taskbar, start menu, windows) with Terminal, Browser (proxy support), Files, Notepad, Calculator and Settings,
plus a from-scratch 32-bit x86 emulator (`core/`): CPU interpreter, PE loader, Win32/CRT API layer. No Wine, Box64, Winlator or other emulator code.

Build: GitHub Actions (`.github/workflows/build-apk.yml`) publishes the APK as a Release.
Test programs: `python3 tools/make_pe.py app/src/main/assets`.
