package dev.jdesk.runtime.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.api.FileWatchHandle;
import dev.jdesk.api.FileWatchOptions;
import dev.jdesk.api.JDeskException;
import dev.jdesk.webview.spi.FileWatchBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coalescing, delivery threading, lifecycle and caps of the file-watch manager. */
class FileWatchManagerTest {

    @TempDir
    Path dir;

    /** A backend whose sink the test drives directly, standing in for OS notifications. */
    private static final class FakeBackend implements FileWatchBackend {
        final AtomicReference<Consumer<FileWatchEvent>> sink = new AtomicReference<>();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public Watch watch(Path root, boolean recursive, Consumer<FileWatchEvent> sink) {
            this.sink.set(sink);
            return closes::incrementAndGet;
        }

        void push(FileWatchEvent event) {
            sink.get().accept(event);
        }
    }

    private static FileWatchOptions window(long millis) {
        return FileWatchOptions.RECURSIVE.withCoalesceWindow(Duration.ofMillis(millis));
    }

    @Test
    void coalescesAndDeduplicatesWithinWindow() throws Exception {
        FakeBackend backend = new FakeBackend();
        BlockingQueue<List<FileWatchEvent>> batches = new LinkedBlockingQueue<>();
        try (FileWatchManager manager = new FileWatchManager(backend)) {
            manager.watch(dir, window(200), batches::add);
            Path a = dir.resolve("a.txt");
            Path b = dir.resolve("b.txt");
            backend.push(new FileWatchEvent(a, FileWatchEvent.Kind.CREATED));
            backend.push(new FileWatchEvent(a, FileWatchEvent.Kind.CREATED)); // duplicate
            backend.push(new FileWatchEvent(b, FileWatchEvent.Kind.MODIFIED));

            List<FileWatchEvent> batch = batches.poll(5, TimeUnit.SECONDS);
            assertThat(batch).as("one coalesced batch").isNotNull().hasSize(2);
            assertThat(batch).extracting(FileWatchEvent::path).containsExactlyInAnyOrder(a, b);
            assertThat(batches.poll(400, TimeUnit.MILLISECONDS)).as("no second batch").isNull();
        }
    }

    @Test
    void invalidRootIsRejectedWithInvalidRequest() {
        try (FileWatchManager manager = new FileWatchManager(new FakeBackend())) {
            Path missing = dir.resolve("does-not-exist");
            assertThatThrownBy(() -> manager.watch(missing, FileWatchOptions.RECURSIVE, e -> { }))
                    .isInstanceOfSatisfying(JDeskException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    void closingHandleStopsBackendAndDropsRegistration() {
        FakeBackend backend = new FakeBackend();
        try (FileWatchManager manager = new FileWatchManager(backend)) {
            FileWatchHandle handle = manager.watch(dir, FileWatchOptions.RECURSIVE, e -> { });
            assertThat(manager.activeWatchCount()).isEqualTo(1);
            assertThat(handle.isActive()).isTrue();

            handle.close();
            assertThat(handle.isActive()).isFalse();
            assertThat(manager.activeWatchCount()).isZero();
            assertThat(backend.closes.get()).isEqualTo(1);

            handle.close(); // idempotent
            assertThat(backend.closes.get()).isEqualTo(1);
        }
    }

    @Test
    void closeAllStopsEveryBackendWatch() {
        FakeBackend backend = new FakeBackend();
        FileWatchManager manager = new FileWatchManager(backend);
        manager.watch(dir, FileWatchOptions.RECURSIVE, e -> { });
        manager.watch(dir, FileWatchOptions.NON_RECURSIVE, e -> { });
        assertThat(manager.activeWatchCount()).isEqualTo(2);

        manager.close();
        assertThat(backend.closes.get()).isEqualTo(2);
        assertThat(manager.activeWatchCount()).isZero();
    }

    @Test
    void afterCloseNewWatchesAreRejected() {
        FileWatchManager manager = new FileWatchManager(new FakeBackend());
        manager.close();
        assertThatThrownBy(() -> manager.watch(dir, FileWatchOptions.RECURSIVE, e -> { }))
                .isInstanceOfSatisfying(JDeskException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }

    @Test
    void watchLimitIsEnforced() {
        try (FileWatchManager manager = new FileWatchManager(new FakeBackend())) {
            for (int i = 0; i < 128; i++) {
                manager.watch(dir, FileWatchOptions.RECURSIVE, e -> { });
            }
            assertThatThrownBy(() -> manager.watch(dir, FileWatchOptions.RECURSIVE, e -> { }))
                    .isInstanceOfSatisfying(JDeskException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        }
    }

    @Test
    void listenerExceptionDoesNotKillTheWatch() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger calls = new AtomicInteger();
        BlockingQueue<List<FileWatchEvent>> delivered = new LinkedBlockingQueue<>();
        Consumer<List<FileWatchEvent>> listener = batch -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom");
            }
            delivered.add(batch);
        };
        try (FileWatchManager manager = new FileWatchManager(backend)) {
            FileWatchHandle handle = manager.watch(dir, window(30), listener);

            backend.push(new FileWatchEvent(dir.resolve("first"), FileWatchEvent.Kind.CREATED));
            // Wait for the first (throwing) batch to be delivered and swallowed.
            for (int i = 0; i < 100 && calls.get() == 0; i++) {
                Thread.sleep(20);
            }
            assertThat(calls.get()).isGreaterThanOrEqualTo(1);
            assertThat(handle.isActive()).as("watch survives a throwing listener").isTrue();

            Path second = dir.resolve("second");
            backend.push(new FileWatchEvent(second, FileWatchEvent.Kind.MODIFIED));
            List<FileWatchEvent> batch = delivered.poll(5, TimeUnit.SECONDS);
            assertThat(batch).isNotNull();
            assertThat(batch).extracting(FileWatchEvent::path).containsExactly(second);
        }
    }
}
