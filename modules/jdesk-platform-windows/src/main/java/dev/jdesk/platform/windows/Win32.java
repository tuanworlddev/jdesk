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
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

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
    private static final SymbolLookup SHELL32 =
            SymbolLookup.libraryLookup("shell32.dll", GLOBAL);
    private static final SymbolLookup COMDLG32 =
            SymbolLookup.libraryLookup("comdlg32.dll", GLOBAL);

    private Win32() {
    }

    // ---- constants ----
    static final int WM_DESTROY = 0x0002;
    static final int WM_SIZE = 0x0005;
    static final int WM_CLOSE = 0x0010;
    static final int WM_GETMINMAXINFO = 0x0024;
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
    private static final MethodHandle MESSAGE_BOX = down(USER32, "MessageBoxW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_CLIENT_RECT = down(USER32, "GetClientRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_WINDOW_RECT = down(USER32, "GetWindowRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle LOAD_CURSOR = down(USER32, "LoadCursorW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle OPEN_CLIPBOARD = down(USER32,"OpenClipboard",FunctionDescriptor.of(JAVA_INT,ADDRESS));
    private static final MethodHandle CLOSE_CLIPBOARD = down(USER32,"CloseClipboard",FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle EMPTY_CLIPBOARD = down(USER32,"EmptyClipboard",FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_CLIPBOARD_DATA = down(USER32,"GetClipboardData",FunctionDescriptor.of(ADDRESS,JAVA_INT));
    private static final MethodHandle SET_CLIPBOARD_DATA = down(USER32,"SetClipboardData",FunctionDescriptor.of(ADDRESS,JAVA_INT,ADDRESS));
    private static final MethodHandle SHELL_EXECUTE = down(SHELL32, "ShellExecuteW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SET_FOREGROUND_WINDOW = down(USER32, "SetForegroundWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle IS_ICONIC = down(USER32, "IsIconic",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle IS_ZOOMED = down(USER32, "IsZoomed",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

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
    private static final MethodHandle GLOBAL_ALLOC = down(KERNEL32,"GlobalAlloc",FunctionDescriptor.of(ADDRESS,JAVA_INT,JAVA_LONG));
    private static final MethodHandle GLOBAL_FREE = down(KERNEL32,"GlobalFree",FunctionDescriptor.of(ADDRESS,ADDRESS));

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

    static void setWindowPosAfter(MemorySegment hwnd, long insertAfter, int flags) {
        try {
            int ignored = (int) SET_WINDOW_POS.invokeExact(hwnd,
                    MemorySegment.ofAddress(insertAfter), 0, 0, 0, 0, flags);
        } catch (Throwable t) { throw wrap("SetWindowPos", t); }
    }

    static void focusWindow(MemorySegment hwnd) {
        try { int ignored = (int) SET_FOREGROUND_WINDOW.invokeExact(hwnd); }
        catch (Throwable t) { throw wrap("SetForegroundWindow", t); }
    }

    static boolean isIconic(MemorySegment hwnd) {
        try { return (int) IS_ICONIC.invokeExact(hwnd) != 0; }
        catch (Throwable t) { throw wrap("IsIconic", t); }
    }

    static boolean isZoomed(MemorySegment hwnd) {
        try { return (int) IS_ZOOMED.invokeExact(hwnd) != 0; }
        catch (Throwable t) { throw wrap("IsZoomed", t); }
    }

    static void getClientRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ignored = (int) GET_CLIENT_RECT.invokeExact(hwnd, rect);
        } catch (Throwable t) {
            throw wrap("GetClientRect", t);
        }
    }

    static void getWindowRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ignored = (int) GET_WINDOW_RECT.invokeExact(hwnd, rect);
        } catch (Throwable t) {
            throw wrap("GetWindowRect", t);
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

    static void openExternal(String uri) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) SHELL_EXECUTE.invokeExact(MemorySegment.NULL,
                    WideStrings.alloc(arena, "open"), WideStrings.alloc(arena, uri),
                    MemorySegment.NULL, MemorySegment.NULL, 1);
            if (result.address() <= 32) throw new IllegalStateException("ShellExecuteW failed");
        } catch (Throwable t) { throw wrap("ShellExecuteW", t); }
    }

    // ---- comdlg32 file dialogs + ShellExecute print ----
    private static final MethodHandle GET_OPEN_FILE_NAME = down(COMDLG32, "GetOpenFileNameW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_SAVE_FILE_NAME = down(COMDLG32, "GetSaveFileNameW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static MethodHandle getOpenFileNameHandle() {
        return GET_OPEN_FILE_NAME;
    }

    static MethodHandle getSaveFileNameHandle() {
        return GET_SAVE_FILE_NAME;
    }

    /**
     * ShellExecuteW with the "print"/"printto" verb (default vs. named printer). The
     * print verb can delegate to COM-based shell/print handlers, so this initializes an
     * STA apartment on the calling (print) thread — printFile runs on a fresh virtual
     * thread that has no apartment otherwise.
     */
    static void shellPrint(String filePath, String printerName) {
        int hr = coInitializeEx(COINIT_APARTMENTTHREADED);
        // hr S_OK (0) / S_FALSE (1) both mean initialized; only balance CoUninitialize
        // when we actually initialized it (hr >= 0).
        boolean initialized = hr >= 0;
        try (Arena arena = Arena.ofConfined()) {
            String verb = printerName == null ? "print" : "printto";
            MemorySegment params = printerName == null
                    ? MemorySegment.NULL : WideStrings.alloc(arena, "\"" + printerName + "\"");
            MemorySegment result = (MemorySegment) SHELL_EXECUTE.invokeExact(MemorySegment.NULL,
                    WideStrings.alloc(arena, verb), WideStrings.alloc(arena, filePath),
                    params, MemorySegment.NULL, 0);
            if (result.address() <= 32) {
                throw new IllegalStateException("ShellExecuteW print failed (" + result.address() + ")");
            }
        } catch (Throwable t) {
            throw wrap("ShellExecuteW print", t);
        } finally {
            if (initialized) {
                coUninitialize();
            }
        }
    }

    static String readClipboardText() {
        try {
            if((int)OPEN_CLIPBOARD.invokeExact(MemorySegment.NULL)==0)throw new IllegalStateException("OpenClipboard failed");
            try { MemorySegment h=(MemorySegment)GET_CLIPBOARD_DATA.invokeExact(13); if(h.equals(MemorySegment.NULL))return "";
                MemorySegment p=globalLock(h); try{return WideStrings.read(p);}finally{globalUnlock(h);} }
            finally{int ignored=(int)CLOSE_CLIPBOARD.invokeExact();}
        }catch(Throwable t){throw wrap("clipboard read",t);}
    }
    static void writeClipboardText(String text) {
        MemorySegment h=MemorySegment.NULL;
        try { byte[] bytes=(text+'\0').getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            h=(MemorySegment)GLOBAL_ALLOC.invokeExact(2, (long)bytes.length);if(h.equals(MemorySegment.NULL))throw new IllegalStateException("GlobalAlloc failed");
            MemorySegment p=globalLock(h);try{MemorySegment.copy(bytes,0,p.reinterpret(bytes.length),JAVA_BYTE,0,bytes.length);}finally{globalUnlock(h);}
            if((int)OPEN_CLIPBOARD.invokeExact(MemorySegment.NULL)==0)throw new IllegalStateException("OpenClipboard failed");
            try{if((int)EMPTY_CLIPBOARD.invokeExact()==0)throw new IllegalStateException("EmptyClipboard failed");
                MemorySegment accepted=(MemorySegment)SET_CLIPBOARD_DATA.invokeExact(13,h);if(accepted.equals(MemorySegment.NULL))throw new IllegalStateException("SetClipboardData failed");h=MemorySegment.NULL;}
            finally{int ignored=(int)CLOSE_CLIPBOARD.invokeExact();}
        }catch(Throwable t){throw wrap("clipboard write",t);}finally{if(!h.equals(MemorySegment.NULL))try{MemorySegment ignored=(MemorySegment)GLOBAL_FREE.invokeExact(h);}catch(Throwable ignored){}}
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

    static dev.jdesk.api.MessageDialogResult showMessageDialog(dev.jdesk.api.MessageDialog dialog) {
        int buttonFlags;
        java.util.List<String> labels = dialog.buttons();
        if (labels.equals(java.util.List.of("OK"))) buttonFlags = 0x00000000;
        else if (labels.equals(java.util.List.of("OK", "Cancel"))) buttonFlags = 0x00000001;
        else if (labels.equals(java.util.List.of("Yes", "No"))) buttonFlags = 0x00000004;
        else if (labels.equals(java.util.List.of("Yes", "No", "Cancel"))) buttonFlags = 0x00000003;
        else throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.INVALID_REQUEST,
                "Windows supports dialog buttons [OK], [OK, Cancel], [Yes, No], or [Yes, No, Cancel]");
        int icon = switch (dialog.kind()) { case INFO -> 0x40; case WARNING -> 0x30; case ERROR -> 0x10; };
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment title = wide(arena, dialog.title()), message = wide(arena, dialog.message());
            int result = (int) MESSAGE_BOX.invokeExact(MemorySegment.NULL, message, title,
                    buttonFlags | icon | 0x00002000);
            int index = switch (result) { case 1, 6 -> 0; case 2, 7 -> 1; default -> labels.size() - 1; };
            return new dev.jdesk.api.MessageDialogResult(index, labels.get(index));
        } catch (Throwable t) { throw wrap("MessageBoxW", t); }
    }

    private static MemorySegment wide(Arena arena, String value) {
        byte[] bytes = (value + '\0').getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        MemorySegment result = arena.allocate(bytes.length, 2);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, result, 0, bytes.length);
        return result;
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
