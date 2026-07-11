package dev.jdesk.runtime.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.runtime.internal.DefensiveJson;
import dev.jdesk.runtime.json.JsonCodec;
import dev.jdesk.runtime.json.JsonLimits;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

/**
 * Strict envelope parsing and canonical envelope serialization (spec section 10).
 * Top-level fields are a closed set per kind; unknown fields, wrong types, and unknown
 * kinds fail deterministically before any user code runs.
 */
public final class EnvelopeCodec {
    public static final int PROTOCOL_VERSION = 1;

    private static final Set<String> HELLO_FIELDS = Set.of("v", "kind", "client", "clientVersion", "nonce");
    private static final Set<String> INVOKE_FIELDS = Set.of("v", "kind", "id", "command", "payload", "nonce");
    private static final Set<String> CANCEL_FIELDS = Set.of("v", "kind", "id", "nonce");

    private final ObjectMapper mapper;
    private final JsonCodec codec;
    private final IpcLimits limits;

    public EnvelopeCodec(JsonCodec codec, IpcLimits limits) {
        this.codec = codec;
        this.limits = limits;
        this.mapper = DefensiveJson.build(new JsonLimits(
                JsonLimits.DEFAULTS.maxNestingDepth(),
                JsonLimits.DEFAULTS.maxStringLength(),
                JsonLimits.DEFAULTS.maxNumberLength(),
                limits.maxMessageBytes()));
    }

    public IncomingEnvelope parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ProtocolException(ErrorCode.INVALID_REQUEST, "Empty message");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > limits.maxMessageBytes()) {
            throw new ProtocolException(ErrorCode.PAYLOAD_TOO_LARGE, "Message exceeds size limit");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new ProtocolException(ErrorCode.INVALID_REQUEST, "Malformed JSON envelope");
        }
        if (root == null || !root.isObject()) {
            throw new ProtocolException(ErrorCode.INVALID_REQUEST, "Envelope must be a JSON object");
        }
        int version = requireInt(root, "v");
        if (version != PROTOCOL_VERSION) {
            throw new ProtocolException(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                    "Unsupported protocol version " + version);
        }
        String kind = requireString(root, "kind", 32);
        return switch (kind) {
            case "hello" -> {
                rejectUnknownFields(root, HELLO_FIELDS);
                yield new IncomingEnvelope.Hello(version,
                        requireString(root, "client", 128),
                        requireString(root, "clientVersion", 64),
                        requireString(root, "nonce", 128));
            }
            case "invoke" -> {
                rejectUnknownFields(root, INVOKE_FIELDS);
                String id = requireString(root, "id", 128);
                String command = requireString(root, "command", limits.maxNameLength());
                JsonNode payload = root.get("payload");
                Optional<String> payloadJson = payload == null || payload.isNull()
                        ? Optional.empty()
                        : Optional.of(payload.toString());
                yield new IncomingEnvelope.Invoke(version, id, command, payloadJson,
                        requireString(root, "nonce", 128));
            }
            case "cancel" -> {
                rejectUnknownFields(root, CANCEL_FIELDS);
                yield new IncomingEnvelope.Cancel(version,
                        requireString(root, "id", 128),
                        requireString(root, "nonce", 128));
            }
            default -> throw new ProtocolException(ErrorCode.INVALID_REQUEST,
                    "Unknown envelope kind");
        };
    }

    // ---- outgoing ----

    /** Control envelope delivering the per-navigation nonce to the fresh document. */
    public String nonceEnvelope(String nonce) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", PROTOCOL_VERSION);
        node.put("kind", "nonce");
        node.put("nonce", nonce);
        return node.toString();
    }

    public String helloAck(String nonce) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", PROTOCOL_VERSION);
        node.put("kind", "helloAck");
        node.put("ok", true);
        node.put("nonce", nonce);
        return node.toString();
    }

    public String successResult(String id, Object value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", PROTOCOL_VERSION);
        node.put("kind", "result");
        node.put("id", id);
        node.put("ok", true);
        node.putRawValue("value", new RawValue(codec.encode(value)));
        return node.toString();
    }

    public String errorResult(String id, ErrorCode code, String publicMessage) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", PROTOCOL_VERSION);
        node.put("kind", "result");
        node.put("id", id);
        node.put("ok", false);
        ObjectNode error = node.putObject("error");
        error.put("code", code.name());
        error.put("message", publicMessage);
        return node.toString();
    }

    public String event(String eventName, Object payload) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", PROTOCOL_VERSION);
        node.put("kind", "event");
        node.put("event", eventName);
        node.putRawValue("payload", new RawValue(codec.encode(payload)));
        return node.toString();
    }

    // ---- helpers ----

    private static void rejectUnknownFields(JsonNode root, Set<String> allowed) {
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new ProtocolException(ErrorCode.INVALID_REQUEST,
                        "Unknown envelope field: " + name);
            }
        }
    }

    private static int requireInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isInt()) {
            throw new ProtocolException(ErrorCode.INVALID_REQUEST,
                    "Missing or non-integer field: " + field);
        }
        return node.intValue();
    }

    private static String requireString(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new ProtocolException(ErrorCode.INVALID_REQUEST,
                    "Missing or non-string field: " + field);
        }
        String value = node.textValue();
        if (value.isEmpty() || value.length() > maxLength) {
            throw new ProtocolException(ErrorCode.INVALID_REQUEST,
                    "Field out of length bounds: " + field);
        }
        return value;
    }
}
