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

    public static final IpcLimits DEFAULTS =
            new IpcLimits(1_048_576, 128, Duration.ofSeconds(30), 256, 128);

    public IpcLimits {
        if (maxMessageBytes < 1 || maxMessageBytes > DEFAULTS.maxMessageBytes()
                || maxInFlightPerWindow < 1 || maxInFlightPerWindow > DEFAULTS.maxInFlightPerWindow()
                || defaultCommandTimeout.isNegative() || defaultCommandTimeout.isZero()
                || defaultCommandTimeout.compareTo(DEFAULTS.defaultCommandTimeout()) > 0
                || maxQueuedEventsPerWindow < 1
                || maxQueuedEventsPerWindow > DEFAULTS.maxQueuedEventsPerWindow()
                || maxNameLength < 1 || maxNameLength > DEFAULTS.maxNameLength()) {
            throw new IllegalArgumentException(
                    "IPC limits may only be lowered from the defaults " + DEFAULTS);
        }
    }
}
