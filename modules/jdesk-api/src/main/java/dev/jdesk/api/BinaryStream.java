package dev.jdesk.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** A lazily opened binary response streamed to the renderer with pull backpressure. */
public final class BinaryStream {
    @FunctionalInterface
    public interface Source { InputStream open() throws IOException; }

    private final long length;
    private final String contentType;
    private final String fileName;
    private final Source source;

    public BinaryStream(long length, String contentType, String fileName, Source source) {
        if (length < 0) throw new IllegalArgumentException("length must be >= 0");
        this.length = length;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.source = Objects.requireNonNull(source, "source");
    }

    public static BinaryStream of(Path path, String contentType) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return new BinaryStream(Files.size(normalized), contentType,
                normalized.getFileName().toString(), () -> Files.newInputStream(normalized));
    }

    public long length() { return length; }
    public String contentType() { return contentType; }
    public String fileName() { return fileName; }
    public Source source() { return source; }
}
