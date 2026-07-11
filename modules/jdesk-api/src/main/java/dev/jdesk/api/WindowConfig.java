package dev.jdesk.api;

import java.net.URI;
import java.util.Objects;

/** Immutable configuration for one native window. */
public record WindowConfig(
        WindowId id,
        String title,
        int width,
        int height,
        boolean resizable,
        URI entry) {

    public WindowConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entry, "entry");
        if (width < 1 || height < 1 || width > 32767 || height > 32767) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Window size out of range");
        }
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

        public Builder resizable(boolean resizable) {
            this.resizable = resizable;
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
            return new WindowConfig(id, title, width, height, resizable, entry);
        }
    }
}
