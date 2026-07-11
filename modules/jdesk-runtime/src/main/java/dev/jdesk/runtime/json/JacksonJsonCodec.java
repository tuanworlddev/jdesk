package dev.jdesk.runtime.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.internal.DefensiveJson;
import java.nio.charset.StandardCharsets;

/**
 * Default codec, backed by the defensively configured shared mapper: no default typing,
 * bounded depth/string/number sizes, strict duplicate keys, deterministic ordering.
 * Jackson types never leak through this class's API.
 */
public final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper mapper;
    private final JsonLimits limits;

    public JacksonJsonCodec() {
        this(JsonLimits.DEFAULTS);
    }

    public JacksonJsonCodec(JsonLimits limits) {
        this.limits = limits;
        this.mapper = DefensiveJson.build(limits);
    }

    @Override
    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new JDeskException(ErrorCode.SERIALIZATION_ERROR, "Failed to encode value", e);
        }
    }

    @Override
    public <T> T decode(String json, Class<T> type) {
        if (json.getBytes(StandardCharsets.UTF_8).length > limits.maxTotalBytes()) {
            throw new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE, "JSON document too large");
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new JDeskException(ErrorCode.SERIALIZATION_ERROR, "Failed to decode value", e);
        }
    }

    @Override
    public JsonLimits limits() {
        return limits;
    }
}
