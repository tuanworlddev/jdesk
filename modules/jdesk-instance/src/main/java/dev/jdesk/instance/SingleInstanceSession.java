package dev.jdesk.instance;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class SingleInstanceSession implements AutoCloseable {
    static final int MAGIC = 0x4a444553;
    static final int MAX_ARGS = 64;
    static final int MAX_ARG_BYTES = 16 * 1024;
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocket server;
    private final byte[] token;
    private final Consumer<List<String>> listener;
    private final ExecutorService executor;
    private final Path state;
    private final java.util.concurrent.atomic.AtomicBoolean resourcesClosed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    SingleInstanceSession(FileChannel lockChannel, FileLock lock, ServerSocket server,
            byte[] token, Consumer<List<String>> listener, Path state) {
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        this.token = token.clone();
        this.listener = listener;
        this.state = state;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                executor.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!closed) {
                    closed = true;
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket; DataInputStream input = new DataInputStream(socket.getInputStream())) {
            if (!socket.getInetAddress().isLoopbackAddress() || input.readInt() != MAGIC) {
                return;
            }
            int tokenLength = input.readUnsignedShort();
            if (tokenLength != token.length) {
                return;
            }
            byte[] supplied = input.readNBytes(tokenLength);
            if (!java.security.MessageDigest.isEqual(token, supplied)) {
                return;
            }
            int count = input.readUnsignedShort();
            if (count > MAX_ARGS) {
                return;
            }
            List<String> arguments = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length < 0 || length > MAX_ARG_BYTES) {
                    return;
                }
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) {
                    return;
                }
                arguments.add(new String(bytes, StandardCharsets.UTF_8));
            }
            listener.accept(List.copyOf(arguments));
        } catch (IOException | RuntimeException ignored) {
            // Invalid and abandoned loopback clients are isolated from the accept loop.
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public String tokenBase64() {
        return Base64.getEncoder().encodeToString(token);
    }

    @Override
    public void close() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
        try {
            Files.deleteIfExists(state);
        } catch (IOException ignored) {
        }
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        try {
            lockChannel.close();
        } catch (IOException ignored) {
        }
    }
}
