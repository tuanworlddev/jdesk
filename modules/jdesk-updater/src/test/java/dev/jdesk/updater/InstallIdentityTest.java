package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallIdentityTest {

    @TempDir
    Path temp;

    @Test
    void createsOnceThenReturnsTheSameIdOnReload() {
        Path file = temp.resolve("state").resolve("install-id");
        String first = InstallIdentity.loadOrCreate(file);
        assertThat(first).isNotBlank();
        assertThat(UUID.fromString(first)).isNotNull(); // well-formed UUID
        assertThat(Files.exists(file)).isTrue();
        String second = InstallIdentity.loadOrCreate(file);
        assertThat(second).isEqualTo(first); // stable across calls
    }

    @Test
    void rejectsACorruptIdentityFile() throws Exception {
        Path file = temp.resolve("install-id");
        Files.writeString(file, "not-a-uuid");
        assertThatThrownBy(() -> InstallIdentity.loadOrCreate(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }
}
