package com.cockroachlabs.data.cdc;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * One row change in a changefeed webhook message. Jackson leaves any field the message omits, or
 * sends as {@code null}, as {@code null}, and a {@code NULL} column arrives as a {@code null} map
 * value, so every reference here may be {@code null}.
 *
 * @param after the row after the change; {@code null} or empty for a delete
 * @param before the row before the change, when the changefeed sends it ({@code diff})
 * @param key the primary key values
 * @param topic the changefeed's topic, normally the table name
 * @param updated the change's MVCC timestamp, when the changefeed sends it ({@code updated})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CDCPayload(
        @Nullable Map<String, @Nullable Object> after,
        @Nullable Map<String, @Nullable Object> before,
        @Nullable List<@Nullable Object> key,
        @Nullable String topic,
        @Nullable String updated
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
