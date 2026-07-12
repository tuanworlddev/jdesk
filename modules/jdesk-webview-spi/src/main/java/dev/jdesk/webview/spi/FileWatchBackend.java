package dev.jdesk.webview.spi;

import dev.jdesk.api.FileWatchEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Platform file-watching backend. The runtime owns coalescing, delivery threading and
 * lifecycle; a backend only turns OS notifications into {@link FileWatchEvent}s.
 *
 * <p>Implementations push raw events to {@code sink} from an arbitrary backend thread —
 * the runtime marshals them. A missing backend (default on
 * {@link PlatformApplication#fileWatchBackend()}) makes the runtime use its portable
 * {@code WatchService} backend instead.
 */
public interface FileWatchBackend {

    /** A running watch; {@link #close()} stops delivery and frees native resources. */
    interface Watch extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Starts watching {@code root}.
     *
     * @param root existing directory to watch (absolute)
     * @param recursive watch the whole subtree, not just direct children
     * @param sink receives each change; may be called from a backend-owned thread
     * @throws IOException if the watch cannot be established
     */
    Watch watch(Path root, boolean recursive, Consumer<FileWatchEvent> sink) throws IOException;
}
