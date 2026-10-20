package com.cockroachlabs.data.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public interface CockroachDBMetrics {

    void recordTransaction(long startNanos, int retries, boolean success);

    long getTransactionCount();

    long getRetryCount();

    long getFailureCount();

    double getAverageDurationMs();

    default void recordFollowerRead(long startNanos, boolean success) {}

    default long getFollowerReadCount() { return 0; }

    class Noop implements CockroachDBMetrics {
        @Override
        public void recordTransaction(long startNanos, int retries, boolean success) {}

        @Override
        public long getTransactionCount() { return 0; }

        @Override
        public long getRetryCount() { return 0; }

        @Override
        public long getFailureCount() { return 0; }

        @Override
        public double getAverageDurationMs() { return 0; }
    }

    class InMemory implements CockroachDBMetrics {
        private final AtomicLong txCount = new AtomicLong();
        private final AtomicLong retryCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();
        private final AtomicLong totalDurationNanos = new AtomicLong();

        @Override
        public void recordTransaction(long startNanos, int retries, boolean success) {
            txCount.incrementAndGet();
            totalDurationNanos.addAndGet(System.nanoTime() - startNanos);
            if (retries > 0) retryCount.addAndGet(retries);
            if (!success) failureCount.incrementAndGet();
        }

        @Override
        public long getTransactionCount() { return txCount.get(); }

        @Override
        public long getRetryCount() { return retryCount.get(); }

        @Override
        public long getFailureCount() { return failureCount.get(); }

        @Override
        public double getAverageDurationMs() {
            long count = txCount.get();
            if (count == 0) return 0;
            return TimeUnit.NANOSECONDS.toMillis(totalDurationNanos.get()) / (double) count;
        }

        private final AtomicLong followerReadCount = new AtomicLong();

        @Override
        public void recordFollowerRead(long startNanos, boolean success) {
            followerReadCount.incrementAndGet();
            totalDurationNanos.addAndGet(System.nanoTime() - startNanos);
        }

        @Override
        public long getFollowerReadCount() { return followerReadCount.get(); }
    }
}
