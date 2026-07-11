package dev.jdesk.webview.spi;

/**
 * PNG capture from the engine's real snapshot API.
 *
 * @param width pixel width
 * @param height pixel height
 * @param png encoded PNG bytes (defensively copied)
 */
public record WebViewSnapshot(int width, int height, byte[] png) {
    public WebViewSnapshot {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Snapshot dimensions must be positive");
        }
        png = png.clone();
    }

    @Override
    public byte[] png() {
        return png.clone();
    }
}
