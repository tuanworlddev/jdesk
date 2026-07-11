package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BinaryStream construction, validation, accessors, and the {@link BinaryStream#of} factory. */
class BinaryStreamTest {

    @Test
    void accessorsReturnConstructorValues() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        BinaryStream.Source source = () -> new ByteArrayInputStream(payload);
        BinaryStream stream = new BinaryStream(payload.length, "text/plain", "greeting.txt", source);

        assertThat(stream.length()).isEqualTo(5);
        assertThat(stream.contentType()).isEqualTo("text/plain");
        assertThat(stream.fileName()).isEqualTo("greeting.txt");
        assertThat(stream.source()).isSameAs(source);
        assertThat(stream.source().open().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void negativeLengthRejected() {
        assertThatThrownBy(() -> new BinaryStream(-1, "text/plain", "x", () -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @Test
    void zeroLengthAllowed() {
        BinaryStream stream = new BinaryStream(0, "application/octet-stream", "empty",
                () -> new ByteArrayInputStream(new byte[0]));
        assertThat(stream.length()).isZero();
    }

    @Test
    void nullArgumentsRejected() {
        BinaryStream.Source source = () -> new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> new BinaryStream(0, null, "x", source))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BinaryStream(0, "text/plain", null, source))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BinaryStream(0, "text/plain", "x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofReadsSizeNameAndContentFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.bin");
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);

        BinaryStream stream = BinaryStream.of(file, "application/octet-stream");

        assertThat(stream.length()).isEqualTo(payload.length);
        assertThat(stream.contentType()).isEqualTo("application/octet-stream");
        assertThat(stream.fileName()).isEqualTo("data.bin");
        assertThat(stream.source().open().readAllBytes()).isEqualTo(payload);
    }
}
