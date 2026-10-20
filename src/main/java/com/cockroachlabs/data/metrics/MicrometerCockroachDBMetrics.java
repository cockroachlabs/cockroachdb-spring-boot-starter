package com.cockroachlabs.data.metrics;

import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class MicrometerCockroachDBMetrics implements CockroachDBMetrics {

    private final Counter txTotal;
    private final Counter txSuccess;
    private final Counter txFailure;
    private final Counter retryTotal;
    private final Timer txDuration;

    public MicrometerCockroachDBMetrics(MeterRegistry registry) {
        this.txTotal = Counter.builder("cockroachdb.transactions.total")
                .description("Total CockroachDB transactions")
                .register(registry);
        this.txSuccess = Counter.builder("cockroachdb.transactions.success")
                .description("Successful transactions")
                .register(registry);
        this.txFailure = Counter.builder("cockroachdb.transactions.failure")
                .description("Failed transactions after all retries")
                .register(registry);
        this.retryTotal = Counter.builder("cockroachdb.transactions.retries")
                .description("Total retry attempts")
                .register(registry);
        this.txDuration = Timer.builder("cockroachdb.transactions.duration")
                .description("Transaction duration including retries")
                .register(registry);
    }

    @Override
    public void recordTransaction(long startNanos, int retries, boolean success) {
        txTotal.increment();
        txDuration.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        if (success) {
            txSuccess.increment();
        } else {
            txFailure.increment();
        }
        if (retries > 0) {
            retryTotal.increment(retries);
        }
    }

    @Override
    public long getTransactionCount() {
        return (long) txTotal.count();
    }

    @Override
    public long getRetryCount() {
        return (long) retryTotal.count();
    }

    @Override
    public long getFailureCount() {
        return (long) txFailure.count();
    }

    @Override
    public double getAverageDurationMs() {
        double count = txTotal.count();
        if (count == 0) return 0;
        return txDuration.totalTime(TimeUnit.MILLISECONDS) / count;
    }
}
