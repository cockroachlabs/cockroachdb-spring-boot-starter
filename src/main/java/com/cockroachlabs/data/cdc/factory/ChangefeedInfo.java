package com.cockroachlabs.data.cdc.factory;

import java.time.Instant;

public record ChangefeedInfo(
        long jobId,
        String status,
        String description,
        String sinkUri,
        Instant created,
        Instant modified
) {}
