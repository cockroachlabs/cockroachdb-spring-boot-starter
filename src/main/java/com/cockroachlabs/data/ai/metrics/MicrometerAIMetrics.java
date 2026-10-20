package com.cockroachlabs.data.ai.metrics;

import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class MicrometerAIMetrics implements AIMetrics {

    private final Counter searchTotal;
    private final Counter searchSuccess;
    private final Counter searchFailure;
    private final Counter resultsTotal;
    private final Timer searchDuration;

    public MicrometerAIMetrics(MeterRegistry registry) {
        this.searchTotal = Counter.builder("cockroachdb.ai.vector.searches.total")
                .description("Total vector searches executed")
                .register(registry);
        this.searchSuccess = Counter.builder("cockroachdb.ai.vector.searches.success")
                .description("Successful vector searches")
                .register(registry);
        this.searchFailure = Counter.builder("cockroachdb.ai.vector.searches.failure")
                .description("Failed vector searches")
                .register(registry);
        this.resultsTotal = Counter.builder("cockroachdb.ai.vector.results.total")
                .description("Total vector search results returned")
                .register(registry);
        this.searchDuration = Timer.builder("cockroachdb.ai.vector.searches.duration")
                .description("Vector search duration")
                .register(registry);
    }

    @Override
    public void recordVectorSearch(long startNanos, int resultCount, boolean success) {
        searchTotal.increment();
        searchDuration.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        resultsTotal.increment(resultCount);
        if (success) {
            searchSuccess.increment();
        } else {
            searchFailure.increment();
        }
    }

    @Override
    public long getVectorSearchCount() {
        return (long) searchTotal.count();
    }

    @Override
    public long getVectorSearchFailureCount() {
        return (long) searchFailure.count();
    }

    @Override
    public double getAverageVectorSearchDurationMs() {
        double count = searchTotal.count();
        if (count == 0) return 0;
        return searchDuration.totalTime(TimeUnit.MILLISECONDS) / count;
    }
}
