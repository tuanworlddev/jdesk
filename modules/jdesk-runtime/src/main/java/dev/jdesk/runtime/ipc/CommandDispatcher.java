package dev.jdesk.runtime.ipc;

import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.BinaryStream;
import dev.jdesk.api.ApplicationHandle;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private final CommandRegistry frontendEvents;
    private final CapabilityEngine capabilityEngine;
    private final JsonCodec codec;
    private final EnvelopeCodec envelopes;
    private final IpcLimits limits;
    private final PlatformInfo platformInfo;
    private final Function<String, CompletionStage<Void>> responder;
    private final InvocationTracker tracker;
    private final ExecutorService commandExecutor;
    private final ThreadPoolExecutor ingressExecutor;
    private final ScheduledExecutorService scheduler;
    private final Duration navigationGrace;
    private final EventOverflowPolicy overflowPolicy;
    private final ApplicationHandle applicationHandle;
    private final Semaphore frontendEventPermits;

    private volatile NavigationSession session;
    private volatile boolean helloCompleted;
    private volatile boolean closed;
    private volatile EventQueue eventQueue;
    private volatile StreamManager streams = new StreamManager();
    private volatile java.util.function.BiConsumer<String, String> consoleListener;

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
        this(windowId, registry, CommandRegistry.of(), capabilityEngine, codec, limits, platformInfo,
                overflowPolicy, navigationGrace, json -> {
                    responder.accept(json);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }, null);
    }

    public CommandDispatcher(
            WindowId windowId,
            CommandRegistry registry,
            CapabilityEngine capabilityEngine,
            JsonCodec codec,
            IpcLimits limits,
            PlatformInfo platformInfo,
            EventOverflowPolicy overflowPolicy,
            Duration navigationGrace,
            Function<String, CompletionStage<Void>> responder) {
        this(windowId, registry, CommandRegistry.of(), capabilityEngine, codec, limits, platformInfo,
                overflowPolicy, navigationGrace, responder, null);
    }

    public CommandDispatcher(
            WindowId windowId,
            CommandRegistry registry,
            CapabilityEngine capabilityEngine,
            JsonCodec codec,
            IpcLimits limits,
            PlatformInfo platformInfo,
            EventOverflowPolicy overflowPolicy,
            Duration navigationGrace,
            Function<String, CompletionStage<Void>> responder,
            ApplicationHandle applicationHandle) {
        this(windowId,registry,CommandRegistry.of(),capabilityEngine,codec,limits,platformInfo,
                overflowPolicy,navigationGrace,responder,applicationHandle);
    }

    public CommandDispatcher(WindowId windowId,CommandRegistry registry,
            CommandRegistry frontendEvents,CapabilityEngine capabilityEngine,JsonCodec codec,
            IpcLimits limits,PlatformInfo platformInfo,EventOverflowPolicy overflowPolicy,
            Duration navigationGrace,Function<String,CompletionStage<Void>> responder,
            ApplicationHandle applicationHandle) {
        this.windowId = windowId;
        this.registry = registry;
        this.frontendEvents = frontendEvents;
        this.capabilityEngine = capabilityEngine;
        this.codec = codec;
        this.envelopes = new EnvelopeCodec(codec, limits);
        this.limits = limits;
        this.platformInfo = platformInfo;
        this.responder = responder;
        this.applicationHandle = applicationHandle;
        this.navigationGrace = navigationGrace;
        this.tracker = new InvocationTracker(limits.maxInFlightPerWindow());
        this.commandExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.ingressExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.maxQueuedEventsPerWindow()),
                Thread.ofVirtual().name("jdesk-ingress-" + windowId + "-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jdesk-timeout-" + windowId);
            thread.setDaemon(true);
            return thread;
        });
        this.overflowPolicy = overflowPolicy;
        this.frontendEventPermits = new Semaphore(limits.maxInFlightPerWindow());
        this.session = new NavigationSession();
        this.eventQueue = newEventQueue(overflowPolicy);
    }

    private EventQueue newEventQueue(EventOverflowPolicy policy) {
        NavigationSession target = session;
        return new EventQueue(limits.maxQueuedEventsPerWindow(), policy, json -> {
            if (!closed && target == session && !target.isInvalidated()) {
                return responder.apply(json);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
    }

    /** Nonce the platform layer must inject into the page for the current navigation. */
    public String currentNonce() {
        return session.nonce();
    }

    /** Serialized control envelope carrying the current nonce (posted after commit). */
    public String currentNonceEnvelope() {
        return envelopes.nonceEnvelope(session.nonce());
    }

    public int pendingInvocations() {
        return tracker.pending();
    }

    public int pendingEvents() {
        return eventQueue.pending();
    }

    /**
     * Enqueues a raw native message without parsing or blocking the caller. The bounded,
     * serial ingress preserves WebView message order while keeping work off the UI thread.
     */
    public void postMessage(String raw, String origin) {
        if (closed) {
            return;
        }
        try {
            ingressExecutor.execute(() -> onMessage(raw, origin));
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "IPC ingress queue full for {0}; dropping message", windowId);
        }
    }

    /** Synchronous protocol entry point used by the ingress worker and focused unit tests. */
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
            case IncomingEnvelope.FrontendEvent event -> handleFrontendEvent(event, origin);
            case IncomingEnvelope.ConsoleLog console -> handleConsole(console);
            case IncomingEnvelope.UnsupportedVersion unsupported -> handleUnsupportedVersion(unsupported);
        }
    }

    /**
     * Sink for validated page-console envelopes (level, sanitized message). Unset
     * (the default) drops them, so production stays quiet unless explicitly enabled.
     */
    public void onConsole(java.util.function.BiConsumer<String, String> listener) {
        this.consoleListener = listener;
    }

    /** Per-navigation ceiling on forwarded console lines (flood protection). */
    private static final int MAX_CONSOLE_LINES_PER_NAVIGATION = 2000;
    private final java.util.concurrent.atomic.AtomicInteger consoleLinesForwarded =
            new java.util.concurrent.atomic.AtomicInteger();

    private void handleConsole(IncomingEnvelope.ConsoleLog console) {
        java.util.function.BiConsumer<String, String> listener = consoleListener;
        if (listener == null) {
            return;
        }
        if (!session.accepts(console.nonce())) {
            return; // stale or spoofed nonce: not from the current document
        }
        // Deliberately no hello requirement: crash-before-handshake lines are exactly
        // what the bridge exists to surface. The nonce proves the current document.
        int forwarded = consoleLinesForwarded.incrementAndGet();
        if (forwarded > MAX_CONSOLE_LINES_PER_NAVIGATION) {
            if (forwarded == MAX_CONSOLE_LINES_PER_NAVIGATION + 1) {
                listener.accept("warn", "(console forwarding suspended: more than "
                        + MAX_CONSOLE_LINES_PER_NAVIGATION + " lines this navigation)");
            }
            return;
        }
        listener.accept(console.level(), sanitizeConsoleMessage(console.message()));
    }

    /**
     * Page-controlled text: neutralize control characters so a page can never forge
     * additional log records. Newlines/tabs become visible escapes — a multi-line stack
     * trace stays one log line.
     */
    private static String sanitizeConsoleMessage(String message) {
        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\n') {
                out.append("\\n");
            } else if (c == '\t') {
                out.append("\\t");
            } else {
                out.append(Character.isISOControl(c) ? ' ' : c);
            }
        }
        return out.toString();
    }

    private void handleFrontendEvent(IncomingEnvelope.FrontendEvent event,String origin){
        NavigationSession current=session;
        if(!current.accepts(event.nonce())||!helloCompleted)return;
        CommandDefinition definition=frontendEvents.find(event.event()).orElse(null);if(definition==null)return;
        PermissionDecision decision=capabilityEngine.evaluate(definition,windowId,origin,current);if(!decision.allowed())return;
        if (!frontendEventPermits.tryAcquire()) {
            LOG.log(Level.WARNING, "Frontend event limit reached for {0}; dropping {1}",
                    windowId, event.event());
            return;
        }
        try {
            commandExecutor.execute(() -> runFrontendEvent(definition, event));
        } catch (RejectedExecutionException e) {
            frontendEventPermits.release();
        }
    }

    private void runFrontendEvent(CommandDefinition definition,
            IncomingEnvelope.FrontendEvent event) {
        Thread worker = Thread.currentThread();
        java.util.concurrent.atomic.AtomicBoolean terminal =
                new java.util.concurrent.atomic.AtomicBoolean();
        Duration timeout = definition.timeout().orElse(limits.defaultCommandTimeout());
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (!terminal.get()) {
                worker.interrupt();
                LOG.log(Level.WARNING, "Frontend event timed out for {0}: {1}",
                        windowId, event.event());
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            Object payload = definition.requestType() == Void.class ? null : codec.decode(
                    event.payloadJson().orElseThrow(() -> new JDeskException(
                            ErrorCode.INVALID_REQUEST, "Event requires payload")),
                    definition.requestType());
            CompletionStage<?> stage = definition.handler().invoke(payload,
                    new EventContext(event.event()));
            if (stage == null) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                        "Frontend event handler returned no result");
            }
            stage.whenComplete((ignored, failure) -> {
                if (terminal.compareAndSet(false, true)) {
                    timeoutTask.cancel(false);
                    frontendEventPermits.release();
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Frontend event handler failed", failure);
                    }
                }
            });
        } catch (Throwable failure) {
            if (terminal.compareAndSet(false, true)) {
                timeoutTask.cancel(false);
                frontendEventPermits.release();
                LOG.log(Level.WARNING, "Rejected frontend event", failure);
            }
        } finally {
            Thread.interrupted();
        }
    }

    private final class EventContext implements InvocationContext {
        private final String name; EventContext(String name){this.name=name;}
        @Override public WindowId windowId(){return windowId;}
        @Override public String commandName(){return name;}
        @Override public String requestId(){return "event";}
        @Override public PlatformInfo platform(){return platformInfo;}
        @Override public ApplicationHandle application(){return applicationHandle;}
        @Override public EventEmitter events(){return emitter();}
        @Override public boolean isCancelled(){return closed;}
    }

    private void handleUnsupportedVersion(IncomingEnvelope.UnsupportedVersion unsupported) {
        NavigationSession current = session;
        if (!current.accepts(unsupported.nonce())) {
            return;
        }
        if (unsupported.kind().equals("hello")) {
            respond(current, envelopes.helloError(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "Unsupported protocol version " + unsupported.version()));
        } else if (unsupported.id().isPresent()) {
            respond(current, envelopes.errorResult(unsupported.id().get(),
                    ErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "Unsupported protocol version " + unsupported.version()));
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
        if (invoke.command().startsWith("jdesk.stream.")) {
            handleStreamInvoke(invoke, current);
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

    private void handleStreamInvoke(IncomingEnvelope.Invoke invoke, NavigationSession current) {
        InvocationTracker.Invocation invocation = tracker.tryBegin(invoke.id(), current);
        if (invocation == null) {
            respond(current, envelopes.errorResult(invoke.id(), ErrorCode.LIMIT_EXCEEDED,
                    "Too many in-flight requests"));
            return;
        }
        try {
            commandExecutor.execute(() -> runStreamInvocation(invoke, invocation));
        } catch (RejectedExecutionException e) {
            terminate(invocation, envelopes.errorResult(invoke.id(), ErrorCode.WINDOW_CLOSED,
                    "Window is closing"), false);
        }
    }

    private void runStreamInvocation(IncomingEnvelope.Invoke invoke,
            InvocationTracker.Invocation invocation) {
        invocation.bindWorker(Thread.currentThread());
        ScheduledFuture<?> timeoutTask = scheduler.schedule(
                () -> terminate(invocation, envelopes.errorResult(invoke.id(), ErrorCode.TIMEOUT,
                        "Stream operation timed out"), true),
                limits.defaultCommandTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            String payload = invoke.payloadJson().orElseThrow(() ->
                    new JDeskException(ErrorCode.INVALID_REQUEST, "Stream command requires payload"));
            Object value = switch (invoke.command()) {
                case "jdesk.stream.pull" -> streams.pull(
                        codec.decode(payload, StreamManager.Pull.class));
                case "jdesk.stream.cancel" -> {
                    streams.cancel(codec.decode(payload, StreamManager.Cancel.class).streamId());
                    yield java.util.Map.of("cancelled", true);
                }
                default -> throw new JDeskException(ErrorCode.UNKNOWN_COMMAND,
                        "Unknown stream command");
            };
            timeoutTask.cancel(false);
            terminate(invocation, envelopes.successResult(invoke.id(), value), false);
        } catch (Throwable failure) {
            timeoutTask.cancel(false);
            JDeskException jde = failure instanceof JDeskException j ? j :
                    new JDeskException(ErrorCode.SERIALIZATION_ERROR, "Invalid stream request");
            terminate(invocation, envelopes.errorResult(
                    invoke.id(), jde.code(), jde.publicMessage(), jde.details()), false);
        } finally {
            invocation.bindWorker(null);
            Thread.interrupted();
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
                try {
                    if (failure != null) {
                        terminate(invocation, errorFor(invoke.id(), failure, invocation), false);
                    } else {
                        completeSuccess(invocation, invoke.id(), value);
                    }
                } catch (Throwable encodingFailure) {
                    terminate(invocation,
                            errorFor(invoke.id(), encodingFailure, invocation), false);
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

    private void completeSuccess(InvocationTracker.Invocation invocation, String id, Object value) {
        if (!(value instanceof BinaryStream binary)) {
            terminate(invocation, envelopes.successResult(id, value), false);
            return;
        }
        if (!invocation.tryTerminate()) {
            return;
        }
        tracker.remove(invocation.id());
        StreamManager.Descriptor descriptor = null;
        try {
            descriptor = streams.register(binary);
            respond(invocation.session(), envelopes.successResult(id, descriptor));
        } catch (Throwable failure) {
            if (descriptor != null) {
                streams.cancel(descriptor.streamId());
            }
            respond(invocation.session(), errorFor(id, failure, invocation));
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
            return envelopes.errorResult(id, jde.code(), jde.publicMessage(), jde.details());
        }
        // Supplier + Throwable overload: passing `cause` as a trailing vararg to
        // log(Level, String, Object...) would treat it as a format parameter and drop
        // the stack trace entirely, leaving INTERNAL_ERROR failures undebuggable.
        LOG.log(Level.ERROR, () -> "Command " + id + " failed internally", cause);
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
        try {
            CompletionStage<Void> response = responder.apply(json);
            if (response != null) {
                response.exceptionally(failure -> {
                    LOG.log(Level.ERROR, "Failed to post IPC response for {0}", windowId, failure);
                    return null;
                });
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Failed to post IPC response for {0}", windowId, e);
        }
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
        StreamManager oldStreams = streams;
        session = new NavigationSession();
        helloCompleted = false;
        consoleLinesForwarded.set(0);
        eventQueue = newEventQueue(overflowPolicy);
        streams = new StreamManager();
        old.invalidate();
        oldQueue.close();
        oldStreams.close();
        // The old document is gone and cannot consume a result. Release request IDs
        // immediately so a freshly loaded client (whose counter commonly restarts at
        // one) cannot collide with an old in-flight invocation. Delaying this cleanup
        // also lets dead work consume the new document's in-flight quota.
        cancelSessionInvocations(old);
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
        streams.close();
        for (InvocationTracker.Invocation invocation : tracker.cancelAll()) {
            if (invocation.tryTerminate()) {
                tracker.remove(invocation.id());
            }
        }
        ingressExecutor.shutdownNow();
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
        public ApplicationHandle application() {
            if (applicationHandle == null) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Application handle is unavailable in this dispatcher");
            }
            return applicationHandle;
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
