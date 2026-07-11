package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LimitedInputStreamTest {

    private static LimitedInputStream of(String data, long limit) {
        return new LimitedInputStream(
                new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), limit);
    }

    @Test
    void capsBulkReadsAtLimit() throws IOException {
        try (LimitedInputStream in = of("0123456789", 4)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("0123");
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void singleByteReadsRespectLimit() throws IOException {
        try (LimitedInputStream in = of("abc", 2)) {
            assertThat(in.read()).isEqualTo('a');
            assertThat(in.read()).isEqualTo('b');
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void skipAndAvailableAreBoundedByLimit() throws IOException {
        try (LimitedInputStream in = of("0123456789", 5)) {
            assertThat(in.available()).isEqualTo(5);
            assertThat(in.skip(3)).isEqualTo(3);
            assertThat(in.available()).isEqualTo(2);
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("34");
        }
    }

    @Test
    void limitBeyondSourceEndsAtSource() throws IOException {
        try (LimitedInputStream in = of("xy", 100)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("xy");
        }
    }
}
