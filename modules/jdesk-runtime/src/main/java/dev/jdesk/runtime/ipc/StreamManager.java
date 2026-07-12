package dev.jdesk.runtime.ipc;

import dev.jdesk.api.BinaryStream;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-navigation pull stream registry. A pull is the sole source of demand. */
public final class StreamManager implements AutoCloseable {
    static final int MAX_CHUNK_BYTES = 256 * 1024;
    static final int MAX_ACTIVE_STREAMS = 32;
    public record Descriptor(String streamId, long length, String contentType, String fileName) { }
    public record Pull(String streamId, int maxBytes) { }
    public record Chunk(String streamId, long offset, String data, boolean eof) { }
    public record Cancel(String streamId) { }

    private final Map<String, Active> streams = new ConcurrentHashMap<>();

    synchronized Descriptor register(BinaryStream stream) {
        if (streams.size() >= MAX_ACTIVE_STREAMS) {
            throw new JDeskException(ErrorCode.LIMIT_EXCEEDED,
                    "Too many active binary streams");
        }
        try {
            String id = UUID.randomUUID().toString();
            streams.put(id, new Active(stream.source().open()));
            return new Descriptor(id, stream.length(), stream.contentType(), stream.fileName());
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Could not open binary stream");
        }
    }

    Chunk pull(Pull pull) {
        if (pull.maxBytes() < 1 || pull.maxBytes() > MAX_CHUNK_BYTES) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Invalid stream chunk size");
        }
        Active active = streams.get(pull.streamId());
        if (active == null) throw new JDeskException(ErrorCode.INVALID_REQUEST, "Unknown stream");
        synchronized (active) {
            try {
                byte[] bytes = active.input.readNBytes(pull.maxBytes());
                long offset = active.offset;
                active.offset += bytes.length;
                boolean eof = bytes.length == 0;
                if (eof) close(pull.streamId());
                return new Chunk(pull.streamId(), offset,
                        Base64.getEncoder().encodeToString(bytes), eof);
            } catch (IOException e) {
                close(pull.streamId());
                throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Binary stream read failed");
            }
        }
    }

    void cancel(String id) { close(id); }
    private void close(String id) {
        Active active = streams.remove(id);
        if (active != null) try { active.input.close(); } catch (IOException ignored) { }
    }
    int size() { return streams.size(); }
    @Override public void close() { streams.keySet().forEach(this::close); }
    private static final class Active { final InputStream input; long offset; Active(InputStream i){input=i;} }
}
