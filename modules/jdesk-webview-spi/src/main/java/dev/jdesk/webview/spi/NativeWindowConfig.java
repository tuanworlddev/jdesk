package dev.jdesk.webview.spi;

import dev.jdesk.api.WindowId;
import dev.jdesk.api.WebViewSessionConfig;
import java.net.URI;
import java.util.Objects;

/**
 * Window creation parameters handed to the platform adapter.
 *
 * @param minWidth minimum content width (0 = none; on Windows the minimum applies to
 *        the outer frame, matching that adapter's bounds convention)
 * @param minHeight minimum content height (0 = none)
 * @param consoleCapture inject the console-capture user script; off when no consumer
 *        (dev mode, console forwarding, automation) exists, so production pages never
 *        pay the interception cost
 * @param webViewSession validated public browser-session configuration
 */
public record NativeWindowConfig(
        WindowId id,
        String title,
        int width,
        int height,
        boolean resizable,
        URI entry,
        boolean devToolsEnabled,
        int minWidth,
        int minHeight,
        boolean consoleCapture,
        WebViewSessionConfig webViewSession) {

    public NativeWindowConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(webViewSession, "webViewSession");
    }

    public NativeWindowConfig(WindowId id, String title, int width, int height,
            boolean resizable, URI entry, boolean devToolsEnabled) {
        this(id, title, width, height, resizable, entry, devToolsEnabled, 0, 0, true,
                WebViewSessionConfig.DEFAULT);
    }

    public NativeWindowConfig(WindowId id, String title, int width, int height,
            boolean resizable, URI entry, boolean devToolsEnabled,
            int minWidth, int minHeight) {
        this(id, title, width, height, resizable, entry, devToolsEnabled,
                minWidth, minHeight, true, WebViewSessionConfig.DEFAULT);
    }

    public NativeWindowConfig(WindowId id, String title, int width, int height,
            boolean resizable, URI entry, boolean devToolsEnabled,
            int minWidth, int minHeight, boolean consoleCapture) {
        this(id, title, width, height, resizable, entry, devToolsEnabled,
                minWidth, minHeight, consoleCapture, WebViewSessionConfig.DEFAULT);
    }
}
