package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Dev-mode asset watcher: fires once per settled change, stops on close. */
@Timeout(30)
class DevAssetWatcherTest {

    @TempDir
    Path root;

    @Test
    void firesAfterFileChangeAndSettling() throws IOException, InterruptedException {
        Files.writeString(root.resolve("index.html"), "v1");
        CountDownLatch fired = new CountDownLatch(1);
        try (DevAssetWatcher ignored = DevAssetWatcher.start(root, fired::countDown)) {
            // Give the watcher a beat to record the initial state, then change a file.
            Thread.sleep(400);
            Files.writeString(root.resolve("index.html"), "v2-different-size");
            assertThat(fired.await(10, TimeUnit.SECONDS))
                    .as("watcher must fire after an asset change")
                    .isTrue();
        }
    }

    @Test
    void newFileTriggersReload() throws IOException, InterruptedException {
        CountDownLatch fired = new CountDownLatch(1);
        try (DevAssetWatcher ignored = DevAssetWatcher.start(root, fired::countDown)) {
            Thread.sleep(400);
            Files.writeString(root.resolve("added.css"), "body{}");
            assertThat(fired.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void closeStopsTheWatcherWithoutFiring() throws IOException, InterruptedException {
        CountDownLatch fired = new CountDownLatch(1);
        DevAssetWatcher watcher = DevAssetWatcher.start(root, fired::countDown);
        watcher.close();
        Files.writeString(root.resolve("late.txt"), "after close");
        assertThat(fired.await(1200, TimeUnit.MILLISECONDS))
                .as("closed watcher must not fire")
                .isFalse();
    }
}
