package dev.jdesk.api;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/**
 * Marshals work onto the native UI thread. Window and WebView objects are created,
 * called, and destroyed only on their UI thread. Never block the UI thread.
 */
public interface UiDispatcher {
    boolean isUiThread();

    void execute(Runnable action);

    <T> CompletionStage<T> submit(Callable<T> action);

    /**
     * Throws {@link JDeskException} with {@link ErrorCode#ILLEGAL_STATE} when called off
     * the UI thread in development/test mode; logs and fails safe in production.
     */
    void assertUiThread();
}
