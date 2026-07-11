package dev.jdesk.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Compile-time-generated command metadata plus its handler.
 *
 * @param name wire name, e.g. {@code greeting.greet}
 * @param requiredCapability empty only for {@link PublicDesktopCommand} commands
 * @param requestType DTO type the payload is deserialized to before the handler runs
 * @param timeout maximum duration; empty uses the runtime default (30 s)
 */
public record CommandDefinition(
        String name,
        Optional<String> requiredCapability,
        Class<?> requestType,
        Optional<Duration> timeout,
        CommandHandler handler) {

    private static final Pattern NAME =
            Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*");

    public CommandDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(handler, "handler");
        if (name.length() > 128 || !NAME.matcher(name).matches()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Command name must be dot-separated camelCase segments, max 128 chars");
        }
    }
}
