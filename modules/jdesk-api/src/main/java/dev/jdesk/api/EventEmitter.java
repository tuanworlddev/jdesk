package dev.jdesk.api;

/**
 * Emits Java-to-JavaScript events. Events to one window from one emitter preserve
 * enqueue order; queues are bounded (spec section 10.5).
 */
public interface EventEmitter {
    /**
     * @param eventName 1..128 chars, same grammar as command names
     * @param payload serialized with the configured {@code JsonCodec}; may be null
     * @throws JDeskException with {@link ErrorCode#LIMIT_EXCEEDED} when the target queue
     *         is full and the overflow policy is {@code REJECT}
     */
    void emit(String eventName, Object payload);
}
