package dev.jdesk.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedPolicyTest {
    @TempDir Path temp;

    @Test void parsesRestrictivePolicyAndDefaultsOmittedFields() {
        ManagedPolicy policy = ManagedPolicy.parse("""
                {"version":1,"automationAllowed":false,"externalBrowserAllowed":false}
                """);
        assertThat(policy.automationAllowed()).isFalse();
        assertThat(policy.externalBrowserAllowed()).isFalse();
        assertThat(policy.devToolsAllowed()).isTrue();
        assertThat(policy.consoleForwardingAllowed()).isTrue();
    }

    @Test void rejectsUnknownWrongTypeOversizeAndSymlink() throws Exception {
        assertThatThrownBy(() -> ManagedPolicy.parse(
                """
                {"version":1,"automationAllowd":false}
                """))
                .hasMessageContaining("Unknown managed policy field");
        assertThatThrownBy(() -> ManagedPolicy.parse(
                """
                {"version":1,"devToolsAllowed":"no"}
                """))
                .hasMessageContaining("must be boolean");
        Path large = temp.resolve("large.json");
        Files.writeString(large, "x".repeat(64 * 1024 + 1));
        assertThatThrownBy(() -> ManagedPolicy.fromFile(large))
                .hasMessage("Managed policy is too large");
        Path target = temp.resolve("policy.json");
        Files.writeString(target, """
                {"version":1}
                """);
        Path link = temp.resolve("link.json");
        Files.createSymbolicLink(link, target);
        assertThatThrownBy(() -> ManagedPolicy.fromFile(link))
                .hasMessage("Managed policy must be a regular file");
    }
}
