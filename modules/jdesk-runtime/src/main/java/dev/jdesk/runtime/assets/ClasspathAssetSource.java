package dev.jdesk.runtime.assets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Serves assets from classpath resources under a fixed prefix (e.g. {@code web/}).
 * Paths are already normalized by {@link AssetPaths}, so traversal cannot reach
 * resources outside the prefix.
 */
public final class ClasspathAssetSource implements AssetSource {
    private final ClassLoader loader;
    private final String prefix;

    public ClasspathAssetSource(ClassLoader loader, String prefix) {
        this.loader = loader;
        this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
    }

    @Override
    public Optional<Asset> find(String normalizedPath) throws IOException {
        try (InputStream in = loader.getResourceAsStream(prefix + normalizedPath)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            return Optional.of(new Asset(bytes.length, () -> new ByteArrayInputStream(bytes)));
        }
    }
}
