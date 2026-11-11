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
        // kernel32 (stdcall)
        add("ExitProcess:1s,TerminateProcess:2s,GetStdHandle:1s,SetStdHandle:2s,WriteFile:5s,WriteConsoleA:5s,WriteConsoleW:5s,ReadFile:5s,ReadConsoleA:5s,"
          + "GetCommandLineA:0s,GetCommandLineW:0s,GetModuleHandleA:1s,GetModuleHandleW:1s,GetModuleHandleExA:3s,GetModuleFileNameA:3s,"
          + "LoadLibraryA:1s,LoadLibraryW:1s,LoadLibraryExA:3s,FreeLibrary:1s,GetProcAddress:2s,GetProcessHeap:0s,HeapCreate:3s,HeapDestroy:1s,"
          + "HeapAlloc:3s,HeapFree:3s,HeapReAlloc:4s,HeapSize:3s,HeapValidate:3s,VirtualAlloc:4s,VirtualFree:3s,VirtualProtect:4s,VirtualQuery:3s,"
          + "GetLastError:0s,SetLastError:1s,Sleep:1s,GetTickCount:0s,GetTickCount64:0s,GetCurrentProcessId:0s,GetCurrentThreadId:0s,"
          + "GetCurrentProcess:0s,GetCurrentThread:0s,GetVersion:0s,GetVersionExA:1s,IsDebuggerPresent:0s,GetFileType:1s,SetConsoleTitleA:1s,"
          + "SetConsoleTextAttribute:2s,SetConsoleCtrlHandler:2s,lstrlenA:1s,lstrlenW:1s,lstrcpyA:2s,lstrcatA:2s,lstrcmpA:2s,lstrcmpiA:2s,"
          + "GetSystemTimeAsFileTime:1s,QueryPerformanceCounter:1s,QueryPerformanceFrequency:1s,CreateFileA:7s,CreateFileW:7s,CloseHandle:1s,"
          + "GetFileSize:2s,GetFileSizeEx:2s,SetFilePointer:4s,SetFilePointerEx:5s,SetEndOfFile:1s,FlushFileBuffers:1s,DeleteFileA:1s,"
          + "CreateDirectoryA:2s,RemoveDirectoryA:1s,GetFileAttributesA:1s,FindFirstFileA:2s,FindNextFileA:2s,FindClose:1s,"
          + "GetCurrentDirectoryA:2s,SetCurrentDirectoryA:1s,GetTempPathA:2s,GetEnvironmentVariableA:3s,SetEnvironmentVariableA:2s,"
          + "GetEnvironmentStringsA:0s,GetEnvironmentStringsW:0s,FreeEnvironmentStringsA:1s,FreeEnvironmentStringsW:1s,GetACP:0s,GetOEMCP:0s,"
          + "GetCPInfo:2s,IsValidCodePage:1s,GetConsoleMode:2s,SetConsoleMode:2s,GetConsoleOutputCP:0s,GetConsoleCP:0s,SetConsoleOutputCP:1s,"
          + "GetConsoleScreenBufferInfo:2s,GetStartupInfoA:1s,GetStartupInfoW:1s,GetSystemInfo:1s,GlobalMemoryStatusEx:1s,"
          + "InitializeCriticalSection:1s,InitializeCriticalSectionAndSpinCount:2s,InitializeCriticalSectionEx:3s,EnterCriticalSection:1s,"
          + "LeaveCriticalSection:1s,DeleteCriticalSection:1s,TryEnterCriticalSection:1s,TlsAlloc:0s,TlsFree:1s,TlsGetValue:1s,TlsSetValue:2s,"
          + "FlsAlloc:1s,FlsFree:1s,FlsGetValue:1s,FlsSetValue:2s,InterlockedIncrement:1s,InterlockedDecrement:1s,InterlockedExchange:2s,"
          + "InterlockedCompareExchange:3s,InterlockedExchangeAdd:2s,SetUnhandledExceptionFilter:1s,UnhandledExceptionFilter:1s,RaiseException:4s,"
          + "OutputDebugStringA:1s,SetErrorMode:1s,EncodePointer:1s,DecodePointer:1s,IsProcessorFeaturePresent:1s,DisableThreadLibraryCalls:1s,"
          + "WaitForSingleObject:2s,CreateEventA:4s,SetEvent:1s,ResetEvent:1s,CreateMutexA:3s,ReleaseMutex:1s,MultiByteToWideChar:6s,"
          + "WideCharToMultiByte:8s,GetUserDefaultLCID:0s,GlobalAlloc:2s,GlobalFree:1s,LocalAlloc:2s,LocalFree:1s,GlobalLock:1s,GlobalUnlock:1s,"
          + "GetComputerNameA:2s,ExitThread:1s,CreateThread:6s,GetLocalTime:1s,GetSystemTime:1s,GetStringTypeW:5s,GetCurrentThreadId:0s,"
          + "SwitchToThread:0s,GetSystemDirectoryA:2s,GetWindowsDirectoryA:2s,SetThreadPriority:2s,GetProcessAffinityMask:3s,"
          + "InitializeSListHead:1s,RtlUnwind:4s,GetStdHandle:1s,GetLogicalDrives:0s,GetDriveTypeA:1s,GetUserNameA:2s,"
          // user32 / gdi32 (stdcall) are declared in Gui.java
          + "MessageBoxA:4s,MessageBoxW:4s,"
          // C runtime (cdecl)
          + "puts:1c,putchar:1c,getchar:0c,_getch:0c,_getche:0c,_kbhit:0c,printf:0c,sprintf:0c,snprintf:0c,_snprintf:0c,fprintf:0c,"
          + "vprintf:2c,vfprintf:3c,vsprintf:3c,vsnprintf:4c,_vsnprintf:4c,"
          + "__stdio_common_vfprintf:6c,__stdio_common_vsprintf:7c,__stdio_common_vsnprintf_s:8c,__acrt_iob_func:1c,__iob_func:0c,"
          + "fwrite:4c,fread:4c,fputs:2c,fputc:2c,putc:2c,fgets:3c,fgetc:1c,getc:1c,ungetc:2c,fopen:2c,fclose:1c,fflush:1c,feof:1c,ferror:1c,"
          + "fseek:3c,ftell:1c,rewind:1c,setvbuf:4c,remove:1c,rename:2c,fileno:1c,"
          + "malloc:1c,calloc:2c,realloc:2c,free:1c,_aligned_malloc:2c,_aligned_free:1c,"
          + "memcpy:3c,memmove:3c,memset:3c,memcmp:3c,memchr:3c,strlen:1c,strcpy:2c,strncpy:3c,strcat:2c,strncat:3c,strcmp:2c,strncmp:3c,"
          + "_stricmp:2c,_strnicmp:3c,stricmp:2c,strchr:2c,strrchr:2c,strstr:2c,strdup:1c,_strdup:1c,strtol:3c,strtoul:3c,strtod:2c,atoi:1c,atol:1c,atof:1c,"
          + "abs:1c,labs:1c,rand:0c,srand:1c,time:1c,clock:0c,toupper:1c,tolower:1c,isalpha:1c,isdigit:1c,isspace:1c,isalnum:1c,isupper:1c,"
          + "islower:1c,isxdigit:1c,ispunct:1c,isprint:1c,iscntrl:1c,exit:1c,_exit:1c,abort:0c,atexit:1c,_crt_atexit:1c,_onexit:1c,_cexit:0c,"
          + "qsort:4c,bsearch:5c,getenv:1c,system:1c,"
          + "__p___argc:0c,__p___argv:0c,__p__environ:0c,__p__fmode:0c,__p__commode:0c,_configure_narrow_argv:1c,_initialize_narrow_environment:0c,"
          + "_initterm:2c,_initterm_e:2c,_set_app_type:1c,_set_new_mode:1c,_configthreadlocale:1c,_set_invalid_parameter_handler:1c,"
          + "_seh_filter_exe:2c,__setusermatherr:1c,_controlfp:2c,_controlfp_s:3c,__getmainargs:5c,__set_app_type:1c,_errno:0c,_set_fmode:1c,"
          + "_lock:1c,_unlock:1c,__lock:1c,__unlock:1c,_amsg_exit:1c,_XcptFilter:2c,__C_specific_handler:4c,_setmode:2c,_fileno:1c,"
          + "sqrt:2c,sin:2c,cos:2c,tan:2c,asin:2c,acos:2c,atan:2c,atan2:4c,sinh:2c,cosh:2c,tanh:2c,exp:2c,log:2c,log10:2c,pow:4c,"
          + "floor:2c,ceil:2c,fabs:2c,fmod:4c,ldexp:3c,sqrtf:1c,floorf:1c,ceilf:1c,fabsf:1c,round:2c,trunc:2c,"
          + "lround:2c,llround:2c,lrint:2c,rint:2c,nearbyint:2c,fmax:4c,fmin:4c,copysign:4c,hypot:4c,cbrt:2c,log2:2c,exp2:2c,expm1:2c,log1p:2c,"
          + "sinf:1c,cosf:1c,tanf:1c,powf:2c,atan2f:2c,fmodf:2c,expf:1c,logf:1c,log10f:1c,atanf:1c,asinf:1c,acosf:1c,roundf:1c,truncf:1c,"
          + "_initialize_onexit_table:1c,_register_onexit_function:2c,_execute_onexit_table:1c,_get_initial_narrow_environment:0c,"
          + "_set_abort_behavior:2c,_invalid_parameter_noinfo_noreturn:0c,_register_thread_local_exe_atexit_callback:1c,"
          + "_chkstk:0c,__chkstk:0c,___chkstk_ms:0c,_alloca:0c,__alloca:0c,_ftol2_sse:0c,_ftol2:0c,_ftol:0c,"
          + "__divdi3:4c,__moddi3:4c,__udivdi3:4c,__umoddi3:4c,__allmul:4s,__alldiv:4s,__aullrem:4s,__aulldiv:4s,__allrem:4s,__allshl:0s,__aullshr:0s,__allshr:0s");
    }

    static { add(WinSock.SPEC); }

    static void addExtra(String spec) { add(spec); }

    static Integer nargs(String name) { int[] v = T.get(name); return v == null ? null : v[0]; }
    static boolean isStdcall(String name) { int[] v = T.get(name); return v != null && v[1] == 1; }
}
