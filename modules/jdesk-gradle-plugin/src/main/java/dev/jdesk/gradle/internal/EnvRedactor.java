package dev.jdesk.gradle.internal;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Redacts secret-looking environment variables before they are ever logged
 * (spec section 14: "redact environment secrets from logs").
 */
public final class EnvRedactor {
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(token|secret|password|key)");
    private static final String REDACTED = "[redacted]";

    private EnvRedactor() {
    }

    /** Returns a sorted, single-line rendering with secret values replaced. */
    public static String redact(Map<String, String> environment) {
        TreeMap<String, String> sorted = new TreeMap<>(environment);
        return sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + (isSecretName(e.getKey()) ? REDACTED : e.getValue()))
                .collect(Collectors.joining(", "));
    }

    public static boolean isSecretName(String variableName) {
        return SECRET_NAME.matcher(variableName).find();
    }
}
