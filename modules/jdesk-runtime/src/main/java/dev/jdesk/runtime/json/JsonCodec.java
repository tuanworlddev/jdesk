package dev.jdesk.runtime.json;

import dev.jdesk.api.JDeskException;

/**
 * Small serialization SPI. Implementations must disable polymorphic/default typing,
 * enforce {@link JsonLimits}, produce stable UTF-8, and map every failure to a
 * {@link JDeskException} with a deterministic {@link dev.jdesk.api.ErrorCode}.
 */
public interface JsonCodec {
    String encode(Object value);

    <T> T decode(String json, Class<T> type);

    JsonLimits limits();
}
