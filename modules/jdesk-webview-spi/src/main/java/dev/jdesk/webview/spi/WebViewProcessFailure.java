package dev.jdesk.webview.spi;

import java.util.Objects;

/** Reported when the engine's content/browser process fails (spec section 13). */
public record WebViewProcessFailure(Kind kind, String detail) {
    public enum Kind {
        RENDER_PROCESS_EXITED,
        RENDER_PROCESS_UNRESPONSIVE,
        BROWSER_PROCESS_EXITED,
        UNKNOWN
    }

    public WebViewProcessFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
    }
}
