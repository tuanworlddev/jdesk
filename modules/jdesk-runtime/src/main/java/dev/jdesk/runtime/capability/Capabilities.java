package dev.jdesk.runtime.capability;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.internal.DefensiveJson;
import dev.jdesk.runtime.json.JsonLimits;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads {@code jdesk-capabilities.json}:
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "grants": [
 *     { "capability": "greeting:use", "windows": ["main"] },
 *     { "capability": "clipboard:read" }
 *   ]
 * }
 * }</pre>
 *
 * Omitted {@code windows} grants to all windows. Unknown fields are rejected: a typo in
 * a capability file must fail startup, not silently widen or narrow permissions.
 */
public final class Capabilities {
    private Capabilities() {
    }

    public static CapabilitySet fromResource(String resourceName) {
        return fromResource(resourceName, Thread.currentThread().getContextClassLoader());
    }

    public static CapabilitySet fromResource(String resourceName, ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Capability resource not found: " + resourceName);
            }
            return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Failed to read capability resource: " + resourceName, e);
        }
    }

    public static CapabilitySet parse(String json) {
        JsonNode root;
        try {
            root = DefensiveJson.build(JsonLimits.DEFAULTS).readTree(json);
        } catch (Exception e) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Malformed capability JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Capability file must be a JSON object");
        }
        rejectUnknown(root, Set.of("version", "grants"));
        JsonNode version = root.get("version");
        if (version == null || !version.isInt() || version.intValue() != 1) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Capability file version must be 1");
        }
        JsonNode grantsNode = root.get("grants");
        if (grantsNode == null || !grantsNode.isArray()) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Capability file requires a grants array");
        }
        Set<CapabilityGrant> grants = new LinkedHashSet<>();
        for (JsonNode grantNode : grantsNode) {
            if (!grantNode.isObject()) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Grant entries must be objects");
            }
            rejectUnknown(grantNode, Set.of("capability", "windows"));
            JsonNode capability = grantNode.get("capability");
            if (capability == null || !capability.isTextual()) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Grant requires a capability string");
            }
            Set<String> windows = new HashSet<>();
            JsonNode windowsNode = grantNode.get("windows");
            if (windowsNode != null) {
                if (!windowsNode.isArray()) {
                    throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Grant windows must be an array");
                }
                for (JsonNode w : windowsNode) {
                    if (!w.isTextual()) {
                        throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Window ids must be strings");
                    }
                    windows.add(w.textValue());
                }
            }
            grants.add(new CapabilityGrant(capability.textValue(), windows));
        }
        return CapabilitySet.of(grants);
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed) {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Unknown field in capability file: " + name);
            }
        }
    }
}
