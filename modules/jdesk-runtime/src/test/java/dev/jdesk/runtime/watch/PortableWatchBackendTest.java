package dev.jdesk.runtime.watch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.webview.spi.FileWatchBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real {@code WatchService} plumbing. Deadlines are generous (up to 25&nbsp;s) because on
 * macOS the JDK falls back to a ~2&nbsp;s polling service — this is the slow fallback the
 * FSEvents backend exists to replace, but it must still be correct.
 */
class PortableWatchBackendTest {

    @TempDir
    Path dir;

    private static final long DEADLINE_MS = 25_000;

    @Test
    void detectsCreatesAcrossAPreexistingSubtree() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        Set<Path> created = ConcurrentHashMap.newKeySet();
        FileWatchBackend backend = new PortableWatchBackend();

        try (FileWatchBackend.Watch watch = backend.watch(dir, true, event -> {
            if (event.kind() == FileWatchEvent.Kind.CREATED) {
                created.add(event.path().getFileName());
            }
        })) {
            // WatchService registration is asynchronous on some platforms; small settle.
            Thread.sleep(200);
            Files.createFile(dir.resolve("a.txt"));
            Files.createFile(sub.resolve("b.txt"));

            Path a = Path.of("a.txt");
            Path b = Path.of("b.txt");
            long deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L;
            while (System.nanoTime() < deadline && !(created.contains(a) && created.contains(b))) {
                Thread.sleep(100);
            }
            assertThat(created)
                    .as("top-level create and recursive subtree create both detected")
                    .contains(a, b);
        }
    }

    @Test
    void closeIsCleanAndIdempotent() throws Exception {
        FileWatchBackend backend = new PortableWatchBackend();
        FileWatchBackend.Watch watch = backend.watch(dir, false, event -> { });
        watch.close();
        watch.close(); // must not throw
    }
}
