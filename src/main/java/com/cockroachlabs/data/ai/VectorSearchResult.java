package com.cockroachlabs.data.ai;

import java.util.Map;

public record VectorSearchResult<T>(
        T id,
        double distance,
        Map<String, Object> metadata
) {

    public VectorSearchResult(T id, double distance) {
        this(id, distance, Map.of());
    }
}
