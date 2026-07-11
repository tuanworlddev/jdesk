package dev.jdesk.webview.spi;

import dev.jdesk.api.WindowId;
import java.net.URI;
import java.util.Objects;

/** Window creation parameters handed to the platform adapter. */
public record NativeWindowConfig(
        WindowId id,
        String title,
        int width,
        int height,
        boolean resizable,
        URI entry,
        boolean devToolsEnabled) {

    public NativeWindowConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entry, "entry");
    }
}
