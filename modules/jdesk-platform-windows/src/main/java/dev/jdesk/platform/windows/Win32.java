package dev.jdesk.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * FFM bindings for the Win32 APIs used by the adapter (user32/kernel32/ole32/shlwapi).
 * Struct layouts are for x64 and carry the native declaration in a comment
 * (source: Windows SDK, verified against learn.microsoft.com docs; spec section 6.5).
 */
final class Win32 {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.ofAuto();

    private static final SymbolLookup USER32 =
            SymbolLookup.libraryLookup("user32.dll", GLOBAL);
    private static final SymbolLookup KERNEL32 =
            SymbolLookup.libraryLookup("kernel32.dll", GLOBAL);
    private static final SymbolLookup OLE32 =
            SymbolLookup.libraryLookup("ole32.dll", GLOBAL);
    private static final SymbolLookup SHLWAPI =
            SymbolLookup.libraryLookup("shlwapi.dll", GLOBAL);

    private Win32() {
    }

    // ---- constants ----
    static final int WM_DESTROY = 0x0002;
    static final int WM_SIZE = 0x0005;
    static final int WM_CLOSE = 0x0010;
    static final int WM_APP_DISPATCH = 0x8000; // WM_APP
    static final int SW_SHOW = 5;
    static final int SW_HIDE = 0;
    static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    static final int WS_OVERLAPPED_NO_RESIZE = 0x00CF0000 & ~0x00040000 & ~0x00010000;
    static final int CW_USEDEFAULT = 0x80000000;
    static final long HWND_MESSAGE = -3L;
    static final int COINIT_APARTMENTTHREADED = 0x2;
    static final int SWP_NOZORDER = 0x0004;
    static final int SWP_NOMOVE = 0x0002;
    static final int GWLP_USERDATA = -21;

