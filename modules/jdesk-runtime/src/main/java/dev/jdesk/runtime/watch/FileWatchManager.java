package dev.jdesk.runtime.watch;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.api.FileWatchHandle;
import dev.jdesk.api.FileWatchOptions;
import dev.jdesk.api.JDeskException;
import dev.jdesk.webview.spi.FileWatchBackend;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns file-watch lifecycle on top of a {@link FileWatchBackend}: coalescing, single-thread
 * delivery, an active-watch cap, and close-all at shutdown. Backends only translate OS
 * notifications into {@link FileWatchEvent}s (possibly on their own threads); every raw
 * event is funnelled here, deduplicated within the watch's coalescing window, and delivered
 * to the app listener on one dedicated thread so listeners never run concurrently.
 */
public final class FileWatchManager implements AutoCloseable {
    private static final Logger LOG = System.getLogger(FileWatchManager.class.getName());
    private static final int MAX_WATCHES = 128;

    private final FileWatchBackend backend;
    private final ScheduledExecutorService delivery;
    private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public FileWatchManager(FileWatchBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.delivery = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jdesk-file-watch");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** @return the number of active watches (test/diagnostic). */
    public int activeWatchCount() {
        return registrations.size();
    }

    public FileWatchHandle watch(Path root, FileWatchOptions options,
            Consumer<List<FileWatchEvent>> listener) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "File watch root is not a directory");
        }
        synchronized (this) {
            if (closed) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "File watching is shut down");
            }
            if (registrations.size() >= MAX_WATCHES) {
                throw new JDeskException(ErrorCode.LIMIT_EXCEEDED,
                        "Too many active file watches (max " + MAX_WATCHES + ")");
            }
            Registration registration = new Registration(
                    normalized, options.recursive(),
                    Math.max(0, options.coalesceWindow().toMillis()), listener);
            try {
                registration.start();
            } catch (IOException e) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                        "Failed to start file watch", null, e);
            }
            registrations.add(registration);
            return registration;
        }
    }

    @Override
    public void close() {
        List<Registration> toStop;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toStop = List.copyOf(registrations);
            registrations.clear();
        }
        for (Registration registration : toStop) {
            registration.stopQuietly();
        }
        delivery.shutdownNow();
    }

    /** One app watch: its backend watch, pending-event buffer, and coalescing flush. */
    private final class Registration implements FileWatchHandle {
        private final Path root;
        private final boolean recursive;
        private final long coalesceMillis;
        private final Consumer<List<FileWatchEvent>> listener;
        private final Map<EventKey, FileWatchEvent> pending = new LinkedHashMap<>();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private FileWatchBackend.Watch watch;
        private boolean flushScheduled;

        Registration(Path root, boolean recursive, long coalesceMillis,
                Consumer<List<FileWatchEvent>> listener) {
            this.root = root;
            this.recursive = recursive;
            this.coalesceMillis = coalesceMillis;
            this.listener = listener;
        }

        void start() throws IOException {
            this.watch = backend.watch(root, recursive, this::onEvent);
        }

        /** Backend-thread entry: buffer and (re)schedule a coalesced flush. */
        private void onEvent(FileWatchEvent event) {
            if (!active.get() || event == null) {
                return;
            }
            synchronized (this) {
                pending.merge(new EventKey(event.path(), event.kind()), event, (a, b) -> b);
                if (flushScheduled) {
                    return;
                }
                flushScheduled = true;
                try {
                    if (coalesceMillis <= 0) {
                        delivery.execute(this::flush);
                    } else {
                        delivery.schedule(this::flush, coalesceMillis, TimeUnit.MILLISECONDS);
                    }
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    flushScheduled = false; // delivery shut down mid-flight; drop quietly
                }
            }
        }

        private void flush() {
            List<FileWatchEvent> batch;
            synchronized (this) {
                flushScheduled = false;
                if (pending.isEmpty()) {
                    return;
                }
                batch = List.copyOf(pending.values());
                pending.clear();
            }
            if (!active.get()) {
                return;
            }
            try {
                listener.accept(batch);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "File watch listener threw for " + root, t);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            closeWatchQuietly();
            registrations.remove(this);
        }

        void stopQuietly() {
            active.set(false);
            closeWatchQuietly();
        }

        private void closeWatchQuietly() {
            try {
                if (watch != null) {
                    watch.close();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "File watch close failed for " + root, e);
            }
        }
    }

    private record EventKey(Path path, FileWatchEvent.Kind kind) {
    }
}
