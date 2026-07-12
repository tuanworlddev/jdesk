package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Headless DPAPI secret-store tests (Windows-only; {@code CryptProtectData} needs no UI). The
 * store writes to {@code ~/.jdesk/secrets/<appId>.properties}; a unique app id keeps the test
 * isolated and its file is removed afterwards.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSecretStoreTest {

    private final String appId = "jdesk-secret-test-" + Long.toHexString(hashCode())
            + "-" + Thread.currentThread().threadId();
    private final Path file = Path.of(System.getProperty("user.home"), ".jdesk", "secrets",
            appId + ".properties");

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(file);
    }

    @Test
    void roundTripsRotatesAndDeletes() {
        WindowsSecretStore store = new WindowsSecretStore(appId);
        assertThat(store.get("api-key")).isEmpty();

        store.put("api-key", "wb-secret-줄기-🔐"); // unicode + emoji
        assertThat(store.get("api-key")).contains("wb-secret-줄기-🔐");

        store.put("api-key", "rotated");
        assertThat(store.get("api-key")).contains("rotated");

        store.delete("api-key");
        assertThat(store.get("api-key")).isEmpty();
    }

    @Test
    void ciphertextOnDiskIsNotPlaintext() throws Exception {
        WindowsSecretStore store = new WindowsSecretStore(appId);
        store.put("token", "PLAINTEXT-MARKER-12345");
        String onDisk = Files.readString(file);
        assertThat(onDisk).doesNotContain("PLAINTEXT-MARKER-12345");
        assertThat(store.get("token")).contains("PLAINTEXT-MARKER-12345");
    }

    @Test
    void rejectsInvalidKeys() {
        WindowsSecretStore store = new WindowsSecretStore(appId);
        assertThatThrownBy(() -> store.get(" "))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> store.put("k", null))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
