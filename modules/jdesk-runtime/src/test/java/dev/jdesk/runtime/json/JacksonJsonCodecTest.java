package dev.jdesk.runtime.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Defensive decoding/encoding behavior of the default codec (spec section 11): bounded
 * document size/depth/strings, strict duplicate keys, no polymorphic typing, and
 * deterministic error codes for every failure.
 */
class JacksonJsonCodecTest {

    public record Person(String name, int age) {
    }

    public record Profile(String name, int age, List<String> tags, Map<String, Integer> scores) {
    }

    /** DTO used for the polymorphic-attack shape; has no {@code @class} component. */
    public record Settings(String name) {
    }

    /** Directly self-referencing bean; Jackson must fail encoding it, never recurse forever. */
    public static final class SelfReferencing {
        public SelfReferencing self;
    }

    private final JacksonJsonCodec codec = new JacksonJsonCodec();

    // ---- round trips ----

    @Test
    void encodeDecodeRoundTripSimpleRecord() {
        Person original = new Person("Tuan", 30);
        String json = codec.encode(original);
        assertThat(codec.decode(json, Person.class)).isEqualTo(original);
    }

    @Test
    void encodeDecodeRoundTripNestedRecord() {
        Profile original = new Profile("dev", 7,
                List.of("a", "b", "c"), Map.of("x", 1, "y", 2));
        String json = codec.encode(original);
        assertThat(codec.decode(json, Profile.class)).isEqualTo(original);
    }

    @Test
    void encodeIsDeterministicallyOrdered() {
        // SORT_PROPERTIES_ALPHABETICALLY + ORDER_MAP_ENTRIES_BY_KEYS.
        String first = codec.encode(Map.of("b", 2, "a", 1, "c", 3));
        assertThat(first).isEqualTo("{\"a\":1,\"b\":2,\"c\":3}");
    }

    // ---- limits ----

    @Test
    void codecRejectsRaisedLimitsAtConstruction() {
        assertThatThrownBy(() -> new JacksonJsonCodec(new JsonLimits(64, 262_144, 100, 2_000_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeRejectsOversizedDocumentWithPayloadTooLarge() {
        JacksonJsonCodec small = new JacksonJsonCodec(new JsonLimits(64, 262_144, 100, 256));
        String big = "{\"name\":\"" + "x".repeat(300) + "\",\"age\":1}";
        assertThatThrownBy(() -> small.decode(big, Person.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void oversizeCheckCountsUtf8BytesNotChars() {
        JacksonJsonCodec small = new JacksonJsonCodec(new JsonLimits(64, 262_144, 100, 64));
        // 30 chars but each snowman is 3 UTF-8 bytes -> > 64 bytes total.
        String multiByte = "{\"name\":\"" + "☃".repeat(25) + "\",\"age\":1}";
        assertThatThrownBy(() -> small.decode(multiByte, Person.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void decodeRejectsNestingBeyondDefaultDepth64() {
        String tooDeep = "[".repeat(65) + "]".repeat(65);
        assertThatThrownBy(() -> codec.decode(tooDeep, Object.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
    }

    @Test
    void decodeAcceptsNestingAtExactlyDepth64() {
        String atLimit = "[".repeat(64) + "]".repeat(64);
        assertThat(codec.decode(atLimit, Object.class)).isNotNull();
    }

    @Test
    void loweredDepthLimitIsEnforced() {
        JacksonJsonCodec shallow = new JacksonJsonCodec(new JsonLimits(4, 262_144, 100, 1_048_576));
        assertThat(shallow.decode("[[[[1]]]]", Object.class)).isNotNull();
        assertThatThrownBy(() -> shallow.decode("[[[[[1]]]]]", Object.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
    }

    @Test
    void decodeRejectsStringsOverMaxStringLength() {
        JacksonJsonCodec shortStrings = new JacksonJsonCodec(new JsonLimits(64, 16, 100, 1_048_576));
        String json = "{\"name\":\"" + "x".repeat(17) + "\",\"age\":1}";
        assertThatThrownBy(() -> shortStrings.decode(json, Person.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
        // Sanity: a string within the bound decodes fine.
        assertThat(shortStrings.decode("{\"name\":\"ok\",\"age\":1}", Person.class))
                .isEqualTo(new Person("ok", 1));
    }

    @Test
    void decodeRejectsNumbersOverMaxNumberLength() {
        String json = "[" + "1".repeat(101) + "]";
        assertThatThrownBy(() -> codec.decode(json, Object.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
    }

    // ---- strictness ----

    @Test
    void decodeRejectsDuplicateKeys() {
        assertThatThrownBy(() -> codec.decode("{\"name\":\"a\",\"name\":\"b\",\"age\":1}", Person.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
        assertThatThrownBy(() -> codec.decode("{\"a\":1,\"a\":2}", Map.class))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
    }

    @Test
    void decodeRejectsMalformedJson() {
        for (String bad : new String[] {"{", "{\"a\":}", "not json", "\"unterminated", "{]"}) {
            assertThatThrownBy(() -> codec.decode(bad, Object.class))
                    .as("input: %s", bad)
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
        }
    }

    @Test
    void polymorphicClassNameShapeIsIgnoredNotInstantiated() {
        // A classic gadget-style payload. With default typing disabled and unknown
        // properties ignored, this must deserialize into the *declared* record type;
        // the named class is never consulted.
        String attack = "{\"@class\":\"java.lang.Runtime\",\"name\":\"safe\"}";
        Settings decoded = codec.decode(attack, Settings.class);
        assertThat(decoded).isExactlyInstanceOf(Settings.class);
        assertThat(decoded.name()).isEqualTo("safe");
    }

    @Test
    void unknownPropertiesAreIgnored() {
        Person decoded = codec.decode("{\"name\":\"a\",\"age\":2,\"extra\":[1,2,3]}", Person.class);
        assertThat(decoded).isEqualTo(new Person("a", 2));
    }

    // ---- encode failures ----

    @Test
    void encodeSelfReferencingObjectFailsWithSerializationError() {
        SelfReferencing loop = new SelfReferencing();
        loop.self = loop;
        assertThatThrownBy(() -> codec.encode(loop))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERIALIZATION_ERROR));
    }

    @Test
    void limitsAccessorReturnsConfiguredLimits() {
        JsonLimits limits = new JsonLimits(8, 128, 20, 512);
        assertThat(new JacksonJsonCodec(limits).limits()).isEqualTo(limits);
        assertThat(codec.limits()).isEqualTo(JsonLimits.DEFAULTS);
    }
}
