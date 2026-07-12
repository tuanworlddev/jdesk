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

    /**
     * Low-latency native file-watching backend, when the platform has one (macOS
     * FSEvents). Empty (the default) makes the runtime fall back to a portable
     * {@code WatchService} backend. May be called from any thread.
     */
    default java.util.Optional<FileWatchBackend> fileWatchBackend() {
        return java.util.Optional.empty();
    }

    /**
     * Native pseudo-terminal backend, when the platform has one (macOS). Empty (the default)
     * makes {@link dev.jdesk.api.ApplicationHandle#openPty} report {@code ILLEGAL_STATE}. May
     * be called from any thread.
     */
    default java.util.Optional<PtyBackend> ptyBackend() {
        return java.util.Optional.empty();
    }

    /** OS light/dark appearance (UI thread). Default: unsupported. */
    default dev.jdesk.api.SystemTheme systemTheme() {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "System theme is not supported by this platform adapter");
    }

    /** Reads binary clipboard data of {@code type}; null when absent (UI thread). Default: unsupported. */
    default byte[] readClipboard(String type) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Binary clipboard is not supported by this platform adapter");
    }

    /** Writes binary clipboard data under {@code type} (UI thread). Default: unsupported. */
    default void writeClipboard(String type, byte[] data) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Binary clipboard is not supported by this platform adapter");
    }

    /** Sets/clears the Dock (or taskbar) badge label (UI thread). Default: unsupported. */
    default void setDockBadge(String label) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Dock badge is not supported by this platform adapter");
    }

    /**
     * Installs the application menu bar (UI thread). Default: no-op — platforms without a
     * global menu bar simply ignore it.
     */
    default void setApplicationMenu(dev.jdesk.api.MenuSpec menu,
            java.util.function.Consumer<String> onAction) {
    }

    /** Sets the application icon from PNG bytes (UI thread). Default: unsupported. */
    default void setApplicationIcon(byte[] pngData) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Application icon is not supported by this platform adapter");
    }

    /** Creates a tray / status-bar item (UI thread). Default: unsupported. */
    default TrayControl createTrayItem(dev.jdesk.api.TraySpec spec,
            java.util.function.Consumer<String> onAction) {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "System tray is not supported by this platform adapter");
    }

    /** Blocks running the native event loop until {@link #requestStop()}. */
    void runEventLoop();

    /** Thread-safe request to end the event loop. */
    void requestStop();

    @Override
    void close();
}
