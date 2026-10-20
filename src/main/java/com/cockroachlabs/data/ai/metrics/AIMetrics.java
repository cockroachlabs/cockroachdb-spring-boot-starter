package com.cockroachlabs.data.ai.metrics;

import java.util.concurrent.atomic.AtomicLong;

public interface AIMetrics {

    void recordVectorSearch(long startNanos, int resultCount, boolean success);

    long getVectorSearchCount();

    long getVectorSearchFailureCount();

    double getAverageVectorSearchDurationMs();

    class Noop implements AIMetrics {
        @Override
        public void recordVectorSearch(long startNanos, int resultCount, boolean success) {}

        @Override
        public long getVectorSearchCount() { return 0; }

        @Override
        public long getVectorSearchFailureCount() { return 0; }

        @Override
        public double getAverageVectorSearchDurationMs() { return 0; }
    }

    class InMemory implements AIMetrics {
        private final AtomicLong searchCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();
        private final AtomicLong totalDurationNanos = new AtomicLong();
        private final AtomicLong totalResults = new AtomicLong();

        @Override
        public void recordVectorSearch(long startNanos, int resultCount, boolean success) {
            searchCount.incrementAndGet();
            totalDurationNanos.addAndGet(System.nanoTime() - startNanos);
            totalResults.addAndGet(resultCount);
            if (!success) failureCount.incrementAndGet();
        }

        @Override
        public long getVectorSearchCount() { return searchCount.get(); }

        @Override
        public long getVectorSearchFailureCount() { return failureCount.get(); }

        @Override
        public double getAverageVectorSearchDurationMs() {
            long count = searchCount.get();
            if (count == 0) return 0;
            return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(totalDurationNanos.get()) / (double) count;
        }

        public long getTotalResults() { return totalResults.get(); }
    }
}
