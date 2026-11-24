package com.cockroachlabs.data.cdc.factory;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ChangefeedInfo(
        long jobId,
        @Nullable String status,
        @Nullable String description,
        @Nullable String sinkUri,
        @Nullable Instant created,
        @Nullable Instant modified
) {}
