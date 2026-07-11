package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.BinaryStream;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Registration failure, cancellation, read failure and close-all paths of StreamManager. */
class StreamManagerExtraTest {

    @Test
    void registerWrapsSourceOpenFailure() {
        try (StreamManager manager = new StreamManager()) {
            BinaryStream failing = new BinaryStream(10, "application/octet-stream", "x.bin",
                    () -> { throw new IOException("boom"); });
            assertThatThrownBy(() -> manager.register(failing))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Test
    void cancelRemovesRegisteredStream() {
        byte[] source = "abcdef".getBytes(StandardCharsets.UTF_8);
        try (StreamManager manager = new StreamManager()) {
            var descriptor = manager.register(new BinaryStream(source.length,
                    "application/octet-stream", "x.bin", () -> new ByteArrayInputStream(source)));
            assertThat(manager.size()).isEqualTo(1);
            manager.cancel(descriptor.streamId());
            assertThat(manager.size()).isZero();
            // A subsequent pull on the cancelled stream is now unknown.
            assertThatThrownBy(() -> manager.pull(new StreamManager.Pull(descriptor.streamId(), 4)))
                    .isInstanceOf(JDeskException.class);
        }
    }

    @Test
    void readFailureIsWrappedAndStreamClosed() {
        try (StreamManager manager = new StreamManager()) {
            InputStream broken = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("read fail");
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    throw new IOException("read fail");
                }
            };
            var descriptor = manager.register(new BinaryStream(100,
                    "application/octet-stream", "x.bin", () -> broken));
            assertThatThrownBy(() -> manager.pull(new StreamManager.Pull(descriptor.streamId(), 8)))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
            assertThat(manager.size()).isZero();
        }
    }

    @Test
    void closeReleasesAllOutstandingStreams() {
        StreamManager manager = new StreamManager();
        for (int i = 0; i < 3; i++) {
            byte[] source = "data".getBytes(StandardCharsets.UTF_8);
            manager.register(new BinaryStream(source.length, "application/octet-stream",
                    "f" + i, () -> new ByteArrayInputStream(source)));
        }
        assertThat(manager.size()).isEqualTo(3);
        manager.close();
        assertThat(manager.size()).isZero();
    }
}
