package dev.jdesk.ffm;

/** Atomic state machine for every native handle wrapper (spec section 6.3). */
public enum HandleState {
    NEW,
    OPEN,
    CLOSING,
    CLOSED
}
