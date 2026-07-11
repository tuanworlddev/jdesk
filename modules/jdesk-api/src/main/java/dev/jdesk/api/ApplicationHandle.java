package dev.jdesk.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.net.URI;

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

    /** Opens an HTTP(S) URI without exposing a general shell execution primitive. */
    CompletionStage<Void> openExternal(URI uri);
    CompletionStage<String> readClipboardText();
    CompletionStage<Void> writeClipboardText(String text);
    CompletionStage<MessageDialogResult> showMessageDialog(MessageDialog dialog);

    /** Requests orderly application shutdown without blocking the caller. */
    void requestStop();
}
