package com.cockroachlabs.data.transaction;

import java.util.List;
import java.util.regex.Pattern;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.multiregion.RegionResolver;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds the resolved region for each transaction that a {@code @Transactional},
 * {@code @CockroachDBTransactional} or {@code CockroachDBTransactionTemplate} call begins, so
 * REGIONAL BY ROW rows written in that transaction are homed to it.
 * <p>
 * Those callers {@linkplain #bind(String) bind} a scope on the current thread for the length of
 * the call. When a transaction begins inside that scope, this listener publishes the region to
 * {@link RegionContext} for the duration of the transaction; {@code RegionalByRowListener} then
 * stamps the entity's {@code @CrdbRegion} column on {@code @PrePersist}. (CockroachDB has no
 * {@code SET crdb_region} session variable, so the region is never applied as session SQL.) The
 * region is the first of:
 * <ol>
 *   <li>the region the caller bound explicitly ({@code @CockroachDBTransactional(region = ...)}
 *       or {@code CockroachDBTransactionTemplate.setRegion(...)});</li>
 *   <li>{@link RegionContext};</li>
 *   <li>the {@link RegionResolver} bean;</li>
 *   <li>{@code cockroachdb.region.default-region}.</li>
 * </ol>
 * No region, a suppressed scope, or a transaction begun outside such a scope leaves
 * {@link RegionContext} untouched. The starter registers this listener with every
 * {@code ConfigurableTransactionManager} bean.
 */
public class CockroachDBRegions implements TransactionExecutionListener {

    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** The innermost active scope on this thread; null when none. */
    private static final ThreadLocal<Binding> BINDING = new ThreadLocal<>();

    private final CockroachDBProperties.Region properties;
    private final RegionResolver regionResolver;

    public CockroachDBRegions(CockroachDBProperties.Region properties,
                              RegionResolver regionResolver) {
        this.properties = properties;
        this.regionResolver = regionResolver;
    }

    /** A scope opened by {@link #bind} or {@link #suppress}; closing it restores the outer scope. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record Binding(String explicitRegion, boolean suppressed) {
    }

    /**
     * Opens a scope in which transactions that begin get a session region: {@code explicitRegion}
     * when not {@code null}, otherwise the resolution chain. Scopes nest; close it in a
     * {@code finally} or try-with-resources.
     */
    public static Scope bind(String explicitRegion) {
        return push(new Binding(explicitRegion, false));
    }

    /**
     * Opens a scope in which transactions that begin get no session region, for transactions
     * that must not run any statement first (follower reads).
     */
    public static Scope suppress() {
        return push(new Binding(null, true));
    }

    private static Scope push(Binding binding) {
        Binding outer = BINDING.get();
        BINDING.set(binding);
        return () -> {
            if (outer == null) {
                BINDING.remove();
            } else {
                BINDING.set(outer);
            }
        };
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        Binding binding = BINDING.get();
        if (beginFailure != null || binding == null || binding.suppressed()) {
            return;
        }
        String region = resolve(binding.explicitRegion());
        if (region == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            // Nothing to apply, or no synchronization to scope the binding to. CockroachDB has no
            // `SET crdb_region` session variable, so region is never applied as session SQL.
            return;
        }
        // Home REGIONAL BY ROW rows by stamping the @CrdbRegion column: RegionalByRowListener reads
        // RegionContext on @PrePersist. Bind the region for THIS transaction only and restore the
        // previous value on completion, so a pooled connection/thread never leaks a region.
        String previous = RegionContext.current().orElse(null);
        RegionContext.set(region);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (previous != null) {
                    RegionContext.set(previous);
                } else {
                    RegionContext.clear();
                }
            }
        });
    }

    /** {@code explicitRegion} if not null, otherwise the resolution chain; validated; null for none. */
    private String resolve(String explicitRegion) {
        if (explicitRegion != null) {
            return validate(explicitRegion);
        }
        String context = RegionContext.current().filter(r -> !r.isBlank()).orElse(null);
        if (context != null) {
            return validate(context);
        }
        if (regionResolver != null) {
            String resolved = regionResolver.resolve();
            if (resolved != null && !resolved.isBlank()) {
                return validate(resolved);
            }
        }
        String fallback = properties.getDefaultRegion();
        return (fallback != null && !fallback.isBlank()) ? validate(fallback) : null;
    }

    /**
     * Checks that {@code region} is well formed and, when {@code cockroachdb.region.validate}
     * is on, in {@code cockroachdb.region.allowed}.
     *
     * @throws IllegalArgumentException if it isn't
     */
    public String validate(String region) {
        if (region == null || !REGION_PATTERN.matcher(region).matches()) {
            throw new IllegalArgumentException(
                    "Invalid region format: '" + region + "' (must match [a-z][a-z0-9-]*)");
        }
        if (properties.isValidate()) {
            List<String> allowed = properties.getAllowed();
            if (!allowed.isEmpty() && !allowed.contains(region)) {
                throw new IllegalArgumentException(
                        "Region '" + region + "' not in allowed list: " + allowed);
            }
        }
        return region;
    }
}
