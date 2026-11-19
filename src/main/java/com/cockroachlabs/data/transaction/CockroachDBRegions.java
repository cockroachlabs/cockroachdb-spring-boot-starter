package com.cockroachlabs.data.transaction;

import java.util.List;
import java.util.regex.Pattern;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.multiregion.RegionResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Sets the session region ({@code SET crdb_region}) at the start of each transaction that a
 * {@code @Transactional}, {@code @CockroachDBTransactional} or
 * {@code CockroachDBTransactionTemplate} call begins.
 * <p>
 * Those callers {@linkplain #bind(String) bind} a scope on the current thread for the length of
 * the call. When a transaction begins inside that scope, this listener runs
 * {@code SET crdb_region} on the transaction's connection, using the first of:
 * <ol>
 *   <li>the region the caller bound explicitly ({@code @CockroachDBTransactional(region = ...)}
 *       or {@code CockroachDBTransactionTemplate.setRegion(...)});</li>
 *   <li>{@link RegionContext};</li>
 *   <li>the {@link RegionResolver} bean;</li>
 *   <li>{@code cockroachdb.region.default-region}.</li>
 * </ol>
 * No region, or a transaction begun outside such a scope, leaves the session region alone.
 * The starter registers this listener with every {@code ConfigurableTransactionManager} bean.
 */
public class CockroachDBRegions implements TransactionExecutionListener {

    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** The innermost active scope on this thread; null when none. */
    private static final ThreadLocal<Binding> BINDING = new ThreadLocal<>();

    private final CockroachDBProperties.Region properties;
    private final RegionResolver regionResolver;
    private final JdbcTemplate jdbcTemplate;

    public CockroachDBRegions(CockroachDBProperties.Region properties,
                              RegionResolver regionResolver,
                              JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.regionResolver = regionResolver;
        this.jdbcTemplate = jdbcTemplate;
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
        if (region != null) {
            jdbcTemplate.execute("SET crdb_region = '" + region + "'");
        }
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
