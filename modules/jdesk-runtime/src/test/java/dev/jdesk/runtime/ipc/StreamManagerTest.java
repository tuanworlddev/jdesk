package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.BinaryStream;
import dev.jdesk.api.JDeskException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class StreamManagerTest {
    @Test void pullAppliesDemandAndClosesAtEof() {
        byte[] source = "abcdefgh".getBytes(StandardCharsets.UTF_8);
        try (StreamManager manager = new StreamManager()) {
            var descriptor = manager.register(new BinaryStream(source.length,
                    "application/octet-stream", "x.bin", () -> new ByteArrayInputStream(source)));
            var first = manager.pull(new StreamManager.Pull(descriptor.streamId(), 3));
            var second = manager.pull(new StreamManager.Pull(descriptor.streamId(), 5));
            var eof = manager.pull(new StreamManager.Pull(descriptor.streamId(), 5));
            assertThat(new String(Base64.getDecoder().decode(first.data()), StandardCharsets.UTF_8))
                    .isEqualTo("abc");
            assertThat(second.offset()).isEqualTo(3);
            assertThat(eof.eof()).isTrue();
            assertThat(manager.size()).isZero();
        }
    }

    @Test void rejectsOversizedDemandAndUnknownToken() {
        try (StreamManager manager = new StreamManager()) {
            assertThatThrownBy(() -> manager.pull(new StreamManager.Pull("missing", 1)))
                    .isInstanceOf(JDeskException.class);
            assertThatThrownBy(() -> manager.pull(new StreamManager.Pull("missing", 262145)))
                    .isInstanceOf(JDeskException.class);
        }
    }
}
