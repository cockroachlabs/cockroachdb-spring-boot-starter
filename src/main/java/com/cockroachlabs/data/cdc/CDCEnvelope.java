package com.cockroachlabs.data.cdc;

import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * A changefeed webhook message: a batch of row changes, or a resolved timestamp.
 *
 * @param payload the row changes; empty for a resolved-timestamp message. Never {@code null}, and
 *        never holds {@code null}: a missing or {@code null} payload becomes an empty list, and
 *        {@code null} entries are dropped
 * @param length the number of row changes CockroachDB sent
 * @param resolved the resolved timestamp, or {@code null} for a message of row changes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CDCEnvelope(
        List<CDCPayload> payload,
        int length,
        @Nullable String resolved
) {

    public CDCEnvelope {
        payload = (payload != null) ? payload.stream().filter(Objects::nonNull).toList() : List.of();
    }

    public boolean isResolved() {
        return resolved != null && !resolved.isBlank();
    }

    public boolean hasPayload() {
        return !payload.isEmpty();
    }
}
