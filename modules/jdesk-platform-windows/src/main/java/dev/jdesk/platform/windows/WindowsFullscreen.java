package dev.jdesk.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

/** Public Win32 borderless fullscreen algorithm with per-window style/bounds restore. */
final class WindowsFullscreen {
    private static final Arena ARENA = Arena.global();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", ARENA);
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle GET_LONG = down("GetWindowLongW", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle SET_LONG = down("SetWindowLongW", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_RECT = down("GetWindowRect", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_METRIC = down("GetSystemMetrics", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final ConcurrentHashMap<Long, State> STATES = new ConcurrentHashMap<>();
    private static final int GWL_STYLE=-16, WS_OVERLAPPEDWINDOW=0x00CF0000, FRAME_CHANGED=0x20;
    private WindowsFullscreen() { }
    static void set(MemorySegment hwnd, boolean fullscreen) {
        try {
            if (fullscreen) {
                if (STATES.containsKey(hwnd.address())) return;
                try (Arena a=Arena.ofConfined()) {
                    MemorySegment r=a.allocate(Win32.RECT); int ok=(int)GET_RECT.invokeExact(hwnd,r);
                    if(ok==0) throw new IllegalStateException("GetWindowRect failed");
                    int style=(int)GET_LONG.invokeExact(hwnd,GWL_STYLE);
                    STATES.put(hwnd.address(),new State(style,r.get(ValueLayout.JAVA_INT,0),r.get(ValueLayout.JAVA_INT,4),r.get(ValueLayout.JAVA_INT,8),r.get(ValueLayout.JAVA_INT,12)));
                    int ignored=(int)SET_LONG.invokeExact(hwnd,GWL_STYLE,style & ~WS_OVERLAPPEDWINDOW);
                    int width=(int)GET_METRIC.invokeExact(0),height=(int)GET_METRIC.invokeExact(1);
                    Win32.setWindowPos(hwnd,0,0,width,height,FRAME_CHANGED);
                }
            } else {
                State s=STATES.remove(hwnd.address()); if(s==null)return;
                int ignored=(int)SET_LONG.invokeExact(hwnd,GWL_STYLE,s.style);
                Win32.setWindowPos(hwnd,s.left,s.top,s.right-s.left,s.bottom-s.top,FRAME_CHANGED);
            }
        } catch(Throwable t){ throw new IllegalStateException("Fullscreen transition failed",t); }
    }
    private static MethodHandle down(String name,FunctionDescriptor d){return LINKER.downcallHandle(USER32.findOrThrow(name),d);}
    private record State(int style,int left,int top,int right,int bottom){}
}
