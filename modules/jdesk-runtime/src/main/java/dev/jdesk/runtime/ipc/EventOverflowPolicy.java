package dev.jdesk.runtime.ipc;

/** What happens when a per-window event queue is full (spec section 10.5). */
public enum EventOverflowPolicy {
    /** New event fails with LIMIT_EXCEEDED. */
    REJECT,
    /** Oldest queued event is dropped and counted. */
    DROP_OLDEST,
    /** A queued event with the same name is replaced by the new one. */
    COALESCE
}
