package com.cockroachlabs.data.transaction;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;

/**
 * The one retry loop behind {@code @CockroachDBTransactional}, retried {@code @Transactional}
 * methods and {@code CockroachDBTransactionTemplate}.
 * <p>
 * Each attempt must run in its own transaction. An attempt is retried when it fails with an
 * exception whose cause chain contains a {@link SQLException} with SQLSTATE {@code 40001},
 * whatever wraps it ({@code CannotAcquireLockException} under JDBC, {@code JpaSystemException}
 * or {@code TransactionSystemException} under JPA). Any other exception is rethrown at once.
 */
public final class SerializationRetry {

    /** One attempt of the work being retried. */
    @FunctionalInterface
    public interface Attempt<T> {
        T run() throws Throwable;
    }

    private final CockroachDBProperties.Retry defaults;
    private final CockroachDBMetrics metrics;

    public SerializationRetry(CockroachDBProperties.Retry defaults, CockroachDBMetrics metrics) {
        this.defaults = defaults;
        this.metrics = metrics;
    }

    /** The {@code cockroachdb.retry.*} settings. */
    public RetrySettings defaults() {
        return RetrySettings.from(defaults);
    }

    /**
     * Runs {@code attempt}, retrying it with backoff while it fails with SQLSTATE
     * {@code 40001}, up to {@code settings.maxRetries()} times. Records the outcome in the
     * transaction metrics.
     */
    public <T> T execute(RetrySettings settings, Attempt<T> attempt) throws Throwable {
        long start = System.nanoTime();
        long currentDelay = settings.delay();
        int retries = 0;

        while (true) {
            try {
                T result = attempt.run();
                metrics.recordTransaction(start, retries, true);
                return result;
            } catch (Throwable ex) {
                if (!isSerializationError(ex) || retries >= settings.maxRetries()) {
                    metrics.recordTransaction(start, retries, false);
                    throw ex;
                }
                retries++;
                sleep(currentDelay, settings.jitter());
                currentDelay = Math.min((long) (currentDelay * settings.multiplier()), settings.maxDelay());
            }
        }
    }

    /** True when any exception in the cause chain is a {@link SQLException} with SQLSTATE 40001. */
    static boolean isSerializationError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sql && "40001".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void sleep(long delay, long jitter) {
        long jitteredDelay = delay;
        if (jitter > 0) {
            jitteredDelay += ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        }
        try {
            Thread.sleep(Math.max(0, jitteredDelay));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a transaction", e);
        }
    }
}
