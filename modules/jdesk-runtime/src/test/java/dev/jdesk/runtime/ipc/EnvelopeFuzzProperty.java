package dev.jdesk.runtime.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Fuzzing invariant (spec 17.2): {@code EnvelopeCodec.parse} is total over arbitrary
 * input — it either returns a validated {@link IncomingEnvelope} or throws
 * {@link ProtocolException}. No other exception type may ever escape, no matter how
 * hostile the input.
 */
class EnvelopeFuzzProperty {

    private static final EnvelopeCodec CODEC =
            new EnvelopeCodec(new JacksonJsonCodec(), IpcLimits.DEFAULTS);
    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static void assertTotal(String raw) {
        try {
            IncomingEnvelope envelope = CODEC.parse(raw);
            if (envelope == null) {
                throw new AssertionError("parse returned null for: " + raw);
            }
        } catch (ProtocolException expected) {
            // The only allowed failure mode.
        } catch (Throwable t) {
            throw new AssertionError(
                    "parse leaked " + t.getClass().getName() + " for input: " + abbreviate(raw), t);
        }
    }

    private static String abbreviate(String raw) {
        return raw != null && raw.length() > 300 ? raw.substring(0, 300) + "..." : String.valueOf(raw);
    }

    // ---- property 1: raw garbage strings ----

    @Property
    void arbitraryStringsNeverLeakUnexpectedExceptions(
            @ForAll @From("garbage") String raw) {
        assertTotal(raw);
    }

    @Provide
    Arbitrary<String> garbage() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(2048),
                Arbitraries.strings().withCharRange((char) 0, (char) 0xFFFF).ofMaxLength(512),
                Arbitraries.strings().withChars("{}[]\",:\\ \n\t\0truefalsnul0123456789.eE+-")
                        .ofMaxLength(256));
    }

    // ---- property 2: random well-formed JSON documents ----

    @Property
    void randomJsonDocumentsNeverLeakUnexpectedExceptions(
            @ForAll @From("jsonDocuments") String document) {
        assertTotal(document);
    }

    @Provide
    Arbitrary<String> jsonDocuments() {
        return jsonValue(4).map(EnvelopeFuzzProperty::write);
    }

    private static String write(Object value) {
        try {
            return PLAIN.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Arbitrary<Object> jsonValue(int depth) {
        Arbitrary<Object> scalar = Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(24).map(s -> (Object) s),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.longs().map(l -> (Object) l),
                Arbitraries.doubles().map(d -> (Object) d),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b),
                Arbitraries.just((Object) null));
        if (depth == 0) {
            return scalar;
        }
        Arbitrary<Object> array = jsonValue(depth - 1).list().ofMaxSize(4).map(l -> (Object) l);
        Arbitrary<Object> object = Arbitraries.maps(
                        Arbitraries.strings().ofMinLength(1).ofMaxLength(12), jsonValue(depth - 1))
                .ofMaxSize(4)
                .map(m -> (Object) m);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, scalar),
                net.jqwik.api.Tuple.of(2, array),
                net.jqwik.api.Tuple.of(2, object));
    }

    // ---- property 3: mutations of valid envelopes ----

    @Property
    void mutatedValidEnvelopesNeverLeakUnexpectedExceptions(
            @ForAll @From("mutatedEnvelopes") String mutated) {
        assertTotal(mutated);
    }

    @Provide
    Arbitrary<String> mutatedEnvelopes() {
        Arbitrary<ObjectNode> base = Arbitraries.of("hello", "invoke", "cancel")
                .map(EnvelopeFuzzProperty::validEnvelope);
        Arbitrary<Mutation> mutation = Arbitraries.of(Mutation.values());
        Arbitrary<String> fieldName = Arbitraries.of(
                "v", "kind", "id", "command", "payload", "nonce", "client", "clientVersion", "extra");
        Arbitrary<Integer> hugeLength = Arbitraries.integers().between(129, 5000);
        return Combinators.combine(base, mutation, fieldName, hugeLength)
                .as(EnvelopeFuzzProperty::mutate)
                .map(ObjectNode::toString);
    }

    private enum Mutation {
        DROP_FIELD, ADD_FIELD, STRING_TO_NUMBER, STRING_TO_OBJECT, NUMBER_TO_STRING,
        HUGE_STRING, NULL_FIELD, NESTED_PAYLOAD, NONE
    }

    private static ObjectNode validEnvelope(String kind) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("v", 1);
        node.put("kind", kind);
        node.put("nonce", "0123456789abcdef0123456789abcdef");
        switch (kind) {
            case "hello" -> {
                node.put("client", "jdesk-client");
                node.put("clientVersion", "0.1.0");
            }
            case "invoke" -> {
                node.put("id", "req-1");
                node.put("command", "test.echo");
                node.set("payload", JsonNodeFactory.instance.objectNode().put("name", "x"));
            }
            case "cancel" -> node.put("id", "req-1");
            default -> throw new IllegalArgumentException(kind);
        }
        return node;
    }

    private static ObjectNode mutate(ObjectNode node, Mutation mutation, String field, int hugeLength) {
        switch (mutation) {
            case DROP_FIELD -> node.remove(field);
            case ADD_FIELD -> node.put(field + "X", "surprise");
            case STRING_TO_NUMBER -> node.put(field, 12345);
            case STRING_TO_OBJECT -> node.set(field, JsonNodeFactory.instance.objectNode().put("a", 1));
            case NUMBER_TO_STRING -> node.put("v", "1");
            case HUGE_STRING -> node.put(field, "h".repeat(hugeLength));
            case NULL_FIELD -> node.putNull(field);
            case NESTED_PAYLOAD -> node.set("payload", deeplyNestedArray(80));
            case NONE -> {
                // Unmutated valid envelope: parse must succeed, which assertTotal allows.
            }
        }
        return node;
    }

    // ---- property 4: deep nesting beyond limits ----

    @Property
    void deeplyNestedDocumentsNeverLeakUnexpectedExceptions(
            @ForAll @From("deepDocuments") String document) {
        assertTotal(document);
    }

    @Provide
    Arbitrary<String> deepDocuments() {
        Arbitrary<Integer> depth = Arbitraries.integers().between(1, 300);
        Arbitrary<Boolean> asPayload = Arbitraries.of(true, false);
        Arbitrary<Boolean> arrays = Arbitraries.of(true, false);
        return Combinators.combine(depth, asPayload, arrays).as((d, wrapInInvoke, useArrays) -> {
            if (wrapInInvoke) {
                ObjectNode invoke = validEnvelope("invoke");
                invoke.set("payload", useArrays ? deeplyNestedArray(d) : deeplyNestedObject(d));
                return invoke.toString();
            }
            return (useArrays ? deeplyNestedArray(d) : deeplyNestedObject(d)).toString();
        });
    }

    private static ArrayNode deeplyNestedArray(int depth) {
        ArrayNode root = JsonNodeFactory.instance.arrayNode();
        ArrayNode current = root;
        for (int i = 1; i < depth; i++) {
            ArrayNode child = JsonNodeFactory.instance.arrayNode();
            current.add(child);
            current = child;
        }
        current.add(1);
        return root;
    }

    private static ObjectNode deeplyNestedObject(int depth) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode current = root;
        for (int i = 1; i < depth; i++) {
            ObjectNode child = JsonNodeFactory.instance.objectNode();
            current.set("n", child);
            current = child;
        }
        current.put("n", 1);
        return root;
    }
}
