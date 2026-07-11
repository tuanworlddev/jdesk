package dev.jdesk.api;

/** Application lifecycle states (spec section 5). Transitions are strictly forward. */
public enum LifecycleState {
    NEW,
    STARTING,
    READY,
    STOPPING,
    STOPPED
}
