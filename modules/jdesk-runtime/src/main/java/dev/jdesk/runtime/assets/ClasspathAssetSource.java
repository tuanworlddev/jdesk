package dev.jdesk.runtime.assets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.util.Optional;

/**
 * Serves assets from classpath resources under a fixed prefix (e.g. {@code web/}).
 * Paths are already normalized by {@link AssetPaths}, so traversal cannot reach
 * resources outside the prefix.
 */
public final class ClasspathAssetSource implements AssetSource {
    private final ClassLoader loader;
    private final Module module;
    private final String prefix;

    public ClasspathAssetSource(ClassLoader loader, String prefix) {
        this.loader = loader;
        this.module = null;
        this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** Uses JPMS-aware resource lookup for assets encapsulated in a named module. */
    public ClasspathAssetSource(Module module, String prefix) {
        this.loader = null;
        this.module = module;
        this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
    }

    @Override
    public Optional<Asset> find(String normalizedPath) throws IOException {
        if (module != null) {
            return findInModule(prefix + normalizedPath);
        }
        try (InputStream in = loader.getResourceAsStream(prefix + normalizedPath)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            return Optional.of(new Asset(bytes.length, () -> new ByteArrayInputStream(bytes)));
        }
    }

    private Optional<Asset> findInModule(String resourceName) throws IOException {
        if (module.getLayer() == null) {
            return Optional.empty();
        }
        var resolved = module.getLayer().configuration().findModule(module.getName());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        try (ModuleReader reader = resolved.get().reference().open();
                InputStream in = reader.open(resourceName).orElse(null)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            return Optional.of(new Asset(bytes.length, () -> new ByteArrayInputStream(bytes)));
        }
    }
}
