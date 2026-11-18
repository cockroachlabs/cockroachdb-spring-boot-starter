package com.cockroachlabs.data.template;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The operations of {@link CockroachDBTransactionTemplate}, in the same way that
 * {@link TransactionOperations} holds the operations of {@code TransactionTemplate}.
 * <p>
 * Implementations run each callback in a transaction and retry it, in a fresh transaction,
 * when CockroachDB reports a serialization conflict (SQLSTATE {@code 40001}). Inject this
 * interface where code only needs to run transactions; configuration (isolation, retry
 * settings, ...) stays on {@link CockroachDBTransactionTemplate}.
 */
public interface CockroachDBTransactionOperations extends TransactionOperations {

    /**
     * Like {@link #execute(TransactionCallback)}, for a callback that doesn't need the
     * {@code TransactionStatus}.
     */
    default <T> T execute(Supplier<T> action) {
        return execute(status -> action.get());
    }

    /**
     * Like {@link #executeWithoutResult(java.util.function.Consumer)}, for a callback that
     * doesn't need the {@code TransactionStatus}.
     */
    default void executeWithoutResult(Runnable action) {
        execute(status -> {
            action.run();
            return null;
        });
    }

    /**
     * Operations that run {@code SET crdb_region} to {@code region} at the start of each
     * transaction, overriding {@code RegionContext}, the {@code RegionResolver} and the
     * default region. This instance is not changed.
     *
     * @throws IllegalArgumentException if the region is malformed or not allowed
     */
    CockroachDBTransactionOperations inRegion(String region);
}
