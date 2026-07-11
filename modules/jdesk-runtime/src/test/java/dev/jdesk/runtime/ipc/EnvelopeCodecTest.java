package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Strict envelope grammar (spec section 10): closed field sets per kind, deterministic
 * ProtocolException codes, and canonical outgoing envelope shapes.
 */
class EnvelopeCodecTest {

    public record Payload(String message) {
    }

    private final ObjectMapper plain = new ObjectMapper();
    private final EnvelopeCodec codec = new EnvelopeCodec(new JacksonJsonCodec(), IpcLimits.DEFAULTS);

    // ---- helpers ----

    private ObjectNode validHello() {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "hello");
        node.put("client", "@jdesk/client");
        node.put("clientVersion", "0.1.0");
        node.put("nonce", "abc123");
        return node;
    }

    private ObjectNode validInvoke() {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "invoke");
        node.put("id", "01JREQ");
        node.put("command", "greeting.greet");
        node.set("payload", plain.createObjectNode().put("name", "Tuan"));
        node.put("nonce", "abc123");
        return node;
    }

    private ObjectNode validCancel() {
        ObjectNode node = plain.createObjectNode();
        node.put("v", 1);
        node.put("kind", "cancel");
        node.put("id", "01JREQ");
        node.put("nonce", "abc123");
        return node;
    }

    private static void assertProtocolError(Runnable parse, ErrorCode expected) {
        assertThatThrownBy(parse::run)
                .isInstanceOfSatisfying(ProtocolException.class,
                        e -> assertThat(e.code()).isEqualTo(expected));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- valid envelopes ----

    @Test
    void parsesValidHello() {
        IncomingEnvelope envelope = codec.parse(validHello().toString());
        assertThat(envelope).isInstanceOfSatisfying(IncomingEnvelope.Hello.class, hello -> {
            assertThat(hello.version()).isEqualTo(1);
            assertThat(hello.client()).isEqualTo("@jdesk/client");
            assertThat(hello.clientVersion()).isEqualTo("0.1.0");
            assertThat(hello.nonce()).isEqualTo("abc123");
        });
    }

    @Test
    void parsesValidInvokeWithPayload() {
        IncomingEnvelope envelope = codec.parse(validInvoke().toString());
        assertThat(envelope).isInstanceOfSatisfying(IncomingEnvelope.Invoke.class, invoke -> {
            assertThat(invoke.version()).isEqualTo(1);
            assertThat(invoke.id()).isEqualTo("01JREQ");
            assertThat(invoke.command()).isEqualTo("greeting.greet");
            assertThat(invoke.nonce()).isEqualTo("abc123");
            assertThat(invoke.payloadJson()).hasValue("{\"name\":\"Tuan\"}");
        });
    }

    @Test
    void parsesValidCancel() {
        IncomingEnvelope envelope = codec.parse(validCancel().toString());
        assertThat(envelope).isInstanceOfSatisfying(IncomingEnvelope.Cancel.class, cancel -> {
            assertThat(cancel.version()).isEqualTo(1);
            assertThat(cancel.id()).isEqualTo("01JREQ");
            assertThat(cancel.nonce()).isEqualTo("abc123");
        });
    }

    @Test
    void invokePayloadAbsentAndExplicitNullBothMapToEmpty() {
        ObjectNode absent = validInvoke();
        absent.remove("payload");
        IncomingEnvelope.Invoke noPayload = (IncomingEnvelope.Invoke) codec.parse(absent.toString());
        assertThat(noPayload.payloadJson()).isEmpty();

        ObjectNode explicitNull = validInvoke();
        explicitNull.putNull("payload");
        IncomingEnvelope.Invoke nullPayload = (IncomingEnvelope.Invoke) codec.parse(explicitNull.toString());
        assertThat(nullPayload.payloadJson()).isEmpty();

        IncomingEnvelope.Invoke withPayload = (IncomingEnvelope.Invoke) codec.parse(validInvoke().toString());
        assertThat(withPayload.payloadJson()).isPresent();
    }

    // ---- missing required fields ----

    @ParameterizedTest
    @ValueSource(strings = {"v", "kind", "client", "clientVersion", "nonce"})
    void helloMissingRequiredFieldIsInvalid(String field) {
        ObjectNode node = validHello();
        node.remove(field);
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v", "kind", "id", "command", "nonce"})
    void invokeMissingRequiredFieldIsInvalid(String field) {
        ObjectNode node = validInvoke();
        node.remove(field);
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v", "kind", "id", "nonce"})
    void cancelMissingRequiredFieldIsInvalid(String field) {
        ObjectNode node = validCancel();
        node.remove(field);
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
    }

    // ---- unknown fields / kinds / versions ----

    @Test
    void unknownTopLevelFieldIsRejectedForEveryKind() {
        for (ObjectNode node : List.of(validHello(), validInvoke(), validCancel())) {
            node.put("sneaky", "extra");
            assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void unknownKindIsRejected() {
        ObjectNode node = validHello();
        node.put("kind", "subscribe");
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void unsupportedProtocolVersionIsPreservedForAProtocolErrorResponse() {
        for (int version : new int[] {0, 2, -1, 99}) {
            ObjectNode node = validInvoke();
            node.put("v", version);
            assertThat(codec.parse(node.toString()))
                    .isInstanceOfSatisfying(IncomingEnvelope.UnsupportedVersion.class,
                            unsupported -> {
                                assertThat(unsupported.version()).isEqualTo(version);
                                assertThat(unsupported.kind()).isEqualTo("invoke");
                                assertThat(unsupported.id()).contains("01JREQ");
                            });
        }
    }

    // ---- wrong types ----

    @Test
    void wrongTypeForVersionIsInvalid() {
        ObjectNode node = validHello();
        node.put("v", "1");
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);

        ObjectNode floatVersion = validHello();
        floatVersion.put("v", 1.5);
        assertProtocolError(() -> codec.parse(floatVersion.toString()), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void wrongTypeForStringFieldsIsInvalid() {
        ObjectNode kindAsNumber = validHello();
        kindAsNumber.put("kind", 7);
        assertProtocolError(() -> codec.parse(kindAsNumber.toString()), ErrorCode.INVALID_REQUEST);

        ObjectNode idAsNumber = validInvoke();
        idAsNumber.put("id", 42);
        assertProtocolError(() -> codec.parse(idAsNumber.toString()), ErrorCode.INVALID_REQUEST);

        ObjectNode commandAsObject = validInvoke();
        commandAsObject.set("command", plain.createObjectNode());
        assertProtocolError(() -> codec.parse(commandAsObject.toString()), ErrorCode.INVALID_REQUEST);

        ObjectNode nonceAsArray = validCancel();
        nonceAsArray.set("nonce", plain.createArrayNode());
        assertProtocolError(() -> codec.parse(nonceAsArray.toString()), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void nonObjectDocumentsAreInvalid() {
        for (String doc : new String[] {"[]", "\"hello\"", "42", "true", "null"}) {
            assertProtocolError(() -> codec.parse(doc), ErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void malformedJsonIsInvalid() {
        for (String doc : new String[] {"{", "{\"v\":1,", "not json", "{\"v\":1}}"}) {
            assertProtocolError(() -> codec.parse(doc), ErrorCode.INVALID_REQUEST);
        }
    }

    // ---- size and length bounds ----

    @Test
    void emptyAndNullMessagesAreInvalid() {
        assertProtocolError(() -> codec.parse(""), ErrorCode.INVALID_REQUEST);
        assertProtocolError(() -> codec.parse(null), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void oversizeMessageIsPayloadTooLarge() {
        ObjectNode node = validInvoke();
        node.set("payload", plain.createObjectNode().put("blob", "x".repeat(1_048_577)));
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void messageJustUnderLimitParses() {
        // Well-formed and < 1 MiB total: must parse.
        ObjectNode node = validInvoke();
        node.set("payload", plain.createObjectNode().put("blob", "x".repeat(200_000)));
        assertThat(codec.parse(node.toString())).isInstanceOf(IncomingEnvelope.Invoke.class);
    }

    @Test
    void idLongerThan128IsInvalid() {
        ObjectNode node = validInvoke();
        node.put("id", "i".repeat(129));
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
        ObjectNode ok = validInvoke();
        ok.put("id", "i".repeat(128));
        assertThat(codec.parse(ok.toString())).isInstanceOf(IncomingEnvelope.Invoke.class);
    }

    @Test
    void commandLongerThanMaxNameLengthIsInvalid() {
        ObjectNode node = validInvoke();
        node.put("command", "c".repeat(IpcLimits.DEFAULTS.maxNameLength() + 1));
        assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void loweredMaxNameLengthIsEnforced() {
        EnvelopeCodec strict = new EnvelopeCodec(new JacksonJsonCodec(),
                new IpcLimits(1_048_576, 128, java.time.Duration.ofSeconds(30), 256, 16));
        ObjectNode node = validInvoke();
        node.put("command", "c".repeat(17));
        assertProtocolError(() -> strict.parse(node.toString()), ErrorCode.INVALID_REQUEST);
    }

    @Test
    void nonceLongerThan128IsInvalid() {
        for (ObjectNode node : List.of(validHello(), validInvoke(), validCancel())) {
            node.put("nonce", "n".repeat(129));
            assertProtocolError(() -> codec.parse(node.toString()), ErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void emptyStringFieldsAreInvalid() {
        ObjectNode emptyId = validInvoke();
        emptyId.put("id", "");
        assertProtocolError(() -> codec.parse(emptyId.toString()), ErrorCode.INVALID_REQUEST);
        ObjectNode emptyNonce = validHello();
        emptyNonce.put("nonce", "");
        assertProtocolError(() -> codec.parse(emptyNonce.toString()), ErrorCode.INVALID_REQUEST);
    }

    // ---- outgoing envelopes ----

    @Test
    void helloAckShape() throws Exception {
        JsonNode node = plain.readTree(codec.helloAck("nonce-1"));
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("v", "kind", "ok", "nonce");
        assertThat(node.get("v").intValue()).isEqualTo(1);
        assertThat(node.get("kind").textValue()).isEqualTo("helloAck");
        assertThat(node.get("ok").booleanValue()).isTrue();
        assertThat(node.get("nonce").textValue()).isEqualTo("nonce-1");
    }

    @Test
    void helloErrorShape() throws Exception {
        JsonNode node = plain.readTree(codec.helloError(
                ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, "Unsupported protocol version 2"));
        assertThat(node.get("kind").textValue()).isEqualTo("helloAck");
        assertThat(node.get("ok").booleanValue()).isFalse();
        assertThat(node.at("/error/code").textValue())
                .isEqualTo("PROTOCOL_VERSION_UNSUPPORTED");
    }

    @Test
    void successResultShapeEmbedsCodecEncodedValueVerbatim() throws Exception {
        JacksonJsonCodec jsonCodec = new JacksonJsonCodec();
        Payload value = new Payload("Hello Tuan");
        String result = codec.successResult("01JREQ", value);
        JsonNode node = plain.readTree(result);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("v", "kind", "id", "ok", "value");
        assertThat(node.get("v").intValue()).isEqualTo(1);
        assertThat(node.get("kind").textValue()).isEqualTo("result");
        assertThat(node.get("id").textValue()).isEqualTo("01JREQ");
        assertThat(node.get("ok").booleanValue()).isTrue();
        assertThat(node.get("value").get("message").textValue()).isEqualTo("Hello Tuan");
        // The value is the codec's own encoding, embedded verbatim.
        assertThat(result).contains(jsonCodec.encode(value));
    }

    @Test
    void successResultWithMapValueUsesDeterministicKeyOrder() throws Exception {
        String result = codec.successResult("id-1", Map.of("b", 2, "a", 1));
        assertThat(result).contains("{\"a\":1,\"b\":2}");
        JsonNode node = plain.readTree(result);
        assertThat(node.get("value").get("a").intValue()).isEqualTo(1);
    }

    @Test
    void errorResultShape() throws Exception {
        JsonNode node = plain.readTree(
                codec.errorResult("01JREQ", ErrorCode.CAPABILITY_DENIED, "Command is not allowed for this window"));
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("v", "kind", "id", "ok", "error");
        assertThat(node.get("v").intValue()).isEqualTo(1);
        assertThat(node.get("kind").textValue()).isEqualTo("result");
        assertThat(node.get("id").textValue()).isEqualTo("01JREQ");
        assertThat(node.get("ok").booleanValue()).isFalse();
        JsonNode error = node.get("error");
        assertThat(fieldNames(error)).containsExactlyInAnyOrder("code", "message");
        assertThat(error.get("code").textValue()).isEqualTo("CAPABILITY_DENIED");
        assertThat(error.get("message").textValue()).isEqualTo("Command is not allowed for this window");
    }

    @Test
    void eventShape() throws Exception {
        JsonNode node = plain.readTree(codec.event("app.tick", new Payload("tick")));
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("v", "kind", "event", "payload");
        assertThat(node.get("v").intValue()).isEqualTo(1);
        assertThat(node.get("kind").textValue()).isEqualTo("event");
        assertThat(node.get("event").textValue()).isEqualTo("app.tick");
        assertThat(node.get("payload").get("message").textValue()).isEqualTo("tick");
    }

    @Test
    void eventWithNullPayloadEncodesJsonNull() throws Exception {
        JsonNode node = plain.readTree(codec.event("app.tick", null));
        assertThat(node.get("payload").isNull()).isTrue();
    }
}
