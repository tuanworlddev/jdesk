package dev.jdesk.runtime.assets;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory asset source for unit tests and generated pages. */
public final class MapAssetSource implements AssetSource {
    private final Map<String, byte[]> assets = new HashMap<>();

    public MapAssetSource() {
    }

    public MapAssetSource put(String path, byte[] bytes) {
        assets.put(path, bytes.clone());
        return this;
    }

    @Override
    public Optional<Asset> find(String normalizedPath) {
        byte[] bytes = assets.get(normalizedPath);
        if (bytes == null) {
            return Optional.empty();
        }
        return Optional.of(new Asset(bytes.length, () -> new ByteArrayInputStream(bytes)));
    }
}
