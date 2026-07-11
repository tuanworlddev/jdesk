package dev.jdesk.runtime.internal;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.jdesk.runtime.json.JsonLimits;

/**
 * Single source of the defensively configured Jackson mapper (spec section 11): no
 * default typing (never enabled), strict duplicate keys, bounded depth/string/number,
 * deterministic property and map ordering. Not exported; Jackson types never appear in
 * public signatures of this module.
 */
public final class DefensiveJson {
    private DefensiveJson() {
    }

    public static ObjectMapper build(JsonLimits limits) {
        ObjectMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(MapperFeature.AUTO_DETECT_SETTERS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(limits.maxNestingDepth())
                .maxStringLength(limits.maxStringLength())
                .maxNumberLength(limits.maxNumberLength())
                .build());
        return mapper;
    }
}
