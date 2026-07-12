package dev.jdesk.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceTest {
    @TempDir Path temp;

    @Test
    void secondaryHandsDeepLinkToPrimary() throws Exception {
        var received = new LinkedBlockingQueue<List<String>>();
        var first = SingleInstance.acquire("dev.test", temp, List.of(), received::add);
        assertThat(first.primary()).isTrue();
        try (var session = first.session().orElseThrow()) {
            assertThat(session.port()).isPositive();
            var second = SingleInstance.acquire("dev.test", temp,
                    List.of("jdesk-test://open/item", "--safe"), ignored -> { });
            assertThat(second.primary()).isFalse();
            assertThat(received.poll(5, TimeUnit.SECONDS))
                    .containsExactly("jdesk-test://open/item", "--safe");
        }
    }

    @Test
    void stateAndDirectoryAreOwnerOnlyBeforeServing() throws Exception {
        var first = SingleInstance.acquire("dev.secure", temp, List.of(), ignored -> { });
        try (var session = first.session().orElseThrow()) {
            assertThat(session.tokenBase64()).isNotBlank();
            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(temp)))
                        .isEqualTo("rwx------");
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                        temp.resolve("dev.secure.properties")))).isEqualTo("rw-------");
            }
        }
    }

    @Test
    void rejectsOversizeAndSymlinkState() throws Exception {
        var first = SingleInstance.acquire("dev.test", temp, List.of(), ignored -> { });
        try (var session = first.session().orElseThrow()) {
            assertThat(session.port()).isPositive();
            assertThatThrownBy(() -> SingleInstance.acquire("dev.test", temp,
                    List.of("x".repeat(20_000)), ignored -> { }))
                    .hasMessage("Handoff argument too large");
        }

        Path real = temp.resolve("real");
        Files.createDirectories(real);
        Path directoryLink = temp.resolve("link");
        Files.createSymbolicLink(directoryLink, real);
        assertThatThrownBy(() -> SingleInstance.acquire("dev.test2", directoryLink,
                List.of(), ignored -> { })).hasMessage("State directory must not be a symlink");

        Path stateTarget = temp.resolve("state-target");
        Files.writeString(stateTarget, "token=attacker");
        Files.createSymbolicLink(temp.resolve("dev.linked.properties"), stateTarget);
        assertThatThrownBy(() -> SingleInstance.acquire("dev.linked", temp,
                List.of(), ignored -> { })).hasMessage("Single-instance state must not be a symlink");
    }
}
