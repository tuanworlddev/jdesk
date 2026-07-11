package dev.jdesk.api;

import java.util.concurrent.CompletionStage;

/**
 * Invokes one command with an already-deserialized request DTO. Runs on a virtual
 * thread, never on the native UI thread.
 */
@FunctionalInterface
public interface CommandHandler {
    CompletionStage<?> invoke(Object request, InvocationContext context);
}
