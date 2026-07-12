package dev.jdesk.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jdesk.runtime.internal.DefensiveJson;
import dev.jdesk.runtime.json.JsonLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/**
 * Centrally managed runtime restrictions. Policy can only disable sensitive facilities;
 * it never enables a feature the application did not request.
 */
public record ManagedPolicy(
        boolean devToolsAllowed,
        boolean automationAllowed,
        boolean consoleForwardingAllowed,
        boolean externalBrowserAllowed) {
    private static final int MAX_POLICY_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "version", "devToolsAllowed", "automationAllowed",
            "consoleForwardingAllowed", "externalBrowserAllowed");

    public static ManagedPolicy permissive() {
        return new ManagedPolicy(true, true, true, true);
    }

    /** Loads {@code -Djdesk.policy.file}; absent means the documented permissive policy. */
    public static ManagedPolicy fromSystemProperties() {
        String configured = System.getProperty("jdesk.policy.file");
        return configured == null || configured.isBlank()
                ? permissive() : fromFile(Path.of(configured));
    }

    public static ManagedPolicy fromFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(normalized)
                    || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Managed policy must be a regular file");
            }
            long size = Files.size(normalized);
            if (size > MAX_POLICY_BYTES) {
                throw new IllegalArgumentException("Managed policy is too large");
            }
            return parse(Files.readString(normalized, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read managed policy", e);
        }
    }

    public static ManagedPolicy parse(String json) {
        JsonNode root;
        try {
            root = DefensiveJson.build(JsonLimits.DEFAULTS).readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed managed policy", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Managed policy must be a JSON object");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                throw new IllegalArgumentException("Unknown managed policy field: " + name);
            }
        });
        JsonNode version = root.get("version");
        if (version == null || !version.isInt() || version.intValue() != 1) {
            throw new IllegalArgumentException("Managed policy version must be 1");
        }
        return new ManagedPolicy(readBoolean(root, "devToolsAllowed", true),
                readBoolean(root, "automationAllowed", true),
                readBoolean(root, "consoleForwardingAllowed", true),
                readBoolean(root, "externalBrowserAllowed", true));
    }

    private static boolean readBoolean(JsonNode root, String name, boolean defaultValue) {
        JsonNode value = root.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("Managed policy field must be boolean: " + name);
        }
        return value.booleanValue();
    }
}
