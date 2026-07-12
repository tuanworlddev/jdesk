package dev.jdesk.updater;

import java.util.Locale;

/** Release channel selected by an application or centrally managed policy. */
public enum UpdateChannel {
    STABLE,
    BETA,
    INTERNAL;

    public static UpdateChannel parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Update channel must not be blank");
        }
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
