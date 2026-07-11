package dev.jdesk.api;

/**
 * Internal service binding the dependency-free API to the runtime. Provided by
 * {@code dev.jdesk.runtime}; applications never implement or call this directly.
 */
public interface JDeskBootstrap {
    int launch(ApplicationSpec spec, String[] args);
}
