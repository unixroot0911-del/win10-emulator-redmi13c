package com.unixroot.win10.core;

import java.util.HashMap;
import java.util.Map;

/** Argument counts and calling convention for the APIs implemented in {@link Emulator}. s = stdcall, c = cdecl. */
final class Win32Table {
    private static final Map<String, int[]> T = new HashMap<>();

    private static void add(String spec) {
        for (String e : spec.split(",")) {
            e = e.trim();
            if (e.isEmpty()) continue;
            String[] p = e.split(":");
            int n = Integer.parseInt(p[1].substring(0, p[1].length() - 1));
            T.put(p[0], new int[]{n, p[1].endsWith("s") ? 1 : 0});
        }
    }

    static {
        add("ExitProcess:1s,TerminateProcess:2s,GetStdHandle:1s,WriteFile:5s,WriteConsoleA:5s,ReadFile:5s,ReadConsoleA:5s,"
          + "GetCommandLineA:0s,GetModuleHandleA:1s,GetModuleHandleW:1s,LoadLibraryA:1s,GetProcAddress:2s,GetProcessHeap:0s,"
          + "HeapAlloc:3s,HeapFree:3s,HeapReAlloc:4s,HeapSize:3s,VirtualAlloc:4s,VirtualFree:3s,GetLastError:0s,SetLastError:1s,"
          + "Sleep:1s,GetTickCount:0s,GetCurrentProcessId:0s,GetCurrentThreadId:0s,GetCurrentProcess:0s,GetVersion:0s,"
          + "IsDebuggerPresent:0s,GetFileType:1s,SetConsoleTitleA:1s,SetConsoleTextAttribute:2s,SetConsoleCtrlHandler:2s,"
          + "lstrlenA:1s,lstrcpyA:2s,lstrcmpA:2s,GetSystemTimeAsFileTime:1s,QueryPerformanceCounter:1s,QueryPerformanceFrequency:1s,"
          + "CreateFileA:7s,CloseHandle:1s,GetFileSize:2s,DeleteFileA:1s,GetEnvironmentVariableA:3s,GetACP:0s,GetLocalTime:1s,GetSystemTime:1s,"
          + "MessageBoxA:4s,"
          + "puts:1c,putchar:1c,getchar:0c,_getch:0c,printf:0c,sprintf:0c,malloc:1c,calloc:2c,realloc:2c,free:1c,"
          + "memcpy:3c,memmove:3c,memset:3c,memcmp:3c,strlen:1c,strcpy:2c,strcmp:2c,atoi:1c,abs:1c,rand:0c,srand:1c,time:1c,"
          + "toupper:1c,tolower:1c,exit:1c,_exit:1c");
    }

    static Integer nargs(String name) { int[] v = T.get(name); return v == null ? null : v[0]; }
    static boolean isStdcall(String name) { int[] v = T.get(name); return v != null && v[1] == 1; }
}
