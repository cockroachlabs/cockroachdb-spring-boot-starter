package com.cockroachlabs.data.cdc;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CDCPayload(
        Map<String, Object> after,
        Map<String, Object> before,
        List<Object> key,
        String topic,
        String updated
) {

    public CDCOperationType resolveOperation() {
        if (after == null || after.isEmpty()) {
            return CDCOperationType.DELETE;
        }
        if (before != null && !before.isEmpty()) {
            return CDCOperationType.UPDATE;
        }
        return CDCOperationType.INSERT;
    }
}
