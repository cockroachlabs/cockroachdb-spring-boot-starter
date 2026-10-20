package com.cockroachlabs.data.cdc.metrics;

import java.util.concurrent.TimeUnit;
import com.cockroachlabs.data.cdc.CDCOperationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

public class MicrometerCDCMetrics implements CDCMetrics {

    private final MeterRegistry registry;
    private final Counter webhooksReceived;
    private final Counter eventsProcessed;
    private final Counter eventsFailed;
    private final Counter resolvedCount;
    private final Counter authFailures;
    private final Timer processingDuration;

    public MicrometerCDCMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.webhooksReceived = Counter.builder("cockroachdb.cdc.webhooks.received")
                .description("Total CDC webhook requests received")
                .register(registry);
        this.eventsProcessed = Counter.builder("cockroachdb.cdc.events.processed")
                .description("Successfully processed CDC events")
                .register(registry);
        this.eventsFailed = Counter.builder("cockroachdb.cdc.events.failed")
                .description("Failed CDC event processing attempts")
                .register(registry);
        this.resolvedCount = Counter.builder("cockroachdb.cdc.resolved")
                .description("CDC resolved timestamps received")
                .register(registry);
        this.authFailures = Counter.builder("cockroachdb.cdc.auth.failures")
                .description("CDC webhook authentication failures")
                .register(registry);
        this.processingDuration = Timer.builder("cockroachdb.cdc.processing.duration")
                .description("CDC event processing duration")
                .register(registry);
    }

    @Override
    public void recordEvent(String endpoint, CDCOperationType operation,
                            long processingNanos, boolean success) {
        Tags tags = Tags.of(
                "endpoint", endpoint,
                "operation", operation.name().toLowerCase(),
                "status", success ? "success" : "failure");

        Counter.builder("cockroachdb.cdc.events")
                .tags(tags)
                .register(registry)
                .increment();

        processingDuration.record(processingNanos, TimeUnit.NANOSECONDS);

        if (success) {
            eventsProcessed.increment();
        } else {
            eventsFailed.increment();
        }
    }

    @Override
    public void recordWebhookReceived(String endpoint, int payloadSize) {
        webhooksReceived.increment();
        Counter.builder("cockroachdb.cdc.webhooks.received.endpoint")
                .tag("endpoint", endpoint)
                .register(registry)
                .increment();
        registry.summary("cockroachdb.cdc.webhooks.payload.size",
                Tags.of("endpoint", endpoint)).record(payloadSize);
    }

    @Override
    public void recordResolved(String endpoint) {
        resolvedCount.increment();
        Counter.builder("cockroachdb.cdc.resolved.endpoint")
                .tag("endpoint", endpoint)
                .register(registry)
                .increment();
    }

    @Override
    public void recordAuthFailure(String endpoint) {
        authFailures.increment();
        Counter.builder("cockroachdb.cdc.auth.failures.endpoint")
                .tag("endpoint", endpoint)
                .register(registry)
                .increment();
    }

    @Override
    public long getEventsReceived() {
        return (long) webhooksReceived.count();
    }

    @Override
    public long getEventsProcessed() {
        return (long) eventsProcessed.count();
    }

    @Override
    public long getEventsFailed() {
        return (long) eventsFailed.count();
    }

    @Override
    public long getResolvedCount() {
        return (long) resolvedCount.count();
    }

    @Override
    public long getAuthFailureCount() {
        return (long) authFailures.count();
    }

    @Override
    public double getAverageProcessingMs() {
        double count = eventsProcessed.count() + eventsFailed.count();
        if (count == 0) return 0;
        return processingDuration.totalTime(TimeUnit.MILLISECONDS) / count;
    }
}
