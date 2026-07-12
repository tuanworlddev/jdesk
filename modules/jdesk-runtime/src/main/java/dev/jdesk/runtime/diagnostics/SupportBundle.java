package dev.jdesk.runtime.diagnostics;

import dev.jdesk.api.JDeskVersion;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
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
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a bounded, redacted ZIP that a user can explicitly attach to a support case. */
public final class SupportBundle {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|token|secret|password|api[-_]?key)"
                    + "(\\s*[:=]\\s*)([^\\r\\n,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

    private SupportBundle() {
    }

    public static Path create(Path output, SupportBundleOptions options) throws IOException {
        java.util.Objects.requireNonNull(options, "options");
        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Support bundle requires a parent directory");
        }
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(target)
                || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Support bundle target must be a regular file");
        }
        Path temporary = parent.resolve("." + target.getFileName() + "-" + UUID.randomUUID());
        try {
            writeBundle(temporary, options);
            moveReplace(temporary, target);
            return target;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private static void writeBundle(Path output, SupportBundleOptions options)
            throws IOException {
        Files.createFile(output);
        restrictToOwner(output);
        try (OutputStream file = Files.newOutputStream(output,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            writeEntry(zip, "system.json", systemJson(options));
            long remaining = options.maxTotalLogBytes();
            int index = 0;
            for (Path log : options.logFiles().stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                if (remaining == 0) {
                    break;
                }
                if (Files.isSymbolicLink(log)
                        || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long limit = Math.min(options.maxBytesPerLog(), remaining);
                byte[] bytes = readTail(log, limit);
                byte[] redacted = redact(new String(bytes, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
                if (redacted.length > limit) {
                    redacted = java.util.Arrays.copyOfRange(redacted,
                            redacted.length - Math.toIntExact(limit), redacted.length);
                }
                remaining -= redacted.length;
                writeEntry(zip, "logs/log-" + String.format(Locale.ROOT, "%02d", ++index)
                        + ".txt", redacted);
            }
        }
    }

    private static byte[] readTail(Path file, long limit) throws IOException {
        long size = Files.size(file);
        int length = Math.toIntExact(Math.min(size, limit));
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
            channel.position(Math.max(0, size - length));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Fill the bounded tail buffer.
            }
        }
        return buffer.array();
    }

    private static byte[] systemJson(SupportBundleOptions options) {
        Runtime runtime = Runtime.getRuntime();
        String json = """
                {
                  "schemaVersion": 1,
                  "applicationId": "%s",
                  "applicationVersion": "%s",
                  "jdeskVersion": "%s",
                  "protocolVersion": %d,
                  "osName": "%s",
                  "osVersion": "%s",
                  "osArchitecture": "%s",
                  "javaVersion": "%s",
                  "javaVendor": "%s",
                  "locale": "%s",
                  "timeZone": "%s",
                  "maxHeapBytes": %d,
                  "availableProcessors": %d
                }
                """.formatted(escape(options.applicationId()),
                escape(options.applicationVersion()), escape(JDeskVersion.current()),
                JDeskVersion.PROTOCOL_VERSION,
                escape(System.getProperty("os.name", "unknown")),
                escape(System.getProperty("os.version", "unknown")),
                escape(System.getProperty("os.arch", "unknown")),
                escape(System.getProperty("java.version", "unknown")),
                escape(System.getProperty("java.vendor", "unknown")),
                escape(Locale.getDefault().toLanguageTag()), escape(ZoneId.systemDefault().getId()),
                runtime.maxMemory(), runtime.availableProcessors());
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static String redact(String input) {
        String redacted = BEARER.matcher(input).replaceAll("Bearer <redacted>");
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1$2<redacted>");
        redacted = JWT.matcher(redacted).replaceAll("<jwt-redacted>");
        redacted = replacePath(redacted, System.getProperty("user.home"), "<home>");
        redacted = replacePath(redacted, System.getProperty("java.io.tmpdir"), "<tmp>");
        return redacted;
    }

    private static String replacePath(String input, String path, String replacement) {
        return path == null || path.isBlank() ? input : input.replace(path, replacement);
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(java.util.List.of(ownerOnly));
            return;
        }
        throw new IOException("Filesystem cannot enforce owner-only permissions: " + path);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
