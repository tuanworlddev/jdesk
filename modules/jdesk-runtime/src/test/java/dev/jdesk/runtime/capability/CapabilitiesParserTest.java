package dev.jdesk.runtime.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.WindowId;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * Strict {@code jdesk-capabilities.json} parsing: a typo must fail startup, never
 * silently widen or narrow permissions (spec section 12.1).
 */
class CapabilitiesParserTest {

    private static void assertIllegalState(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }

    @Test
    void parsesWindowScopedAndAllWindowsGrants() {
        CapabilitySet set = Capabilities.parse("""
                {
                  "version": 1,
                  "grants": [
                    { "capability": "greeting:use", "windows": ["main", "settings"] },
                    { "capability": "clipboard:read" }
                  ]
                }
                """);
        assertThat(set.grants()).containsExactlyInAnyOrder(
                new CapabilityGrant("greeting:use", Set.of("main", "settings")),
                CapabilityGrant.forAllWindows("clipboard:read"));
        assertThat(set.isGranted("greeting:use", new WindowId("main"))).isTrue();
        assertThat(set.isGranted("greeting:use", new WindowId("other"))).isFalse();
        assertThat(set.isGranted("clipboard:read", new WindowId("anything"))).isTrue();
        assertThat(set.isGranted("never:granted", new WindowId("main"))).isFalse();
    }

    @Test
    void emptyGrantsArrayYieldsDenyEverything() {
        CapabilitySet set = Capabilities.parse("{\"version\":1,\"grants\":[]}");
        assertThat(set.grants()).isEmpty();
        assertThat(set.isGranted("anything", new WindowId("main"))).isFalse();
    }

    @Test
    void unknownTopLevelFieldFailsStartup() {
        assertIllegalState(() -> Capabilities.parse(
                "{\"version\":1,\"grants\":[],\"extra\":true}"));
    }

    @Test
    void unknownGrantFieldFailsStartup() {
        assertIllegalState(() -> Capabilities.parse("""
                {"version":1,"grants":[{"capability":"a:b","window":["main"]}]}
                """));
    }

    @Test
    void versionOtherThanOneIsRejected() {
        assertIllegalState(() -> Capabilities.parse("{\"version\":2,\"grants\":[]}"));
        assertIllegalState(() -> Capabilities.parse("{\"version\":\"1\",\"grants\":[]}"));
        assertIllegalState(() -> Capabilities.parse("{\"grants\":[]}"));
    }

    @Test
    void missingGrantsIsRejected() {
        assertIllegalState(() -> Capabilities.parse("{\"version\":1}"));
    }

    @Test
    void nonArrayGrantsIsRejected() {
        assertIllegalState(() -> Capabilities.parse("{\"version\":1,\"grants\":{}}"));
    }

    @Test
    void nonArrayWindowsIsRejected() {
        assertIllegalState(() -> Capabilities.parse("""
                {"version":1,"grants":[{"capability":"a:b","windows":"main"}]}
                """));
    }

    @Test
    void nonStringWindowIdIsRejected() {
        assertIllegalState(() -> Capabilities.parse("""
                {"version":1,"grants":[{"capability":"a:b","windows":[42]}]}
                """));
    }

    @Test
    void nonObjectGrantIsRejected() {
        assertIllegalState(() -> Capabilities.parse("{\"version\":1,\"grants\":[\"a:b\"]}"));
    }

    @Test
    void grantWithoutCapabilityIsRejected() {
        assertIllegalState(() -> Capabilities.parse(
                "{\"version\":1,\"grants\":[{\"windows\":[\"main\"]}]}"));
        assertIllegalState(() -> Capabilities.parse(
                "{\"version\":1,\"grants\":[{\"capability\":42}]}"));
    }

    @Test
    void malformedJsonIsRejected() {
        assertIllegalState(() -> Capabilities.parse("{"));
        assertIllegalState(() -> Capabilities.parse("not json"));
    }

    @Test
    void nonObjectRootIsRejected() {
        assertIllegalState(() -> Capabilities.parse("[]"));
        assertIllegalState(() -> Capabilities.parse("42"));
    }

    @Test
    void fromResourceWithMissingResourceFailsStartup() {
        assertIllegalState(() -> Capabilities.fromResource(
                "definitely-missing-capabilities.json", getClass().getClassLoader()));
        assertIllegalState(() -> Capabilities.fromResource("definitely-missing-capabilities.json"));
    }

    @Test
    void fromResourceParsesBundledFile() {
        CapabilitySet set = Capabilities.fromResource(
                "jdesk-capabilities-test.json", getClass().getClassLoader());
        assertThat(set.isGranted("greeting:use", new WindowId("main"))).isTrue();
        assertThat(set.isGranted("greeting:use", new WindowId("other"))).isFalse();
        assertThat(set.isGranted("clipboard:read", new WindowId("other"))).isTrue();
    }
}