    /*
     * typedef struct tagWNDCLASSEXW {  // WinUser.h, x64
     *   UINT cbSize; UINT style; WNDPROC lpfnWndProc; int cbClsExtra; int cbWndExtra;
     *   HINSTANCE hInstance; HICON hIcon; HCURSOR hCursor; HBRUSH hbrBackground;
     *   LPCWSTR lpszMenuName; LPCWSTR lpszClassName; HICON hIconSm;
     * } WNDCLASSEXW;  // 80 bytes on x64
     */
    static final MemoryLayout WNDCLASSEXW = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"),
            JAVA_INT.withName("cbClsExtra"),
            JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"),
            ADDRESS.withName("hIcon"),
            ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"),
            ADDRESS.withName("lpszMenuName"),
            ADDRESS.withName("lpszClassName"),
            ADDRESS.withName("hIconSm"));

    /*
     * typedef struct tagMSG {  // WinUser.h, x64
     *   HWND hwnd; UINT message; WPARAM wParam; LPARAM lParam; DWORD time; POINT pt;
     * } MSG;  // 48 bytes on x64 (4 bytes padding after message, 4 at the end)
     */
    static final MemoryLayout MSG = MemoryLayout.structLayout(
            ADDRESS.withName("hwnd"),
            JAVA_INT.withName("message"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("wParam"),
            JAVA_LONG.withName("lParam"),
            JAVA_INT.withName("time"),
            JAVA_INT.withName("pt_x"),
            JAVA_INT.withName("pt_y"),
            MemoryLayout.paddingLayout(4));

    /* typedef struct tagRECT { LONG left; LONG top; LONG right; LONG bottom; } RECT; */
    static final MemoryLayout RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"),
            JAVA_INT.withName("top"),
            JAVA_INT.withName("right"),
            JAVA_INT.withName("bottom"));

    /** LRESULT WndProc(HWND, UINT, WPARAM, LPARAM) */
    static final FunctionDescriptor WNDPROC_DESC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

    // ---- user32 ----
    private static final MethodHandle REGISTER_CLASS_EX = down(USER32, "RegisterClassExW",
            FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
    private static final MethodHandle CREATE_WINDOW_EX = down(USER32, "CreateWindowExW",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle DEF_WINDOW_PROC = down(USER32, "DefWindowProcW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle DESTROY_WINDOW = down(USER32, "DestroyWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SHOW_WINDOW = down(USER32, "ShowWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_MESSAGE = down(USER32, "GetMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRANSLATE_MESSAGE = down(USER32, "TranslateMessage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISPATCH_MESSAGE = down(USER32, "DispatchMessageW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle POST_QUIT_MESSAGE = down(USER32, "PostQuitMessage",
            FunctionDescriptor.ofVoid(JAVA_INT));
    private static final MethodHandle POST_MESSAGE = down(USER32, "PostMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle SET_WINDOW_TEXT = down(USER32, "SetWindowTextW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_WINDOW_POS = down(USER32, "SetWindowPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_CLIENT_RECT = down(USER32, "GetClientRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle LOAD_CURSOR = down(USER32, "LoadCursorW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    // ---- kernel32 ----
    private static final MethodHandle GET_MODULE_HANDLE = down(KERNEL32, "GetModuleHandleW",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURRENT_THREAD_ID = down(KERNEL32, "GetCurrentThreadId",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GLOBAL_LOCK = down(KERNEL32, "GlobalLock",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GLOBAL_UNLOCK = down(KERNEL32, "GlobalUnlock",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_SIZE = down(KERNEL32, "GlobalSize",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    // ---- ole32 ----
    private static final MethodHandle CO_INITIALIZE_EX = down(OLE32, "CoInitializeEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_UNINITIALIZE = down(OLE32, "CoUninitialize",
            FunctionDescriptor.ofVoid());
    private static final MethodHandle CO_TASK_MEM_FREE = down(OLE32, "CoTaskMemFree",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CO_TASK_MEM_ALLOC = down(OLE32, "CoTaskMemAlloc",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    private static final MethodHandle CREATE_STREAM_ON_HGLOBAL = down(OLE32,
            "CreateStreamOnHGlobal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GET_HGLOBAL_FROM_STREAM = down(OLE32,
            "GetHGlobalFromStream",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    // ---- shlwapi ----
    private static final MethodHandle SH_CREATE_MEM_STREAM = down(SHLWAPI, "SHCreateMemStream",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    private static MethodHandle down(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() ->
                        new IllegalStateException("Win32 symbol not found: " + name)),
                desc);
    }

    // ---- wrappers (unchecked exceptions carry the failed operation name) ----

    static short registerClassEx(MemorySegment wndClass) {
        try {
            return (short) REGISTER_CLASS_EX.invokeExact(wndClass);
        } catch (Throwable t) {
            throw wrap("RegisterClassExW", t);
        }
    }

    static MemorySegment createWindowEx(int exStyle, MemorySegment className,
            MemorySegment title, int style, int x, int y, int width, int height,
            MemorySegment parent, MemorySegment menu, MemorySegment instance,
            MemorySegment param) {
        try {
            return (MemorySegment) CREATE_WINDOW_EX.invokeExact(exStyle, className, title,
                    style, x, y, width, height, parent, menu, instance, param);
        } catch (Throwable t) {
            throw wrap("CreateWindowExW", t);
        }
    }

    static long defWindowProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) DEF_WINDOW_PROC.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw wrap("DefWindowProcW", t);
        }
    }

    static void destroyWindow(MemorySegment hwnd) {
        try {
            int ignored = (int) DESTROY_WINDOW.invokeExact(hwnd);
        } catch (Throwable t) {
            throw wrap("DestroyWindow", t);
        }
    }

    static void showWindow(MemorySegment hwnd, int cmd) {
        try {
            int ignored = (int) SHOW_WINDOW.invokeExact(hwnd, cmd);
        } catch (Throwable t) {
            throw wrap("ShowWindow", t);
        }
    }

    static int getMessage(MemorySegment msg, MemorySegment hwnd, int min, int max) {
        try {
            return (int) GET_MESSAGE.invokeExact(msg, hwnd, min, max);
        } catch (Throwable t) {
            throw wrap("GetMessageW", t);
        }
    }

    static void translateMessage(MemorySegment msg) {
        try {
            int ignored = (int) TRANSLATE_MESSAGE.invokeExact(msg);
        } catch (Throwable t) {
            throw wrap("TranslateMessage", t);
        }
    }

    static void dispatchMessage(MemorySegment msg) {
        try {
            long ignored = (long) DISPATCH_MESSAGE.invokeExact(msg);
        } catch (Throwable t) {
            throw wrap("DispatchMessageW", t);
        }
    }

    static void postQuitMessage(int exitCode) {
        try {
            POST_QUIT_MESSAGE.invokeExact(exitCode);
        } catch (Throwable t) {
            throw wrap("PostQuitMessage", t);
        }
    }

    static boolean postMessage(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (int) POST_MESSAGE.invokeExact(hwnd, msg, wParam, lParam) != 0;
        } catch (Throwable t) {
            throw wrap("PostMessageW", t);
        }
    }

    static void setWindowText(MemorySegment hwnd, MemorySegment text) {
        try {
            int ignored = (int) SET_WINDOW_TEXT.invokeExact(hwnd, text);
        } catch (Throwable t) {
            throw wrap("SetWindowTextW", t);
        }
    }

    static void setWindowPos(MemorySegment hwnd, int x, int y, int width, int height, int flags) {
        try {
            int ignored = (int) SET_WINDOW_POS.invokeExact(hwnd, MemorySegment.NULL,
                    x, y, width, height, flags);
        } catch (Throwable t) {
            throw wrap("SetWindowPos", t);
        }
    }

    static void getClientRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ignored = (int) GET_CLIENT_RECT.invokeExact(hwnd, rect);
        } catch (Throwable t) {
            throw wrap("GetClientRect", t);
        }
    }

    static MemorySegment loadArrowCursor() {
        try {
            // IDC_ARROW = MAKEINTRESOURCEW(32512)
            return (MemorySegment) LOAD_CURSOR.invokeExact(
                    MemorySegment.NULL, MemorySegment.ofAddress(32512));
        } catch (Throwable t) {
            throw wrap("LoadCursorW", t);
        }
    }

    static MemorySegment getModuleHandle() {
        try {
            return (MemorySegment) GET_MODULE_HANDLE.invokeExact(MemorySegment.NULL);
        } catch (Throwable t) {
            throw wrap("GetModuleHandleW", t);
        }
    }

    static int getCurrentThreadId() {
        try {
            return (int) GET_CURRENT_THREAD_ID.invokeExact();
        } catch (Throwable t) {
            throw wrap("GetCurrentThreadId", t);
        }
    }

    static MemorySegment globalLock(MemorySegment hGlobal) {
        try {
            return (MemorySegment) GLOBAL_LOCK.invokeExact(hGlobal);
        } catch (Throwable t) {
            throw wrap("GlobalLock", t);
        }
    }

    static void globalUnlock(MemorySegment hGlobal) {
        try {
            int ignored = (int) GLOBAL_UNLOCK.invokeExact(hGlobal);
        } catch (Throwable t) {
            throw wrap("GlobalUnlock", t);
        }
    }

    static long globalSize(MemorySegment hGlobal) {
        try {
            return (long) GLOBAL_SIZE.invokeExact(hGlobal);
        } catch (Throwable t) {
            throw wrap("GlobalSize", t);
        }
    }

    static int coInitializeEx(int model) {
        try {
            return (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, model);
        } catch (Throwable t) {
            throw wrap("CoInitializeEx", t);
        }
    }

    static void coUninitialize() {
        try {
            CO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw wrap("CoUninitialize", t);
        }
    }

    /** Allocates zeroed CoTaskMem bytes; ownership follows COM out-parameter rules. */
    static MemorySegment coTaskAllocBytes(long size) {
        try {
            MemorySegment mem = (MemorySegment) CO_TASK_MEM_ALLOC.invokeExact(size);
            if (mem.equals(MemorySegment.NULL)) {
                throw new OutOfMemoryError("CoTaskMemAlloc failed");
            }
            mem.reinterpret(size).fill((byte) 0);
            return mem;
        } catch (Throwable t) {
            throw wrap("CoTaskMemAlloc", t);
        }
    }

    /** Allocates a CoTaskMem UTF-16LE copy of {@code value}; caller/callee frees per COM rules. */
    static MemorySegment coTaskAllocWide(String value) {
        byte[] utf16 = value.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        try {
            MemorySegment mem = (MemorySegment) CO_TASK_MEM_ALLOC.invokeExact((long) utf16.length + 2);
            if (mem.equals(MemorySegment.NULL)) {
                throw new OutOfMemoryError("CoTaskMemAlloc failed");
            }
            MemorySegment target = mem.reinterpret(utf16.length + 2L);
            MemorySegment.copy(MemorySegment.ofArray(utf16), 0, target, 0, utf16.length);
            target.set(java.lang.foreign.ValueLayout.JAVA_SHORT, utf16.length, (short) 0);
            return mem;
        } catch (Throwable t) {
            throw wrap("CoTaskMemAlloc", t);
        }
    }

    static void coTaskMemFree(MemorySegment segment) {
        try {
            CO_TASK_MEM_FREE.invokeExact(segment);
        } catch (Throwable t) {
            throw wrap("CoTaskMemFree", t);
        }
    }

    static int createStreamOnHGlobal(MemorySegment hGlobal, boolean deleteOnRelease,
            MemorySegment streamOut) {
        try {
            return (int) CREATE_STREAM_ON_HGLOBAL.invokeExact(hGlobal,
                    deleteOnRelease ? 1 : 0, streamOut);
        } catch (Throwable t) {
            throw wrap("CreateStreamOnHGlobal", t);
        }
    }

    static int getHGlobalFromStream(MemorySegment stream, MemorySegment hGlobalOut) {
        try {
            return (int) GET_HGLOBAL_FROM_STREAM.invokeExact(stream, hGlobalOut);
        } catch (Throwable t) {
            throw wrap("GetHGlobalFromStream", t);
        }
    }

    static MemorySegment shCreateMemStream(MemorySegment bytes, int length) {
        try {
            return (MemorySegment) SH_CREATE_MEM_STREAM.invokeExact(bytes, length);
        } catch (Throwable t) {
            throw wrap("SHCreateMemStream", t);
        }
    }

    private static RuntimeException wrap(String operation, Throwable t) {
        return new IllegalStateException("Win32 call failed: " + operation, t);
    }
}
