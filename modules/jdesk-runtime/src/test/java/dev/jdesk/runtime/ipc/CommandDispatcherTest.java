package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandHandler;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.capability.CapabilityEngine;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import dev.jdesk.runtime.json.JsonCodec;
import dev.jdesk.runtime.json.JsonLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit-level protocol flow through the per-window dispatcher (spec sections 10 and 12.1):
 * handshake, correlation, capability gating before deserialization, limits, cancellation,
 * timeout, navigation reset, exception redaction, close, and event emission.
 */
@Timeout(30)
class CommandDispatcherTest {

    private static final String ORIGIN = "jdesk://app";
    private static final Duration AWAIT = Duration.ofSeconds(5);

    public record EchoRequest(String text) {
    }

    public record EchoResponse(String echoed) {
    }

    /** Thread-safe responder with monitor-based waiting; no polling sleeps. */
    private static final class CollectingResponder implements Consumer<String> {
        private final List<String> messages = new ArrayList<>();
        private final Object monitor = new Object();

        @Override
        public void accept(String json) {
            synchronized (monitor) {
                messages.add(json);
                monitor.notifyAll();
            }
        }

        List<String> snapshot() {
            synchronized (monitor) {
                return new ArrayList<>(messages);
            }
        }

        int count() {
            synchronized (monitor) {
                return messages.size();
            }
        }

        List<String> awaitCount(int expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (monitor) {
                while (messages.size() < expected) {
                    long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
                    if (remainingMillis <= 0) {
                        throw new AssertionError("Timed out waiting for " + expected
                                + " responses; got " + messages.size() + ": " + messages);
                    }
                    monitor.wait(remainingMillis);
                }
                return new ArrayList<>(messages);
            }
        }
    }

    private final ObjectMapper plain = new ObjectMapper();
    private final List<CommandDispatcher> dispatchers = new ArrayList<>();

    private CollectingResponder responder;
    private CommandDispatcher dispatcher;
    private Semaphore sleeperStarted;
    private AtomicBoolean secretHandlerRan;

    @BeforeEach
    void setUp() {
        responder = new CollectingResponder();
        sleeperStarted = new Semaphore(0);
        secretHandlerRan = new AtomicBoolean();
        dispatcher = newDispatcher(IpcLimits.DEFAULTS, Optional.empty(), responder);
    }

    @AfterEach
    void tearDown() {
        for (CommandDispatcher d : dispatchers) {
            d.close();
        }
        dispatchers.clear();
    }

    // ---- harness ----

