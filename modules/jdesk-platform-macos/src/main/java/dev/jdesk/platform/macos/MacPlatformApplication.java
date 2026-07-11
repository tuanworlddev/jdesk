package dev.jdesk.platform.macos;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.ffm.NativeHandle;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformWindow;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * One AppKit application. The process main thread is the UI thread (spec section 7:
 * NSApplication and AppKit event handling run on the first/main thread — launch with
 * {@code -XstartOnFirstThread}). Windows and WebViews are created on this thread, both
 * before {@code [NSApp run]} starts and later through the dispatcher while it runs.
 */
final class MacPlatformApplication extends NativeHandle implements PlatformApplication {
    private static final Logger LOG = System.getLogger(MacPlatformApplication.class.getName());

    private static final long NS_EVENT_TYPE_APPLICATION_DEFINED = 15; // NSEvent.h
    // + otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:
    private static final FunctionDescriptor OTHER_EVENT_DESC = FunctionDescriptor.of(ADDRESS,
            ADDRESS, ADDRESS, JAVA_LONG, ObjC.NSPOINT, JAVA_LONG, JAVA_DOUBLE, JAVA_LONG,
            ADDRESS, JAVA_SHORT, JAVA_LONG, JAVA_LONG);
    // - postEvent:atStart:
    private static final FunctionDescriptor POST_EVENT_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_BYTE);
    // - setActivationPolicy: (returns BOOL)
    private static final FunctionDescriptor SET_ACTIVATION_POLICY_DESC =
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG);

    private final PlatformApplicationConfig config;
    private final MacUiDispatcher dispatcher;
    /** Pins block literals/stubs for evaluate/snapshot completions; app-lifetime owner. */
    private final NativeCallbackRegistry blockRegistry;
    private final MemorySegment nsApp;
    private final MemorySegment constructionPool;
    private volatile boolean stopRequested;

    MacPlatformApplication(PlatformApplicationConfig config) {
        super("MacPlatformApplication");
        if (!MacUiDispatcher.onProcessMainThread()) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "AppKit requires the process main thread; launch the JVM with "
                            + "-XstartOnFirstThread");
        }
        this.config = config;
        this.constructionPool = ObjC.autoreleasePoolPush();
        this.nsApp = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication");
        try {
            // NSApplicationActivationPolicyRegular = 0
            byte unusedPolicyResult = (byte) ObjC.msgSend(SET_ACTIVATION_POLICY_DESC)
                    .invokeExact(nsApp, ObjC.sel("setActivationPolicy:"), 0L);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
        ObjC.sendVoidBool(nsApp, "activateIgnoringOtherApps:", true);
        this.dispatcher = new MacUiDispatcher(config.devMode());
        this.blockRegistry = new NativeCallbackRegistry("macos-app-blocks", Arena.ofShared());
        markOpen();
    }

    @Override
    public UiDispatcher ui() {
        return dispatcher;
    }

    MacUiDispatcher dispatcher() {
        return dispatcher;
    }

    PlatformApplicationConfig config() {
        return config;
    }

    NativeCallbackRegistry blockRegistry() {
        return blockRegistry;
    }

    @Override
    public PlatformWindow createWindow(NativeWindowConfig windowConfig) {
        requireOpen();
        dispatcher.assertUiThread();
        return new MacWindow(this, windowConfig);
    }

    @Override
    public void runEventLoop() {
        requireOpen();
        dispatcher.assertUiThread();
        if (stopRequested) {
            return;
        }
        ObjC.sendVoid(nsApp, "run");
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        dispatcher.execute(() -> {
            try {
                ObjC.sendVoid(nsApp, "stop:", MemorySegment.NULL);
                postWakeEvent();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "requestStop failed", e);
            }
        });
    }

    /**
     * {@code [NSApp stop:]} only takes effect after the current event completes; posting
     * an application-defined event wakes the loop (documented AppKit pattern).
     */
    private void postWakeEvent() {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment location = confined.allocate(ObjC.NSPOINT); // zero-initialized
            MemorySegment event = (MemorySegment) ObjC.msgSend(OTHER_EVENT_DESC).invokeExact(
                    ObjC.cls("NSEvent"),
                    ObjC.sel("otherEventWithType:location:modifierFlags:timestamp:"
                            + "windowNumber:context:subtype:data1:data2:"),
                    NS_EVENT_TYPE_APPLICATION_DEFINED, location, 0L, 0.0d, 0L,
                    MemorySegment.NULL, (short) 0, 0L, 0L);
            if (!event.equals(MemorySegment.NULL)) {
                ObjC.msgSend(POST_EVENT_DESC).invokeExact(
                        nsApp, ObjC.sel("postEvent:atStart:"), event, (byte) 1);
            }
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }

    @Override
    protected void releaseNative() {
        dispatcher.assertUiThread();
        blockRegistry.close();
        ObjC.autoreleasePoolPop(constructionPool);
        // NSApplication is a process singleton and is not released.
    }
}
