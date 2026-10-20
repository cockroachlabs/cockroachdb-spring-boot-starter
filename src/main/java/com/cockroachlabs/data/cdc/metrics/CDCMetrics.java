package com.cockroachlabs.data.cdc.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.cockroachlabs.data.cdc.CDCOperationType;

public interface CDCMetrics {

    void recordEvent(String endpoint, CDCOperationType operation, long processingNanos, boolean success);

    void recordWebhookReceived(String endpoint, int payloadSize);

    void recordResolved(String endpoint);

    void recordAuthFailure(String endpoint);

    long getEventsReceived();

    long getEventsProcessed();

    long getEventsFailed();

    long getResolvedCount();

    long getAuthFailureCount();

    double getAverageProcessingMs();

    class Noop implements CDCMetrics {
        @Override public void recordEvent(String e, CDCOperationType o, long n, boolean s) {}
        @Override public void recordWebhookReceived(String e, int s) {}
        @Override public void recordResolved(String e) {}
        @Override public void recordAuthFailure(String e) {}
        @Override public long getEventsReceived() { return 0; }
        @Override public long getEventsProcessed() { return 0; }
        @Override public long getEventsFailed() { return 0; }
        @Override public long getResolvedCount() { return 0; }
        @Override public long getAuthFailureCount() { return 0; }
        @Override public double getAverageProcessingMs() { return 0; }
    }

    class InMemory implements CDCMetrics {
        private final AtomicLong webhooksReceived = new AtomicLong();
        private final AtomicLong eventsProcessed = new AtomicLong();
        private final AtomicLong eventsFailed = new AtomicLong();
        private final AtomicLong resolvedCount = new AtomicLong();
        private final AtomicLong authFailures = new AtomicLong();
        private final AtomicLong totalProcessingNanos = new AtomicLong();

        private final Map<String, AtomicLong> perEndpointReceived = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> perEndpointProcessed = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> perEndpointFailed = new ConcurrentHashMap<>();

        @Override
        public void recordEvent(String endpoint, CDCOperationType operation,
                                long processingNanos, boolean success) {
            totalProcessingNanos.addAndGet(processingNanos);
            if (success) {
                eventsProcessed.incrementAndGet();
                perEndpointProcessed.computeIfAbsent(endpoint, k -> new AtomicLong()).incrementAndGet();
            } else {
                eventsFailed.incrementAndGet();
                perEndpointFailed.computeIfAbsent(endpoint, k -> new AtomicLong()).incrementAndGet();
            }
        }

        @Override
        public void recordWebhookReceived(String endpoint, int payloadSize) {
            webhooksReceived.incrementAndGet();
            perEndpointReceived.computeIfAbsent(endpoint, k -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void recordResolved(String endpoint) {
            resolvedCount.incrementAndGet();
        }

        @Override
        public void recordAuthFailure(String endpoint) {
            authFailures.incrementAndGet();
        }

        @Override
        public long getEventsReceived() { return webhooksReceived.get(); }

        @Override
        public long getEventsProcessed() { return eventsProcessed.get(); }

        @Override
        public long getEventsFailed() { return eventsFailed.get(); }

        @Override
        public long getResolvedCount() { return resolvedCount.get(); }

        @Override
        public long getAuthFailureCount() { return authFailures.get(); }

        @Override
        public double getAverageProcessingMs() {
            long count = eventsProcessed.get() + eventsFailed.get();
            if (count == 0) return 0;
            return TimeUnit.NANOSECONDS.toMillis(totalProcessingNanos.get()) / (double) count;
        }

        public Map<String, AtomicLong> getPerEndpointReceived() { return Map.copyOf(perEndpointReceived); }
        public Map<String, AtomicLong> getPerEndpointProcessed() { return Map.copyOf(perEndpointProcessed); }
        public Map<String, AtomicLong> getPerEndpointFailed() { return Map.copyOf(perEndpointFailed); }
    }
}
