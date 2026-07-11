package dev.jdesk.webview.spi;

import dev.jdesk.api.UiDispatcher;

/**
 * One running native application: owns the UI thread/event loop and creates windows.
 * All methods except {@link #runEventLoop()} and {@link #requestStop()} must be called
 * on the UI thread.
 */
public interface PlatformApplication extends AutoCloseable {
    UiDispatcher ui();

    PlatformWindow createWindow(NativeWindowConfig config);

    /** Blocks running the native event loop until {@link #requestStop()}. */
    void runEventLoop();

    /** Thread-safe request to end the event loop. */
    void requestStop();

    @Override
    void close();
}