    private CommandDispatcher newDispatcher(
            IpcLimits limits, Optional<Duration> sleeperTimeout, CollectingResponder target) {
        CommandHandler echo = (request, context) ->
                CompletableFuture.completedFuture(new EchoResponse(((EchoRequest) request).text()));
        CommandHandler sleeper = (request, context) -> {
            sleeperStarted.release();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
            return CompletableFuture.completedFuture(new EchoResponse("done"));
        };
        CommandHandler failing = (request, context) -> {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "custom public failure");
        };
        CommandHandler failingWithData = (request, context) -> {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "upstream rejected",
                    java.util.Map.of("httpStatus", 429, "retryAfterSeconds", 30), null);
        };
        CommandHandler npe = (request, context) -> {
            throw new NullPointerException("secret internal detail");
        };
        CommandHandler secret = (request, context) -> {
            secretHandlerRan.set(true);
            return CompletableFuture.completedFuture(new EchoResponse("never"));
        };
        CommandRegistry registry = CommandRegistry.of(
                new CommandDefinition("test.echo", Optional.of("test:use"),
                        EchoRequest.class, Optional.empty(), echo),
                new CommandDefinition("test.sleep", Optional.of("test:use"),
                        Void.class, sleeperTimeout, sleeper),
                new CommandDefinition("test.fail", Optional.of("test:use"),
                        Void.class, Optional.empty(), failing),
                new CommandDefinition("test.failData", Optional.of("test:use"),
                        Void.class, Optional.empty(), failingWithData),
                new CommandDefinition("test.npe", Optional.of("test:use"),
                        Void.class, Optional.empty(), npe),
                new CommandDefinition("other.secret", Optional.of("other:cap"),
                        EchoRequest.class, Optional.empty(), secret));
        CapabilityEngine engine = new CapabilityEngine(
                CapabilitySet.of(Set.of(new CapabilityGrant("test:use", Set.of("main")))),
                Set.of(ORIGIN));
        CommandDispatcher created = new CommandDispatcher(
                new WindowId("main"),
                registry,
                engine,
                new JacksonJsonCodec(),
                limits,
                new PlatformInfo("test-os", "1.0", "arm64"),
                EventOverflowPolicy.REJECT,
                Duration.ZERO,
                target);
        dispatchers.add(created);
        return created;
    }

    private String hello(String nonce) {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "hello");
        node.put("client", "jdesk-client");
        node.put("clientVersion", "0.1.0");
        node.put("nonce", nonce);
        return node.toString();
    }

    private String invoke(String id, String command, String payloadJson, String nonce) {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "invoke");
        node.put("id", id);
        node.put("command", command);
        if (payloadJson != null) {
            try {
                node.set("payload", plain.readTree(payloadJson));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        node.put("nonce", nonce);
        return node.toString();
    }

    private String cancel(String id, String nonce) {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "cancel");
        node.put("id", id);
        node.put("nonce", nonce);
        return node.toString();
    }

    private String console(String level, String message, String nonce) {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "console");
        node.put("level", level);
        node.put("message", message);
        node.put("nonce", nonce);
        return node.toString();
    }

    @Test
    void consoleEnvelopeForwardsSanitizedMessageToListener() {
        List<String> lines = new ArrayList<>();
        dispatcher.onConsole((level, message) -> lines.add(level + ":" + message));
        dispatcher.onMessage(console("error", "boom\u0007bell", dispatcher.currentNonce()), ORIGIN);
        assertThat(lines).containsExactly("error:boom bell");
    }

    @Test
    void consoleEnvelopeWithStaleNonceIsDropped() {
        List<String> lines = new ArrayList<>();
        dispatcher.onConsole((level, message) -> lines.add(message));
        dispatcher.onMessage(console("log", "spoofed", "not-the-nonce"), ORIGIN);
        assertThat(lines).isEmpty();
    }

    @Test
    void consoleEnvelopeWithoutListenerIsDroppedSilently() {
        dispatcher.onMessage(console("log", "nobody listening", dispatcher.currentNonce()), ORIGIN);
        assertThat(responder.count()).isZero();
    }

    /** Completes the handshake on {@code target} and returns the session nonce. */
    private String handshake(CommandDispatcher target, CollectingResponder replies)
            throws InterruptedException {
        int before = replies.count();
        String nonce = target.currentNonce();
        target.onMessage(hello(nonce), ORIGIN);
        replies.awaitCount(before + 1, AWAIT);
        return nonce;
    }

    private JsonNode parse(String json) {
        try {
            return plain.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Responder received invalid JSON: " + json, e);
        }
    }

    private List<JsonNode> resultsFor(String id, CollectingResponder replies) {
        List<JsonNode> results = new ArrayList<>();
        for (String message : replies.snapshot()) {
            JsonNode node = parse(message);
            if ("result".equals(node.path("kind").textValue())
                    && id.equals(node.path("id").textValue())) {
                results.add(node);
            }
        }
        return results;
    }

    private JsonNode awaitResultFor(String id, CollectingResponder replies) throws InterruptedException {
        awaitMessage(replies, node -> "result".equals(node.path("kind").textValue())
                && id.equals(node.path("id").textValue()));
        return resultsFor(id, replies).getFirst();
    }

    private void awaitMessage(CollectingResponder replies, Predicate<JsonNode> predicate)
            throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        int seen = 0;
        while (true) {
            List<String> snapshot = replies.snapshot();
            for (; seen < snapshot.size(); seen++) {
                if (predicate.test(parse(snapshot.get(seen)))) {
                    return;
                }
            }
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                throw new AssertionError("Timed out waiting for matching response in: " + snapshot);
            }
            replies.awaitCount(seen + 1, Duration.ofMillis(remainingMillis));
        }
    }

    private static void assertError(JsonNode result, ErrorCode code) {
        assertThat(result.path("ok").booleanValue()).isFalse();
        assertThat(result.path("error").path("code").textValue()).isEqualTo(code.name());
    }

    /** Short quiescence window used only for asserting the *absence* of a response. */
    private static void settle() throws InterruptedException {
        Thread.sleep(200);
    }

    // ---- handshake ----

    @Test
    void helloReturnsAckWithCurrentNonce() throws Exception {
        String nonce = dispatcher.currentNonce();
        dispatcher.onMessage(hello(nonce), ORIGIN);
        List<String> messages = responder.awaitCount(1, AWAIT);
        JsonNode ack = parse(messages.getFirst());
        assertThat(ack.path("kind").textValue()).isEqualTo("helloAck");
        assertThat(ack.path("v").intValue()).isEqualTo(1);
        assertThat(ack.path("ok").booleanValue()).isTrue();
        assertThat(ack.path("nonce").textValue()).isEqualTo(nonce);
    }

    @Test
    void invokeBeforeHelloIsInvalidRequest() throws Exception {
        dispatcher.onMessage(
                invoke("pre-1", "test.echo", "{\"text\":\"hi\"}", dispatcher.currentNonce()), ORIGIN);
        JsonNode result = awaitResultFor("pre-1", responder);
        assertError(result, ErrorCode.INVALID_REQUEST);
        assertThat(result.path("error").path("message").textValue())
                .isEqualTo("Handshake required before commands");
    }

    @Test
    void helloWithWrongNonceIsIgnoredEntirely() throws Exception {
        dispatcher.onMessage(hello("00000000000000000000000000000000"), ORIGIN);
        settle();
        assertThat(responder.count()).isZero();
        // And the handshake is still not completed.
        dispatcher.onMessage(
                invoke("after-bad-hello", "test.echo", "{\"text\":\"x\"}", dispatcher.currentNonce()),
                ORIGIN);
        assertError(awaitResultFor("after-bad-hello", responder), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void unsupportedHelloVersionReturnsExplicitProtocolError() throws Exception {
        ObjectNode unsupported = (ObjectNode) plain.readTree(hello(dispatcher.currentNonce()));
        unsupported.put("v", 2);
        dispatcher.onMessage(unsupported.toString(), ORIGIN);

        JsonNode ack = parse(responder.awaitCount(1, AWAIT).getFirst());
        assertThat(ack.path("kind").textValue()).isEqualTo("helloAck");
        assertThat(ack.path("ok").booleanValue()).isFalse();
        assertThat(ack.at("/error/code").textValue())
                .isEqualTo(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED.name());
    }

    // ---- echo and correlation ----

    @Test
    void typedEchoReturnsSuccessResult() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("echo-1", "test.echo", "{\"text\":\"Hello Tuan\"}", nonce), ORIGIN);
        JsonNode result = awaitResultFor("echo-1", responder);
        assertThat(result.path("v").intValue()).isEqualTo(1);
        assertThat(result.path("ok").booleanValue()).isTrue();
        assertThat(result.path("id").textValue()).isEqualTo("echo-1");
        assertThat(result.path("value").path("echoed").textValue()).isEqualTo("Hello Tuan");
        assertThat(result.has("error")).isFalse();
    }

    @Test
    @Timeout(60)
    void hundredConcurrentInvokesAllCorrelateById() throws Exception {
        String nonce = handshake(dispatcher, responder);
        final int count = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int n = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    dispatcher.onMessage(
                            invoke("conc-" + n, "test.echo", "{\"text\":\"value-" + n + "\"}", nonce),
                            ORIGIN);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    sent.countDown();
                }
            });
        }
        start.countDown();
        assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
        responder.awaitCount(count + 1, Duration.ofSeconds(15)); // + helloAck

        for (int i = 0; i < count; i++) {
            List<JsonNode> results = resultsFor("conc-" + i, responder);
            assertThat(results).as("results for conc-%d", i).hasSize(1);
            JsonNode result = results.getFirst();
            assertThat(result.path("ok").booleanValue()).isTrue();
            assertThat(result.path("value").path("echoed").textValue()).isEqualTo("value-" + i);
        }
        assertThat(dispatcher.pendingInvocations()).isZero();
    }

    // ---- rejection paths ----

    @Test
    void unknownCommandIsRejected() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("u-1", "no.suchCommand", null, nonce), ORIGIN);
        assertError(awaitResultFor("u-1", responder), ErrorCode.UNKNOWN_COMMAND);
    }

    @Test
    void missingCapabilityIsDeniedBeforeHandlerAndBeforePayloadDecoding() throws Exception {
        String nonce = handshake(dispatcher, responder);
        // This payload cannot decode into EchoRequest (object where a string belongs).
        // If deserialization ran before the capability check, we would see
        // SERIALIZATION_ERROR instead of CAPABILITY_DENIED.
        dispatcher.onMessage(
                invoke("cap-1", "other.secret", "{\"text\":{\"evil\":true}}", nonce), ORIGIN);
        JsonNode result = awaitResultFor("cap-1", responder);
        assertError(result, ErrorCode.CAPABILITY_DENIED);
        assertThat(result.path("error").path("message").textValue())
                .isEqualTo("Command is not allowed for this window");
        settle();
        assertThat(secretHandlerRan).as("handler must never run when capability is denied").isFalse();
    }

    @Test
    void oversizeMessageIsSilentlyDropped() throws Exception {
        String nonce = handshake(dispatcher, responder);
        String huge = invoke("big-1", "test.echo",
                "{\"text\":\"" + "x".repeat(1_100_000) + "\"}", nonce);
        dispatcher.onMessage(huge, ORIGIN);
        settle();
        // No trustworthy correlation id in an unparsed envelope: no response at all.
        assertThat(responder.count()).isEqualTo(1); // just the helloAck
    }

    @Test
    void duplicateRequestIdIsRejected() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("dup-1", "test.sleep", null, nonce), ORIGIN);
        assertThat(sleeperStarted.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.onMessage(invoke("dup-1", "test.echo", "{\"text\":\"again\"}", nonce), ORIGIN);
        JsonNode result = awaitResultFor("dup-1", responder);
        assertError(result, ErrorCode.INVALID_REQUEST);
        assertThat(result.path("error").path("message").textValue()).isEqualTo("Duplicate request id");
    }

    @Test
    void inFlightLimitProducesLimitExceeded() throws Exception {
        CollectingResponder replies = new CollectingResponder();
        CommandDispatcher limited = newDispatcher(
                new IpcLimits(1_048_576, 2, Duration.ofSeconds(30), 256, 128),
                Optional.empty(), replies);
        String nonce = handshake(limited, replies);
        limited.onMessage(invoke("if-1", "test.sleep", null, nonce), ORIGIN);
        limited.onMessage(invoke("if-2", "test.sleep", null, nonce), ORIGIN);
        assertThat(sleeperStarted.tryAcquire(2, 5, TimeUnit.SECONDS)).isTrue();
        assertThat(limited.pendingInvocations()).isEqualTo(2);

        limited.onMessage(invoke("if-3", "test.sleep", null, nonce), ORIGIN);
        JsonNode result = awaitResultFor("if-3", replies);
        assertError(result, ErrorCode.LIMIT_EXCEEDED);
    }

    // ---- cancellation / timeout ----

    @Test
    void cancelProducesExactlyOneCancelledResult() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("c-1", "test.sleep", null, nonce), ORIGIN);
        assertThat(sleeperStarted.tryAcquire(5, TimeUnit.SECONDS)).isTrue();

        dispatcher.onMessage(cancel("c-1", nonce), ORIGIN);
        JsonNode result = awaitResultFor("c-1", responder);
        assertError(result, ErrorCode.CANCELLED);

        settle(); // give the interrupted worker time to attempt a late second result
        assertThat(resultsFor("c-1", responder)).hasSize(1);
        assertThat(dispatcher.pendingInvocations()).isZero();
    }

    @Test
    void commandTimeoutProducesExactlyOneTimeoutResult() throws Exception {
        CollectingResponder replies = new CollectingResponder();
        CommandDispatcher timed = newDispatcher(
                IpcLimits.DEFAULTS, Optional.of(Duration.ofMillis(100)), replies);
        String nonce = handshake(timed, replies);
        timed.onMessage(invoke("t-1", "test.sleep", null, nonce), ORIGIN);
        JsonNode result = awaitResultFor("t-1", replies);
        assertError(result, ErrorCode.TIMEOUT);

        settle();
        assertThat(resultsFor("t-1", replies)).hasSize(1);
        assertThat(timed.pendingInvocations()).isZero();
    }

    @Test
    void responseEncodingFailureTerminatesInsteadOfTimingOut() throws Exception {
        JsonCodec failingCodec = new JsonCodec() {
            @Override
            public String encode(Object value) {
                throw new JDeskException(ErrorCode.SERIALIZATION_ERROR,
                        "Result could not be serialized");
            }

            @Override
            public <T> T decode(String json, Class<T> type) {
                return null;
            }

            @Override
            public JsonLimits limits() {
                return JsonLimits.DEFAULTS;
            }
        };
        CollectingResponder replies = new CollectingResponder();
        CommandDefinition command = new CommandDefinition("test.encode", Optional.empty(),
                Void.class, Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(new Object()));
        CommandDispatcher encoding = new CommandDispatcher(
                new WindowId("main"), CommandRegistry.of(command),
                new CapabilityEngine(CapabilitySet.empty(), Set.of(ORIGIN)), failingCodec,
                IpcLimits.DEFAULTS, new PlatformInfo("test", "1", "arm64"),
                EventOverflowPolicy.REJECT, Duration.ZERO, replies);
        dispatchers.add(encoding);

        String nonce = handshake(encoding, replies);
        encoding.onMessage(invoke("encode-1", "test.encode", null, nonce), ORIGIN);

        assertError(awaitResultFor("encode-1", replies), ErrorCode.SERIALIZATION_ERROR);
        assertThat(encoding.pendingInvocations()).isZero();
    }

    // ---- nonce / navigation ----

    @Test
    void invokeWithStaleNonceGetsStaleNonceResult() throws Exception {
        handshake(dispatcher, responder);
        dispatcher.onMessage(
                invoke("s-1", "test.echo", "{\"text\":\"x\"}", "ffffffffffffffffffffffffffffffff"),
                ORIGIN);
        assertError(awaitResultFor("s-1", responder), ErrorCode.STALE_NONCE);
    }

    @Test
    void navigationInvalidatesOldNonceSuppressesLateResultsAndResetsHandshake() throws Exception {
        String oldNonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("nav-sleep", "test.sleep", null, oldNonce), ORIGIN);
        assertThat(sleeperStarted.tryAcquire(5, TimeUnit.SECONDS)).isTrue();

        dispatcher.onNavigationCommitted();
        String newNonce = dispatcher.currentNonce();
        assertThat(newNonce).isNotEqualTo(oldNonce);

        // Old nonce is now stale.
        dispatcher.onMessage(invoke("nav-old", "test.echo", "{\"text\":\"x\"}", oldNonce), ORIGIN);
        assertError(awaitResultFor("nav-old", responder), ErrorCode.STALE_NONCE);

        // Handshake is reset: even the fresh nonce needs a new hello first.
        dispatcher.onMessage(invoke("nav-new", "test.echo", "{\"text\":\"x\"}", newNonce), ORIGIN);
        JsonNode result = awaitResultFor("nav-new", responder);
        assertError(result, ErrorCode.INVALID_REQUEST);
        assertThat(result.path("error").path("message").textValue())
                .isEqualTo("Handshake required before commands");

        // The pre-navigation sleeper must never produce a late result in the new document.
        settle();
        assertThat(resultsFor("nav-sleep", responder)).isEmpty();

        // A fresh handshake works and commands flow again.
        handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("nav-after", "test.echo", "{\"text\":\"back\"}", newNonce), ORIGIN);
        JsonNode revived = awaitResultFor("nav-after", responder);
        assertThat(revived.path("ok").booleanValue()).isTrue();
        assertThat(revived.path("value").path("echoed").textValue()).isEqualTo("back");
    }

    // ---- exception mapping ----

    @Test
    void jdeskExceptionMapsToItsCodeAndPublicMessage() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("f-1", "test.fail", null, nonce), ORIGIN);
        JsonNode result = awaitResultFor("f-1", responder);
        assertError(result, ErrorCode.INVALID_REQUEST);
        assertThat(result.path("error").path("message").textValue()).isEqualTo("custom public failure");
    }

    @Test
    void jdeskExceptionDetailsArriveAsStructuredErrorData() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("fd-1", "test.failData", null, nonce), ORIGIN);
        JsonNode result = awaitResultFor("fd-1", responder);
        assertError(result, ErrorCode.INVALID_REQUEST);
        assertThat(result.path("error").path("data").path("httpStatus").intValue()).isEqualTo(429);
        assertThat(result.path("error").path("data").path("retryAfterSeconds").intValue()).isEqualTo(30);
    }

    @Test
    void runtimeExceptionMapsToInternalErrorWithoutInternalDetails() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("n-1", "test.npe", null, nonce), ORIGIN);
        JsonNode result = awaitResultFor("n-1", responder);
        assertError(result, ErrorCode.INTERNAL_ERROR);
        assertThat(result.path("error").path("message").textValue()).isEqualTo("Command failed");
        assertThat(result.toString())
                .doesNotContain("secret internal detail")
                .doesNotContain("NullPointerException");
    }

    @Test
    void internalErrorLogsCauseStackTraceForDebugging() throws Exception {
        // BUG-001 regression: errorFor() must hand the cause to the Throwable logging
        // overload. The old log(Level, String, Object...) form treated the Throwable as a
        // message parameter, so the stack trace never reached the JVM log and command
        // failures were effectively undebuggable. The redacted public response is checked
        // above; here we assert the *internal* log keeps the full cause.
        java.util.logging.Logger jul =
                java.util.logging.Logger.getLogger("dev.jdesk.runtime.ipc.CommandDispatcher");
        java.util.concurrent.atomic.AtomicReference<Throwable> logged =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getThrown() != null) {
                    logged.compareAndSet(null, record.getThrown());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        java.util.logging.Level previousLevel = jul.getLevel();
        jul.addHandler(capture);
        jul.setLevel(java.util.logging.Level.ALL);
        try {
            String nonce = handshake(dispatcher, responder);
            dispatcher.onMessage(invoke("bug1-1", "test.npe", null, nonce), ORIGIN);
            assertError(awaitResultFor("bug1-1", responder), ErrorCode.INTERNAL_ERROR);
            assertThat(logged.get())
                    .as("errorFor must log the cause with its stack trace")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("secret internal detail");
            assertThat(logged.get().getStackTrace()).isNotEmpty();
        } finally {
            jul.removeHandler(capture);
            jul.setLevel(previousLevel);
        }
    }

    // ---- close ----

    @Test
    void closeTerminatesPendingWorkWithoutResponseLeakAndIgnoresFurtherMessages() throws Exception {
        String nonce = handshake(dispatcher, responder);
        dispatcher.onMessage(invoke("z-1", "test.sleep", null, nonce), ORIGIN);
        assertThat(sleeperStarted.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        int before = responder.count();

        dispatcher.close();

        assertThat(dispatcher.pendingInvocations()).isZero();
        settle();
        assertThat(resultsFor("z-1", responder)).as("no terminal result may leak after close").isEmpty();
        assertThat(responder.count()).isEqualTo(before);

        dispatcher.onMessage(invoke("z-2", "test.echo", "{\"text\":\"x\"}", nonce), ORIGIN);
        settle();
        assertThat(responder.count()).isEqualTo(before);
    }

    @Test
    void closeIsIdempotent() {
        dispatcher.close();
        dispatcher.close();
        assertThat(dispatcher.pendingInvocations()).isZero();
    }

    // ---- events ----

    @Test
    void emitterProducesCanonicalEventEnvelope() throws Exception {
        dispatcher.emitter().emit("app.tick", new EchoResponse("tick"));
        List<String> messages = responder.awaitCount(1, AWAIT);
        JsonNode event = parse(messages.getFirst());
        List<String> names = new ArrayList<>();
        event.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("v", "kind", "event", "payload");
        assertThat(event.path("v").intValue()).isEqualTo(1);
        assertThat(event.path("kind").textValue()).isEqualTo("event");
        assertThat(event.path("event").textValue()).isEqualTo("app.tick");
        assertThat(event.path("payload").path("echoed").textValue()).isEqualTo("tick");
    }

    @Test
    void emitterAfterCloseThrowsWindowClosed() {
        dispatcher.close();
        assertThatThrownBy(() -> dispatcher.emitter().emit("app.tick", null))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.WINDOW_CLOSED));
    }

    @Test
    void emitterRejectsInvalidEventNames() {
        assertThatThrownBy(() -> dispatcher.emitter().emit("", null))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> dispatcher.emitter().emit("e".repeat(129), null))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> dispatcher.emitter().emit(null, null))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(responder.count()).isZero();
    }
}
