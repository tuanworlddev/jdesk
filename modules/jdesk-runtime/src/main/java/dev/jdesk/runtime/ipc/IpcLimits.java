package dev.jdesk.runtime.ipc;

import java.time.Duration;

/**
 * IPC limits (spec section 10.3). Configurable downward only; raising any value above
 * the defaults is a construction error, never silent.
 */
public record IpcLimits(
        int maxMessageBytes,
        int maxInFlightPerWindow,
        Duration defaultCommandTimeout,
        int maxQueuedEventsPerWindow,
        int maxNameLength) {

    // Literal ceilings: the compact constructor must not read DEFAULTS, which is still
    // null while DEFAULTS itself is being constructed during class initialization.
    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final int MAX_IN_FLIGHT = 128;
    private static final Duration MAX_COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_QUEUED_EVENTS = 256;
    private static final int MAX_NAME_LENGTH = 128;

    public static final IpcLimits DEFAULTS = new IpcLimits(
            MAX_MESSAGE_BYTES, MAX_IN_FLIGHT, MAX_COMMAND_TIMEOUT,
            MAX_QUEUED_EVENTS, MAX_NAME_LENGTH);

    public IpcLimits {
        if (maxMessageBytes < 1 || maxMessageBytes > MAX_MESSAGE_BYTES
                || maxInFlightPerWindow < 1 || maxInFlightPerWindow > MAX_IN_FLIGHT
                || defaultCommandTimeout.isNegative() || defaultCommandTimeout.isZero()
                || defaultCommandTimeout.compareTo(MAX_COMMAND_TIMEOUT) > 0
                || maxQueuedEventsPerWindow < 1
                || maxQueuedEventsPerWindow > MAX_QUEUED_EVENTS
                || maxNameLength < 1 || maxNameLength > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "IPC limits may only be lowered from the defaults (1MiB, 128, 30s, 256, 128)");
        }
    }
}
