package dev.jdesk.webview.spi;

import dev.jdesk.api.UiDispatcher;
import java.net.URI;
import dev.jdesk.api.MessageDialog;
import dev.jdesk.api.MessageDialogResult;

/**
 * One running native application: owns the UI thread/event loop and creates windows.
 * All methods except {@link #runEventLoop()} and {@link #requestStop()} must be called
 * on the UI thread.
 */
public interface PlatformApplication extends AutoCloseable {
    UiDispatcher ui();

    PlatformWindow createWindow(NativeWindowConfig config);

    /** Opens an already policy-validated HTTP(S) URI in the OS default browser. */
    void openExternal(URI uri);

    /**
     * OS-backed secret storage scoped to {@code applicationId}. Unlike the other
     * methods, implementations must be callable from any thread (they may block on the
     * OS credential service). Adapters without a backend keep this default and fail
     * loudly rather than fall back to plaintext.
     */
    default dev.jdesk.api.SecretStore secrets(String applicationId) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Secret storage is not supported by this platform adapter");
    }

    String readClipboardText();
    void writeClipboardText(String text);
    MessageDialogResult showMessageDialog(MessageDialog dialog);

    /** Shows a native app-modal open dialog (UI thread). Default: unsupported. */
    default dev.jdesk.api.FileDialogResult showOpenDialog(dev.jdesk.api.FileDialog.OpenDialog dialog) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Open dialog is not supported by this platform adapter");
    }

    /** Shows a native app-modal save dialog (UI thread). Default: unsupported. */
    default dev.jdesk.api.FileDialogResult showSaveDialog(dev.jdesk.api.FileDialog.SaveDialog dialog) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Save dialog is not supported by this platform adapter");
    }

    /** Sends a document file to a printer. May run off the UI thread. Default: unsupported. */
    default void printFile(dev.jdesk.api.PrintJob job) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "File printing is not supported by this platform adapter");
    }

    /** Blocks running the native event loop until {@link #requestStop()}. */
    void runEventLoop();

    /** Thread-safe request to end the event loop. */
    void requestStop();

    @Override
    void close();
}
