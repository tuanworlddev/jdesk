package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.WindowId;
import dev.jdesk.webview.spi.WindowBounds;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowStateStoreTest {

    @TempDir
    Path stateDir;

    @BeforeEach
    void pointStateDirAtTempDir() {
        System.setProperty("jdesk.state.dir", stateDir.toString());
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty("jdesk.state.dir");
    }

    @Test
    void savedBoundsRoundTripPerWindow() {
        WindowStateStore store = new WindowStateStore("dev.example.app");
        store.save(new WindowId("main"), new WindowBounds(40, 60, 1200, 800));
        store.save(new WindowId("tools"), new WindowBounds(5, 6, 300, 200));

        WindowStateStore reopened = new WindowStateStore("dev.example.app");
        assertThat(reopened.load(new WindowId("main")))
                .contains(new WindowBounds(40, 60, 1200, 800));
        assertThat(reopened.load(new WindowId("tools")))
                .contains(new WindowBounds(5, 6, 300, 200));
        assertThat(reopened.load(new WindowId("unknown"))).isEmpty();
    }

    @Test
    void appsDoNotShareState() {
        new WindowStateStore("dev.example.one")
                .save(new WindowId("main"), new WindowBounds(1, 2, 640, 480));
        assertThat(new WindowStateStore("dev.example.two").load(new WindowId("main"))).isEmpty();
    }

    @Test
    void corruptOrInsaneEntriesAreIgnored() throws Exception {
        WindowStateStore store = new WindowStateStore("dev.example.app");
        Files.writeString(stateDir.resolve("dev.example.app.properties"),
                "main.x=abc\nmain.y=1\nmain.width=100\nmain.height=100\n");
        assertThat(store.load(new WindowId("main"))).isEmpty();

        store.save(new WindowId("main"), new WindowBounds(0, 0, 0, 0));
        assertThat(store.load(new WindowId("main")))
                .as("degenerate bounds are never persisted")
                .isEmpty();
    }
}
