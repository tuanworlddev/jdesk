package dev.jdesk.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a plugin declares about itself: its identity, the exact set of capabilities it needs, an
 * integrity hash of its jar, and an optional Ed25519 signature over that jar. The declaration is
 * a contract the host checks against — a plugin gets no capability the app has not explicitly
 * granted ({@link PluginAuthorization}), and its bytes must match {@code sha256} (and the signature,
 * if present) before it loads ({@link PluginIntegrity}).
 */
public record PluginManifest(
        String pluginId,
        String version,
        Set<String> capabilities,
        String sha256,
        String signature) {

    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(6).maxStringLength(4096).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PluginManifest {
        if (pluginId == null || !pluginId.matches("[a-z0-9]+([._-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid plugin id: " + pluginId);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Plugin version is required");
        }
        Set<String> copy = new LinkedHashSet<>();
        if (capabilities != null) {
            for (String capability : capabilities) {
                if (capability == null || capability.isBlank()) {
                    throw new IllegalArgumentException("Blank capability in plugin " + pluginId);
                }
                copy.add(capability);
            }
        }
        capabilities = Set.copyOf(copy);
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Plugin sha256 must be 64 hex chars");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (signature != null) {
            if (signature.isBlank()) {
                signature = null;
            } else {
                try {
                    if (Base64.getDecoder().decode(signature).length != 64) {
                        throw new IllegalArgumentException("Plugin signature must be 64 bytes");
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid plugin signature", e);
                }
            }
        }
    }

    /** True when the plugin ships an Ed25519 signature that a trust root must verify. */
    public boolean isSigned() {
        return signature != null;
    }

    /** Strictly parses a plugin manifest from JSON. */
    public static PluginManifest parse(byte[] json, int maxBytes) {
        Objects.requireNonNull(json, "json");
        if (json.length > maxBytes) {
            throw new IllegalArgumentException("Plugin manifest is too large");
        }
        try {
            return MAPPER.readValue(json, PluginManifest.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not parse plugin manifest", e);
        }
    }
}
