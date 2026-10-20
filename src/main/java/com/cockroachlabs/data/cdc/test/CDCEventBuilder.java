package com.cockroachlabs.data.cdc.test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.CDCEvent;
import com.cockroachlabs.data.cdc.CDCOperationType;
import com.cockroachlabs.data.cdc.CDCPayload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class CDCEventBuilder<T> {

    private CDCOperationType operation = CDCOperationType.INSERT;
    private T after;
    private T before;
    private List<Object> key = new ArrayList<>();
    private String table = "unknown";
    private Instant timestamp = Instant.now();

    private CDCEventBuilder() {}

    public static <T> CDCEventBuilder<T> create() {
        return new CDCEventBuilder<>();
    }

    public CDCEventBuilder<T> operation(CDCOperationType operation) {
        this.operation = operation;
        return this;
    }

    public CDCEventBuilder<T> after(T after) {
        this.after = after;
        return this;
    }

    public CDCEventBuilder<T> before(T before) {
        this.before = before;
        return this;
    }

    public CDCEventBuilder<T> key(Object... keyParts) {
        this.key = Arrays.asList(keyParts);
        return this;
    }

    public CDCEventBuilder<T> table(String table) {
        this.table = table;
        return this;
    }

    public CDCEventBuilder<T> timestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public CDCEvent<T> build() {
        return new CDCEvent<>(after, before, key, operation, table, timestamp);
    }

    public CDCEnvelope buildAsEnvelope(ObjectMapper objectMapper) {
        Map<String, Object> afterMap = after != null
                ? objectMapper.convertValue(after, mapType())
                : null;
        Map<String, Object> beforeMap = before != null
                ? objectMapper.convertValue(before, mapType())
                : null;

        String hlcTimestamp = timestamp != null
                ? timestamp.getEpochSecond() + ".0000000000"
                : null;

        CDCPayload payload = new CDCPayload(afterMap, beforeMap, key, table, hlcTimestamp);
        return new CDCEnvelope(List.of(payload), 1, null);
    }

    public String buildAsJson(ObjectMapper objectMapper) {
        CDCEnvelope envelope = buildAsEnvelope(objectMapper);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize CDC event to JSON", e);
        }
    }

    public String buildAsJson() {
        return buildAsJson(JsonMapper.builder().findAndAddModules().build());
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    public static CDCEnvelope resolvedEnvelope(Instant timestamp) {
        String hlc = timestamp.getEpochSecond() + ".0000000000";
        return new CDCEnvelope(null, 0, hlc);
    }

    public static <T> CDCEnvelope insertEnvelope(T entity, ObjectMapper objectMapper, Object... keyParts) {
        return CDCEventBuilder.<T>create()
                .operation(CDCOperationType.INSERT)
                .after(entity)
                .key(keyParts)
                .buildAsEnvelope(objectMapper);
    }

    public static <T> CDCEnvelope updateEnvelope(T before, T after, ObjectMapper objectMapper, Object... keyParts) {
        return CDCEventBuilder.<T>create()
                .operation(CDCOperationType.UPDATE)
                .before(before)
                .after(after)
                .key(keyParts)
                .buildAsEnvelope(objectMapper);
    }

    public static <T> CDCEnvelope deleteEnvelope(ObjectMapper objectMapper, Object... keyParts) {
        return CDCEventBuilder.<T>create()
                .operation(CDCOperationType.DELETE)
                .key(keyParts)
                .buildAsEnvelope(objectMapper);
    }
}
