package dev.jdesk.instance;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

public final class SingleInstance {
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;

    private SingleInstance() {
    }

    public static SingleInstanceResult acquire(String appId, Path stateDirectory,
            List<String> arguments, Consumer<List<String>> listener)
            throws SingleInstanceException {
        if (appId == null || !appId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new SingleInstanceException("Invalid application id");
        }
        Path directory = stateDirectory.toAbsolutePath().normalize();
        FileChannel channel = null;
        FileLock lock = null;
        ServerSocket server = null;
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new SingleInstanceException("State directory must not be a symlink");
            }
            Files.createDirectories(directory);
            restrictToOwner(directory);
            Path lockPath = directory.resolve(appId + ".lock");
            Path state = directory.resolve(appId + ".properties");
            if (Files.isSymbolicLink(lockPath) || Files.isSymbolicLink(state)) {
                throw new SingleInstanceException("Single-instance state must not be a symlink");
            }
            channel = FileChannel.open(lockPath, LinkOption.NOFOLLOW_LINKS,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            restrictToOwner(lockPath);
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                channel = null;
                handoff(state, arguments);
                return new SingleInstanceResult(false, Optional.empty());
            }

            byte[] token = new byte[32];
            new SecureRandom().nextBytes(token);
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            writeState(state, server.getLocalPort(), token);
            SingleInstanceSession session = new SingleInstanceSession(
                    channel, lock, server, token, listener, state);
            channel = null;
            lock = null;
            server = null;
            return new SingleInstanceResult(true, Optional.of(session));
        } catch (SingleInstanceException e) {
            closePartial(server, lock, channel);
            throw e;
        } catch (IOException | RuntimeException e) {
            closePartial(server, lock, channel);
            throw new SingleInstanceException("Single-instance coordination failed", e);
        }
    }

    private static void handoff(Path state, List<String> arguments)
            throws SingleInstanceException {
        if (arguments.size() > SingleInstanceSession.MAX_ARGS) {
            throw new SingleInstanceException("Too many handoff arguments");
        }
        try {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(state, LinkOption.NOFOLLOW_LINKS)) {
                properties.load(input);
            }
            int port = Integer.parseInt(properties.getProperty("port"));
            byte[] token = Base64.getDecoder().decode(properties.getProperty("token"));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        CONNECT_TIMEOUT_MILLIS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
                try (DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    output.writeInt(SingleInstanceSession.MAGIC);
                    output.writeShort(token.length);
                    output.write(token);
                    output.writeShort(arguments.size());
                    for (String argument : arguments) {
                        byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > SingleInstanceSession.MAX_ARG_BYTES) {
                            throw new SingleInstanceException("Handoff argument too large");
                        }
                        output.writeInt(bytes.length);
                        output.write(bytes);
                    }
                }
            }
        } catch (SingleInstanceException e) {
            throw e;
        } catch (Exception e) {
            throw new SingleInstanceException("Could not notify primary instance", e);
        }
    }

    private static void writeState(Path state, int port, byte[] token) throws IOException {
        Path temporary = state.resolveSibling(state.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Files.createFile(temporary);
        restrictToOwner(temporary);
        Properties properties = new Properties();
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("token", Base64.getEncoder().encodeToString(token));
        try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE)) {
            properties.store(output, "JDesk single-instance state");
        }
        Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            acl.setAcl(List.of(ownerOnly));
            return;
        }
        throw new IOException("Filesystem cannot enforce owner-only permissions: " + path);
    }

    private static void closePartial(ServerSocket server, FileLock lock, FileChannel channel) {
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }
}
