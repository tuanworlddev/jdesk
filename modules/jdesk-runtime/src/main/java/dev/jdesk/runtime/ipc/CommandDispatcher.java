package dev.jdesk.runtime.ipc;

import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PermissionDecision;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.capability.CapabilityEngine;
import dev.jdesk.runtime.json.JsonCodec;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Per-window IPC dispatcher (spec sections 10 and 7). Entry point is
 * {@link #onMessage(String, String)}, called with raw JSON from the platform bridge.
 * Commands run on virtual threads; results marshal back through the responder, which
 * the runtime wires to post via {@code UiDispatcher}. Exactly one terminal result is
 * sent per request; late results after navigation or close are dropped.
 */
public final class CommandDispatcher implements AutoCloseable {
    private static final Logger LOG = System.getLogger(CommandDispatcher.class.getName());

    private final WindowId windowId;
    private final CommandRegistry registry;
    private final CapabilityEngine capabilityEngine;
    private final JsonCodec codec;
    private final EnvelopeCodec envelopes;
    private final IpcLimits limits;
    private final PlatformInfo platformInfo;
    private final Consumer<String> responder;
    private final InvocationTracker tracker;
    private final ExecutorService commandExecutor;
    private final ScheduledExecutorService scheduler;
    private final Duration navigationGrace;
    private final EventOverflowPolicy overflowPolicy;

    private volatile NavigationSession session;
    private volatile boolean helloCompleted;
    private volatile boolean closed;
    private volatile EventQueue eventQueue;

    public CommandDispatcher(
            WindowId windowId,
            CommandRegistry registry,
            CapabilityEngine capabilityEngine,
            JsonCodec codec,
            IpcLimits limits,
            PlatformInfo platformInfo,
            EventOverflowPolicy overflowPolicy,
            Duration navigationGrace,
            Consumer<String> responder) {
        this.windowId = windowId;
        this.registry = registry;
        this.capabilityEngine = capabilityEngine;
        this.codec = codec;
        this.envelopes = new EnvelopeCodec(codec, limits);
        this.limits = limits;
        this.platformInfo = platformInfo;
        this.responder = responder;
        this.navigationGrace = navigationGrace;
        this.tracker = new InvocationTracker(limits.maxInFlightPerWindow());
        this.commandExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jdesk-timeout-" + windowId);
            thread.setDaemon(true);
            return thread;
        });
        this.overflowPolicy = overflowPolicy;
        this.session = new NavigationSession();
        this.eventQueue = newEventQueue(overflowPolicy);
    }

    private EventQueue newEventQueue(EventOverflowPolicy policy) {
        NavigationSession target = session;
        return new EventQueue(limits.maxQueuedEventsPerWindow(), policy, json -> {
            if (!closed && target == session && !target.isInvalidated()) {
                responder.accept(json);
            }
        });
    }

    /** Nonce the platform layer must inject into the page for the current navigation. */
    public String currentNonce() {
        return session.nonce();
    }

    public int pendingInvocations() {
        return tracker.pending();
    }

    public int pendingEvents() {
        return eventQueue.pending();
    }

    /** Raw message from the WebView bridge. Never throws; protocol errors become results. */
    public void onMessage(String raw, String origin) {
        if (closed) {
            return;
        }
        IncomingEnvelope envelope;
        try {
            envelope = envelopes.parse(raw);
        } catch (ProtocolException e) {
            LOG.log(Level.DEBUG, "Rejected malformed envelope for {0}: {1}", windowId, e.code());
            return; // No trustworthy correlation id: nothing to respond to.
        }
        switch (envelope) {
            case IncomingEnvelope.Hello hello -> handleHello(hello);
            case IncomingEnvelope.Invoke invoke -> handleInvoke(invoke, origin);
            case IncomingEnvelope.Cancel cancel -> handleCancel(cancel);
        }
    }

    private void handleHello(IncomingEnvelope.Hello hello) {
        NavigationSession current = session;
        if (!current.accepts(hello.nonce())) {
            LOG.log(Level.DEBUG, "Rejected hello with stale nonce for {0}", windowId);
            return;
        }
        helloCompleted = true;
        respond(current, envelopes.helloAck(current.nonce()));
    }

    private void handleInvoke(IncomingEnvelope.Invoke invoke, String origin) {
        NavigationSession current = session;
        if (!current.accepts(invoke.nonce())) {
            respond(current, envelopes.errorResult(invoke.id(), ErrorCode.STALE_NONCE,
                    "Navigation session is stale"));
            return;
        }
        if (!helloCompleted) {
            respond(current, envelopes.errorResult(invoke.id(), ErrorCode.INVALID_REQUEST,
                    "Handshake required before commands"));
            return;
        }
        if (!current.registerRequestId(invoke.id())) {
            respond(current, envelopes.errorResult(invoke.id(), ErrorCode.INVALID_REQUEST,
                    "Duplicate request id"));
            return;
        }
        CommandDefinition definition = registry.find(invoke.command()).orElse(null);
        if (definition == null) {
            respond(current, envelopes.errorResult(invoke.id(), ErrorCode.UNKNOWN_COMMAND,
                    "Unknown command"));
            return;
        }
        // Capability check strictly before payload deserialization (spec 12.1).
        PermissionDecision decision =
                capabilityEngine.evaluate(definition, windowId, origin, current);
        if (!decision.allowed()) {
            respond(current, envelopes.errorResult(invoke.id(), decision.errorCode(),
                    decision.publicReason()));
            return;
        }
        InvocationTracker.Invocation invocation = tracker.tryBegin(invoke.id(), current);
        if (invocation == null) {
            respond(current, envelopes.errorResult(invoke.id(), ErrorCode.LIMIT_EXCEEDED,
                    "Too many in-flight requests"));
            return;
        }
        Duration timeout = definition.timeout().orElse(limits.defaultCommandTimeout());
        try {
            commandExecutor.execute(() -> runInvocation(definition, invoke, invocation, timeout));
        } catch (RejectedExecutionException e) {
            if (invocation.tryTerminate()) {
                tracker.remove(invoke.id());
                respond(current, envelopes.errorResult(invoke.id(), ErrorCode.WINDOW_CLOSED,
                        "Window is closing"));
            }
        }
    }

    private void runInvocation(
            CommandDefinition definition,
            IncomingEnvelope.Invoke invoke,
            InvocationTracker.Invocation invocation,
            Duration timeout) {
        invocation.bindWorker(Thread.currentThread());
        ScheduledFuture<?> timeoutTask = scheduler.schedule(
                () -> terminate(invocation, envelopes.errorResult(invoke.id(), ErrorCode.TIMEOUT,
                        "Command timed out"), true),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            Object request = decodePayload(definition, invoke);
            InvocationContext context = new Context(invocation, definition.name());
            CompletionStage<?> stage = definition.handler().invoke(request, context);
            if (stage == null) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Command returned no result");
            }
            stage.whenComplete((value, failure) -> {
                timeoutTask.cancel(false);
                if (failure != null) {
                    terminate(invocation, errorFor(invoke.id(), failure, invocation), false);
                } else {
                    terminate(invocation, envelopes.successResult(invoke.id(), value), false);
                }
            });
        } catch (Throwable t) {
            timeoutTask.cancel(false);
            terminate(invocation, errorFor(invoke.id(), t, invocation), false);
        } finally {
            invocation.bindWorker(null);
            Thread.interrupted(); // clear a late interrupt so the pooled carrier is clean
        }
    }

    private Object decodePayload(CommandDefinition definition, IncomingEnvelope.Invoke invoke) {
        if (definition.requestType() == Void.class) {
            return null;
        }
        String payload = invoke.payloadJson().orElseThrow(() -> new JDeskException(
                ErrorCode.INVALID_REQUEST, "Command requires a payload"));
        return codec.decode(payload, definition.requestType());
    }

    private String errorFor(String id, Throwable failure, InvocationTracker.Invocation invocation) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (invocation.isCancelled()
                || cause instanceof InterruptedException
                || cause instanceof java.util.concurrent.CancellationException) {
            return envelopes.errorResult(id, ErrorCode.CANCELLED, "Command was cancelled");
        }
        if (cause instanceof JDeskException jde) {
            return envelopes.errorResult(id, jde.code(), jde.publicMessage());
        }
        LOG.log(Level.ERROR, "Command {0} failed internally", id, cause);
        return envelopes.errorResult(id, ErrorCode.INTERNAL_ERROR, "Command failed");
    }

    /**
     * Sends the terminal result exactly once and releases tracking. The terminal CAS is
     * won BEFORE any interrupt: otherwise the interrupted worker could race in with a
     * CANCELLED result and make a timeout non-deterministic.
     */
    private void terminate(InvocationTracker.Invocation invocation, String resultJson,
            boolean interruptWorker) {
        if (!invocation.tryTerminate()) {
            return;
        }
        if (interruptWorker) {
            invocation.cancel();
        }
        tracker.remove(invocation.id());
        respond(invocation.session(), resultJson);
    }

    private void handleCancel(IncomingEnvelope.Cancel cancel) {
        NavigationSession current = session;
        if (!current.accepts(cancel.nonce())) {
            return;
        }
        InvocationTracker.Invocation invocation = tracker.find(cancel.id());
        if (invocation != null) {
            terminate(invocation, envelopes.errorResult(cancel.id(), ErrorCode.CANCELLED,
                    "Command was cancelled"), true);
        }
    }

    /** Drops output bound to a stale session so late results never reach a new document. */
    private void respond(NavigationSession target, String json) {
        if (closed || target != session || target.isInvalidated()) {
            return;
        }
        responder.accept(json);
    }

    /** Emitter for events targeted at this window. */
    public EventEmitter emitter() {
        return (eventName, payload) -> {
            if (eventName == null || eventName.isEmpty() || eventName.length() > limits.maxNameLength()) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST, "Invalid event name");
            }
            if (closed) {
                throw new JDeskException(ErrorCode.WINDOW_CLOSED, "Window is closed");
            }
            eventQueue.enqueue(eventName, envelopes.event(eventName, payload));
        };
    }

    /**
     * Main-frame navigation committed: invalidate the nonce, reject new invokes, cancel
     * outstanding requests after the grace period, drop queued events.
     */
    public void onNavigationCommitted() {
        NavigationSession old = session;
        EventQueue oldQueue = eventQueue;
        session = new NavigationSession();
        helloCompleted = false;
        eventQueue = newEventQueue(overflowPolicy);
        old.invalidate();
        oldQueue.close();
        if (navigationGrace.isZero()) {
            cancelSessionInvocations(old);
        } else {
            scheduler.schedule(() -> cancelSessionInvocations(old),
                    navigationGrace.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void cancelSessionInvocations(NavigationSession target) {
        for (InvocationTracker.Invocation invocation : tracker.cancelSession(target)) {
            if (invocation.tryTerminate()) {
                tracker.remove(invocation.id());
                // Terminal result is intentionally NOT sent: the document is gone.
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        session.invalidate();
        eventQueue.close();
        for (InvocationTracker.Invocation invocation : tracker.cancelAll()) {
            if (invocation.tryTerminate()) {
                tracker.remove(invocation.id());
            }
        }
        commandExecutor.shutdown();
        scheduler.shutdown();
        try {
            if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                commandExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            commandExecutor.shutdownNow();
        }
    }

    /** Per-invocation context handed to command handlers. */
    private final class Context implements InvocationContext {
        private final InvocationTracker.Invocation invocation;
        private final String commandName;

        Context(InvocationTracker.Invocation invocation, String commandName) {
            this.invocation = invocation;
            this.commandName = commandName;
        }

        @Override
        public WindowId windowId() {
            return windowId;
        }

        @Override
        public String commandName() {
            return commandName;
        }

        @Override
        public String requestId() {
            return invocation.id();
        }

        @Override
        public PlatformInfo platform() {
            return platformInfo;
        }

        @Override
        public EventEmitter events() {
            return emitter();
        }

        @Override
        public boolean isCancelled() {
            return invocation.isCancelled();
        }
    }
}
