package com.cockroachlabs.data.test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CockroachDBConcurrencyRunner {

    private CockroachDBConcurrencyRunner() {}

    public static Result run(int threads, int iterations, Runnable action) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        AtomicLong successCount = new AtomicLong();
        AtomicLong totalOps = new AtomicLong();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        long startTime = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        try {
                            action.run();
                            successCount.incrementAndGet();
                        } catch (Throwable ex) {
                            errors.add(ex);
                        }
                        totalOps.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            done.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        return new Result(
                threads,
                iterations,
                totalOps.get(),
                successCount.get(),
                errors.size(),
                durationMs,
                errors
        );
    }

    public record Result(
            int threads,
            int iterationsPerThread,
            long totalOperations,
            long successes,
            long failures,
            long durationMs,
            java.util.List<Throwable> errors
    ) {
        public double opsPerSecond() {
            if (durationMs == 0) return 0;
            return (totalOperations * 1000.0) / durationMs;
        }
    }
}
