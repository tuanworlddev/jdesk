package dev.jdesk.api;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for one native window.
 *
 * @param minWidth minimum content width the user can resize to; 0 means no minimum
 * @param minHeight minimum content height; 0 means no minimum
 * @param startMaximized open maximized (after the remembered bounds, when both are set)
 * @param rememberBounds persist size/position across runs (per application id and
 *        window id, under {@code ~/.jdesk/window-state/}) and restore them on open
 * @param position initial top-left position; empty lets the OS place/center the window.
 *        Remembered bounds, when present, take precedence over this.
 */
public record WindowConfig(
        WindowId id,
        String title,
        int width,
        int height,
        boolean resizable,
        URI entry,
        int minWidth,
        int minHeight,
        boolean startMaximized,
        boolean rememberBounds,
        Optional<Position> position) {

    /** Top-left window position in logical screen coordinates. */
    public record Position(int x, int y) {
    }

    public WindowConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(position, "position");
        if (width < 1 || height < 1 || width > 32767 || height > 32767) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Window size out of range");
        }
        if (minWidth < 0 || minHeight < 0 || minWidth > 32767 || minHeight > 32767) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Window minimum size out of range");
        }
        if (minWidth > width || minHeight > height) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Window minimum size exceeds the initial size");
        }
    }

    public WindowConfig(WindowId id, String title, int width, int height,
            boolean resizable, URI entry) {
        this(id, title, width, height, resizable, entry, 0, 0, false, false, Optional.empty());
    }

    public WindowConfig(WindowId id, String title, int width, int height,
            boolean resizable, URI entry, int minWidth, int minHeight,
            boolean startMaximized, boolean rememberBounds) {
        this(id, title, width, height, resizable, entry, minWidth, minHeight,
                startMaximized, rememberBounds, Optional.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private WindowId id;
        private String title = "";
        private int width = 800;
        private int height = 600;
        private boolean resizable = true;
        private URI entry;
        private int minWidth;
        private int minHeight;
        private boolean startMaximized;
        private boolean rememberBounds;
        private Optional<Position> position = Optional.empty();

        private Builder() {
        }

        public Builder id(String id) {
            this.id = new WindowId(id);
            return this;
        }

        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /** Minimum content size the user can resize down to. */
        public Builder minSize(int minWidth, int minHeight) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            return this;
        }

        public Builder resizable(boolean resizable) {
            this.resizable = resizable;
            return this;
        }

        /** Opens the window maximized. */
        public Builder startMaximized(boolean startMaximized) {
            this.startMaximized = startMaximized;
            return this;
        }

        /**
         * Initial top-left position in logical screen coordinates. Useful to place
         * windows side by side (e.g. two instances for multiplayer testing). Remembered
         * bounds, when present, win over this.
         */
        public Builder position(int x, int y) {
            this.position = Optional.of(new Position(x, y));
            return this;
        }

        /**
         * Persists this window's size/position across runs and restores them on open.
         * State lives under {@code ~/.jdesk/window-state/<applicationId>.properties}.
         */
        public Builder rememberBounds(boolean rememberBounds) {
            this.rememberBounds = rememberBounds;
            return this;
        }

        public Builder entry(String entry) {
            this.entry = URI.create(Objects.requireNonNull(entry, "entry"));
            return this;
        }

        public WindowConfig build() {
            if (id == null) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST, "Window id is required");
            }
            if (entry == null) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST, "Window entry is required");
            }
            return new WindowConfig(id, title, width, height, resizable, entry,
                    minWidth, minHeight, startMaximized, rememberBounds, position);
        }
    }
}
