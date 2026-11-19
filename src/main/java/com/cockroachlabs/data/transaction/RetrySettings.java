package com.cockroachlabs.data.transaction;

import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;

/**
 * How a transaction that fails with SQLSTATE {@code 40001} is retried: up to
 * {@code maxRetries} times, waiting {@code delay} ms before the first retry, multiplying the
 * wait by {@code multiplier} after each one, never waiting longer than {@code maxDelay} ms,
 * and adding up to {@code jitter} ms of randomness either way.
 */
public record RetrySettings(int maxRetries, long delay, double multiplier, long maxDelay, long jitter) {

    public RetrySettings {
        requireNonNegative("maxRetries", maxRetries);
        requireNonNegative("delay", delay);
        requireNonNegative("multiplier", multiplier);
        requireNonNegative("maxDelay", maxDelay);
        requireNonNegative("jitter", jitter);
    }

    /** The {@code cockroachdb.retry.*} settings. */
    static RetrySettings from(CockroachDBProperties.Retry retry) {
        return new RetrySettings(retry.getMaxRetries(), retry.getDelay(), retry.getMultiplier(),
                retry.getMaxDelay(), retry.getJitter());
    }

    public RetrySettings withMaxRetries(int maxRetries) {
        return new RetrySettings(maxRetries, delay, multiplier, maxDelay, jitter);
    }

    public RetrySettings withDelay(long delay) {
        return new RetrySettings(maxRetries, delay, multiplier, maxDelay, jitter);
    }

    public RetrySettings withMultiplier(double multiplier) {
        return new RetrySettings(maxRetries, delay, multiplier, maxDelay, jitter);
    }

    public RetrySettings withMaxDelay(long maxDelay) {
        return new RetrySettings(maxRetries, delay, multiplier, maxDelay, jitter);
    }

    private static void requireNonNegative(String name, double value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }
}
