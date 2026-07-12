package dev.jdesk.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.net.URI;
import java.nio.file.Path;

/** Thread-safe control plane for a running JDesk application. */
public interface ApplicationHandle {
    /** Opens a native window, reserving its id before UI-thread creation starts. */
    CompletionStage<WindowHandle> openWindow(WindowConfig config);

    /** Looks up a currently open window. */
    Optional<WindowHandle> window(WindowId windowId);

    PlatformInfo platform();

    UiDispatcher ui();

    /** OS-backed secret storage scoped to this application id. */
    SecretStore secrets();

    /**
     * Platform-standard persistent data directory for this application, created on first
     * access. Windows {@code %APPDATA%\<id>}, macOS {@code ~/Library/Application Support/<id>},
     * Linux {@code $XDG_DATA_HOME/<id>} (default {@code ~/.local/share/<id>}). Scoped to the
     * application id; overridable in bulk with {@code -Djdesk.paths.dir=<base>}.
     */
    Path dataDir();

    /** Platform-standard configuration directory for this application, created on first access. */
    Path configDir();

    /** Platform-standard cache directory for this application, created on first access. */
    Path cacheDir();

    /** Opens an HTTP(S) URI without exposing a general shell execution primitive. */
    CompletionStage<Void> openExternal(URI uri);
    CompletionStage<String> readClipboardText();
    CompletionStage<Void> writeClipboardText(String text);

    /** The OS light/dark appearance (macOS effective appearance). */
    CompletionStage<SystemTheme> systemTheme();

    /**
     * Reads binary clipboard data for a platform type/UTI (e.g. {@code "public.png"},
     * {@code "public.tiff"}); empty when the clipboard has nothing of that type. The
     * non-text side of {@link #readClipboardText}.
     */
    CompletionStage<Optional<byte[]>> readClipboard(String type);

    /** Writes binary clipboard data under a type/UTI, replacing the clipboard contents. */
    CompletionStage<Void> writeClipboard(String type, byte[] data);

    /** Sets the Dock badge label, or clears it when {@code label} is null/blank. */
    CompletionStage<Void> setDockBadge(String label);

    /**
     * Installs the application menu bar. When a menu {@link MenuItem.Action} is chosen, its
     * {@code id} is delivered to {@code onAction} on the UI thread. Replacing an existing menu
     * is allowed. On platforms without a global menu bar this is a no-op.
     */
    CompletionStage<Void> setApplicationMenu(MenuSpec menu, Consumer<String> onAction);

    /** Sets the application (Dock/taskbar) icon from PNG bytes. */
    CompletionStage<Void> setApplicationIcon(byte[] pngData);

    /**
     * Adds a system tray / status-bar item with a click menu. A chosen menu
     * {@link MenuItem.Action} reports its id to {@code onAction} on the UI thread. Close the
     * returned {@link TrayHandle} to remove it.
     */
    CompletionStage<TrayHandle> createTrayItem(TraySpec spec, Consumer<String> onAction);

    /**
     * Registers an OS-wide hotkey (e.g. {@code "CmdOrCtrl+Shift+K"}; at least one modifier
     * required) that runs {@code callback} on the UI thread when pressed, even while the app is
     * unfocused. Close the returned {@link Subscription} to unregister.
     */
    CompletionStage<Subscription> registerGlobalShortcut(String accelerator, Runnable callback);

    /**
     * Posts a desktop notification. In production, delivery/appearance requires a signed app
     * bundle and the user's notification permission; the stage fails with
     * {@link ErrorCode#ILLEGAL_STATE} where notifications are unavailable.
     */
    CompletionStage<Void> showNotification(String title, String body);
    CompletionStage<MessageDialogResult> showMessageDialog(MessageDialog dialog);

    /** Shows a native, app-modal open dialog. Result paths are empty when cancelled. */
    CompletionStage<FileDialogResult> showOpenDialog(FileDialog.OpenDialog dialog);

    /** Shows a native, app-modal save dialog. Result path is empty when cancelled. */
    CompletionStage<FileDialogResult> showSaveDialog(FileDialog.SaveDialog dialog);

    /** Sends a document file (typically a PDF) straight to a printer via the OS print system. */
    CompletionStage<Void> printFile(PrintJob job);

    /**
     * Watches {@code root} for filesystem changes, delivering coalesced event batches to
     * {@code listener} on a single background thread (never the UI thread). macOS uses
     * FSEvents (sub-100&nbsp;ms, event-driven); other platforms use a recursive
     * {@code WatchService}. Registration is synchronous; close the returned handle — or
     * let shutdown do it — to stop. Listener exceptions are logged and swallowed so one
     * bad batch never kills the watch.
     */
    FileWatchHandle watchFiles(Path root, FileWatchOptions options,
            Consumer<List<FileWatchEvent>> listener);

    /**
     * Starts a child process attached to a real pseudo-terminal (a shell, REPL, or any
     * TTY program) and streams its output to {@code output} off the UI thread. The returned
     * {@link PtyHandle} writes input, resizes, and terminates. Requires a platform PTY
     * backend (macOS); throws {@link ErrorCode#ILLEGAL_STATE} where unsupported. All
     * sessions are closed at shutdown, and closing kills the process group so nothing leaks.
     */
    PtyHandle openPty(PtySpec spec, Consumer<byte[]> output);

    /** Requests orderly application shutdown without blocking the caller. */
    void requestStop();
}
