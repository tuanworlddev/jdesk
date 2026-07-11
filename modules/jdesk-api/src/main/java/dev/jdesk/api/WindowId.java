package dev.jdesk.api;

import java.util.regex.Pattern;

/** Stable window identifier: 1..64 chars of [a-zA-Z0-9._-]. */
public record WindowId(String value) {
    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9._-]{1,64}");

    public WindowId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Window id must match [a-zA-Z0-9._-]{1,64}");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
