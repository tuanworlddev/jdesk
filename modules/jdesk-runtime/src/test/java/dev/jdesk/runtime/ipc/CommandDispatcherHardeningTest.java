package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.api.BinaryStream;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.capability.CapabilityEngine;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class CommandDispatcherHardeningTest {
    private static final String ORIGIN = "jdesk://app";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void nativeIngressReturnsBeforeBlockingStreamReadAndPreservesMessageOrder() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        BinaryStream stream = new BinaryStream(1, "application/octet-stream", "blocked.bin",
                () -> new InputStream() {
                    @Override public int read() throws IOException {
                        readStarted.countDown();
                        try {
                            releaseRead.await();
                            return -1;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted", e);
                        }
                    }
                    @Override public void close() {
                        releaseRead.countDown();
                    }
                });
        CommandDefinition definition = new CommandDefinition("test.stream", Optional.empty(),
                Void.class, Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(stream));
        List<String> responses = new CopyOnWriteArrayList<>();
        try (CommandDispatcher dispatcher = dispatcher(CommandRegistry.of(definition),
                CommandRegistry.of(), IpcLimits.DEFAULTS, responses)) {
            String nonce = dispatcher.currentNonce();
            dispatcher.postMessage(hello(nonce), ORIGIN);
            dispatcher.postMessage(invoke("open", "test.stream", null, nonce), ORIGIN);
            JsonNode descriptor = awaitResult(responses, "open");
            String streamId = descriptor.at("/value/streamId").textValue();

            long started = System.nanoTime();
            dispatcher.postMessage(invoke("pull", "jdesk.stream.pull",
                    "{\"streamId\":\"" + streamId + "\",\"maxBytes\":1}", nonce), ORIGIN);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMillis).isLessThan(100);
            assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseRead.countDown();
            assertThat(awaitResult(responses, "pull").path("ok").booleanValue()).isTrue();
        }
    }

    @Test
    void timedOutInvocationNeverOpensItsLateBinaryStream() throws Exception {
        CompletableFuture<Object> late = new CompletableFuture<>();
        AtomicInteger opens = new AtomicInteger();
        BinaryStream stream = new BinaryStream(0, "application/octet-stream", "late.bin", () -> {
            opens.incrementAndGet();
            return InputStream.nullInputStream();
        });
        CommandDefinition definition = new CommandDefinition("test.lateStream", Optional.empty(),
                Void.class, Optional.of(Duration.ofMillis(20)), (request, context) -> late);
        List<String> responses = new CopyOnWriteArrayList<>();
        try (CommandDispatcher dispatcher = dispatcher(CommandRegistry.of(definition),
                CommandRegistry.of(), IpcLimits.DEFAULTS, responses)) {
            String nonce = handshake(dispatcher, responses);
            dispatcher.onMessage(invoke("late", "test.lateStream", null, nonce), ORIGIN);
            JsonNode timeout = awaitResult(responses, "late");
            assertThat(timeout.at("/error/code").textValue()).isEqualTo(ErrorCode.TIMEOUT.name());

            late.complete(stream);
            Thread.sleep(100);
            assertThat(opens).hasValue(0);
        }
    }

    @Test
    void frontendEventsAreBoundedByThePerWindowInFlightLimit() throws Exception {
        AtomicInteger started = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        CommandDefinition event = new CommandDefinition("page.changed", Optional.empty(),
                Void.class, Optional.empty(), (request, context) -> {
                    started.incrementAndGet();
                    twoStarted.countDown();
                    return new CompletableFuture<>();
                });
        IpcLimits limits = new IpcLimits(1_048_576, 2, Duration.ofSeconds(30), 8, 128);
        List<String> responses = new CopyOnWriteArrayList<>();
        try (CommandDispatcher dispatcher = dispatcher(CommandRegistry.of(),
                CommandRegistry.of(event), limits, responses)) {
            String nonce = handshake(dispatcher, responses);
            for (int i = 0; i < 20; i++) {
                dispatcher.onMessage(frontendEvent("page.changed", nonce), ORIGIN);
            }
            assertThat(twoStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(started).hasValue(2);
        }
    }

    @Test
    void timedOutFrontendEventKeepsItsPermitUntilTheStageActuallyCompletes() throws Exception {
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<Object> stuck = new CompletableFuture<>();
        CommandDefinition event = new CommandDefinition("page.stuck", Optional.empty(),
                Void.class, Optional.of(Duration.ofMillis(20)), (request, context) -> {
                    started.incrementAndGet();
                    return stuck;
                });
        IpcLimits limits = new IpcLimits(1_048_576, 1, Duration.ofSeconds(30), 8, 128);
        List<String> responses = new CopyOnWriteArrayList<>();
        try (CommandDispatcher dispatcher = dispatcher(CommandRegistry.of(),
                CommandRegistry.of(event), limits, responses)) {
            String nonce = handshake(dispatcher, responses);
            dispatcher.onMessage(frontendEvent("page.stuck", nonce), ORIGIN);
            Thread.sleep(100);
            dispatcher.onMessage(frontendEvent("page.stuck", nonce), ORIGIN);
            Thread.sleep(100);
            assertThat(started).hasValue(1);
            stuck.complete(null);
        }
    }

    @Test
    void streamRegistryRejectsResourceExhaustion() {
        try (StreamManager manager = new StreamManager()) {
            for (int i = 0; i < StreamManager.MAX_ACTIVE_STREAMS; i++) {
                manager.register(new BinaryStream(0, "application/octet-stream", "x" + i,
                        InputStream::nullInputStream));
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.register(
                    new BinaryStream(0, "application/octet-stream", "overflow",
                            InputStream::nullInputStream)))
                    .isInstanceOfSatisfying(dev.jdesk.api.JDeskException.class,
                            error -> assertThat(error.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        }
    }

    private static CommandDispatcher dispatcher(CommandRegistry commands,
            CommandRegistry frontendEvents, IpcLimits limits, List<String> responses) {
        return new CommandDispatcher(new WindowId("main"), commands, frontendEvents,
                new CapabilityEngine(CapabilitySet.empty(), Set.of(ORIGIN)),
                new JacksonJsonCodec(), limits, new PlatformInfo("test", "1", "test"),
                EventOverflowPolicy.REJECT, Duration.ZERO, json -> {
                    responses.add(json);
                    return CompletableFuture.completedFuture(null);
                }, null);
    }

    private static String handshake(CommandDispatcher dispatcher, List<String> responses)
            throws Exception {
        String nonce = dispatcher.currentNonce();
        dispatcher.onMessage(hello(nonce), ORIGIN);
        awaitKind(responses, "helloAck");
        return nonce;
    }

    private static JsonNode awaitResult(List<String> responses, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (String response : responses) {
                JsonNode node = JSON.readTree(response);
                if ("result".equals(node.path("kind").textValue())
                        && id.equals(node.path("id").textValue())) {
                    return node;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out waiting for result " + id + ": " + responses);
    }

    private static void awaitKind(List<String> responses, String kind) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (String response : responses) {
                if (kind.equals(JSON.readTree(response).path("kind").textValue())) {
                    return;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out waiting for " + kind);
    }

    private static String hello(String nonce) {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", 1).put("kind", "hello").put("client", "test")
                .put("clientVersion", "1").put("nonce", nonce);
        return node.toString();
    }

    private static String invoke(String id, String command, String payload, String nonce)
            throws Exception {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", 1).put("kind", "invoke").put("id", id).put("command", command);
        if (payload != null) {
            node.set("payload", JSON.readTree(payload));
        }
        node.put("nonce", nonce);
        return node.toString();
    }

    private static String frontendEvent(String event, String nonce) {
        return JSON.createObjectNode().put("v", 1).put("kind", "frontendEvent")
                .put("event", event).putNull("payload").put("nonce", nonce).toString();
    }
}
