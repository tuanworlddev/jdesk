package dev.jdesk.api;

/** Handle for an event/listener registration. Closing is idempotent. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
