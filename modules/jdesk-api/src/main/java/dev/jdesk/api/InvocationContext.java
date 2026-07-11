package dev.jdesk.api;

/** Per-invocation context passed to every command handler. */
public interface InvocationContext {
    WindowId windowId();

    String commandName();

    /** Request id, unique within the navigation session. */
    String requestId();

    PlatformInfo platform();

    /** Emitter targeting the invoking window. */
    EventEmitter events();

    /** True once the client cancelled or the command timed out. */
    boolean isCancelled();
}
