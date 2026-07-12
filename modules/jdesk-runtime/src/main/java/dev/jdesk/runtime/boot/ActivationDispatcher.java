package dev.jdesk.runtime.boot;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Serializes every activation source onto one non-UI virtual thread. */
final class ActivationDispatcher implements AutoCloseable {
    private static final Logger LOG = System.getLogger(ActivationDispatcher.class.getName());

    private final Consumer<List<String>> handler;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("jdesk-activation", 0).factory());

    ActivationDispatcher(Consumer<List<String>> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    void dispatch(List<String> arguments) {
        List<String> copy = List.copyOf(arguments);
        try {
            executor.execute(() -> {
                try {
                    handler.accept(copy);
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "Application activation handler failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.DEBUG, "Activation ignored during shutdown");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
