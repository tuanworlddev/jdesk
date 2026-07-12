package dev.jdesk.runtime.watch;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.webview.spi.FileWatchBackend;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Portable {@link FileWatchBackend} over the JDK {@code WatchService}. Event-driven and
 * recursive (new directories are registered as they appear) on Windows/Linux, where the
 * service is kernel-backed. It also works on macOS but there the JDK falls back to a
 * ~2&nbsp;s polling service — which is exactly why macOS ships its own FSEvents backend and
 * this remains the fallback.
 */
public final class PortableWatchBackend implements FileWatchBackend {

    @Override
    public Watch watch(Path root, boolean recursive, Consumer<FileWatchEvent> sink)
            throws IOException {
        return new WatchServiceWatch(root, recursive, sink);
    }

    private static final class WatchServiceWatch implements Watch {
        private static final Logger LOG = System.getLogger(WatchServiceWatch.class.getName());

        private final WatchService service;
        private final Path root;
        private final boolean recursive;
        private final Consumer<FileWatchEvent> sink;
        private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
        private final Thread thread;
        private volatile boolean running = true;

        WatchServiceWatch(Path root, boolean recursive, Consumer<FileWatchEvent> sink)
                throws IOException {
            this.root = root;
            this.recursive = recursive;
            this.sink = sink;
            this.service = root.getFileSystem().newWatchService();
            if (recursive) {
                registerAll(root);
            } else {
                register(root);
            }
            this.thread = new Thread(this::loop, "jdesk-watchservice");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void register(Path dir) throws IOException {
            WatchKey key = dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE, OVERFLOW);
            directories.put(key, dir);
        }

        private void registerAll(Path start) throws IOException {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    register(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private void loop() {
            while (running) {
                WatchKey key;
                try {
                    key = service.take();
                } catch (ClosedWatchServiceException | InterruptedException e) {
                    return;
                }
                Path dir = directories.get(key);
                if (dir != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        dispatch(dir, event);
                    }
                }
                if (!key.reset()) {
                    directories.remove(key);
                    if (directories.isEmpty()) {
                        return; // watched tree is gone
                    }
                }
            }
        }

        private void dispatch(Path dir, WatchEvent<?> event) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == OVERFLOW) {
                emit(FileWatchEvent.overflow(root));
                return;
            }
            Path child = dir.resolve((Path) event.context());
            FileWatchEvent.Kind mapped = kind == ENTRY_CREATE ? FileWatchEvent.Kind.CREATED
                    : kind == ENTRY_DELETE ? FileWatchEvent.Kind.DELETED
                    : FileWatchEvent.Kind.MODIFIED;
            emit(new FileWatchEvent(child, mapped));
            // Recursively pick up subtrees created after the watch started.
            if (recursive && kind == ENTRY_CREATE
                    && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    registerAll(child);
                } catch (IOException e) {
                    LOG.log(Level.DEBUG, "Failed to register new subtree " + child, e);
                }
            }
        }

        private void emit(FileWatchEvent event) {
            try {
                sink.accept(event);
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "File watch sink threw", e);
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                service.close();
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "WatchService close failed", e);
            }
            thread.interrupt();
        }
    }
}
