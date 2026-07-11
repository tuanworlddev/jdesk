package dev.jdesk.api;

/** Application lifecycle hooks. All methods default to no-ops. */
public interface LifecycleListener {
    default void onStarting() {
    }

    default void onReady() {
    }

    /**
     * Called when the application is ready, with its public control handle. Existing
     * listeners overriding {@link #onReady()} continue to work.
     */
    default void onReady(ApplicationHandle application) {
        onReady();
    }

    /** Return false to veto the close request. */
    default boolean onCloseRequested(WindowId windowId) {
        return true;
    }

    default void onStopping() {
    }

    default void onStopped() {
    }
}
