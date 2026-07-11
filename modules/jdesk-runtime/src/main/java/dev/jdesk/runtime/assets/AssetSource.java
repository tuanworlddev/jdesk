package dev.jdesk.runtime.assets;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** Provides asset bytes for already-normalized relative paths. */
public interface AssetSource {
    /**
     * @param normalizedPath canonical relative path from {@link AssetPaths#normalize};
     *        never contains {@code ..}, backslashes, or empty segments
     */
    Optional<Asset> find(String normalizedPath) throws IOException;

    /** One resolvable asset: size when known (-1 otherwise) plus a fresh stream. */
    record Asset(long size, StreamSupplier open) {
    }

    @FunctionalInterface
    interface StreamSupplier {
        InputStream open() throws IOException;
    }
}
