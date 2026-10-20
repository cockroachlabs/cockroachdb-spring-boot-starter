package com.cockroachlabs.data.cdc;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CDCEnvelope(
        List<CDCPayload> payload,
        int length,
        String resolved
) {

    public boolean isResolved() {
        return resolved != null && !resolved.isBlank();
    }

    public boolean hasPayload() {
        return payload != null && !payload.isEmpty();
    }
}
