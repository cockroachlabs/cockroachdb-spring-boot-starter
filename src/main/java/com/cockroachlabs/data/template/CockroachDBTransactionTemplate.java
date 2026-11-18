package com.cockroachlabs.data.template;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.multiregion.RegionResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link TransactionTemplate} that retries the whole callback, in a fresh transaction
 * each time, when CockroachDB reports a serialization conflict (SQLSTATE {@code 40001}).
 * <p>
 * Use it exactly like {@code TransactionTemplate}: configure it with the inherited setters
 * ({@link #setIsolationLevel}, {@link #setReadOnly}, {@link #setTimeout}, ...) and call
 * {@link #execute(TransactionCallback)} or {@link #executeWithoutResult}. Its operations
 * are declared by {@link CockroachDBTransactionOperations}, which extends
 * {@code TransactionOperations}, the same way {@code TransactionTemplate}'s are declared by
 * {@code TransactionOperations}. On top of {@code TransactionTemplate} it adds:
 * <ul>
 *   <li>retry settings ({@link #setMaxRetries}, {@link #setDelay}, {@link #setMultiplier},
 *       {@link #setMaxDelay}), which default to the {@code cockroachdb.retry.*} properties;</li>
 *   <li>a session region ({@link #setRegion}, or the {@link #inRegion} shortcut) for
 *       REGIONAL BY ROW tables, which defaults to {@code RegionContext}, the
 *       {@code RegionResolver}, then {@code cockroachdb.region.default-region};</li>
 *   <li>{@link Supplier} and {@link Runnable} overloads for callbacks that don't need the
 *       {@code TransactionStatus}, inherited from {@code CockroachDBTransactionOperations}.</li>
 * </ul>
 * Unlike {@code TransactionTemplate}, the isolation level defaults to SERIALIZABLE.
 * <p>
 * As with {@code TransactionTemplate}, configure an instance before sharing it between
 * threads. To vary settings per call, copy the shared instance with
 * {@link #CockroachDBTransactionTemplate(CockroachDBTransactionTemplate)} and configure the copy.
 * {@code @CockroachDB} methods are run this way.
 */
public class CockroachDBTransactionTemplate extends TransactionTemplate
        implements CockroachDBTransactionOperations {

    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final CockroachDBProperties properties;
    private final CockroachDBMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final RegionResolver regionResolver;

    // null = use the cockroachdb.retry.* property
    private Integer maxRetries;
    private Long delay;
    private Double multiplier;
    private Long maxDelay;
    // null = resolve from RegionContext, RegionResolver, then the default region
    private String region;

    public CockroachDBTransactionTemplate(PlatformTransactionManager txManager,
                               CockroachDBProperties props,
                               CockroachDBMetrics metrics,
                               JdbcTemplate jdbcTemplate,
                               RegionResolver regionResolver) {
        super(txManager);
        this.properties = props;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.regionResolver = regionResolver;
        setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /**
     * Copies {@code template}: the same transaction manager, transaction definition
     * (isolation, propagation, read-only, timeout, name), retry settings and region.
     * Configure the copy without affecting the original.
     */
    public CockroachDBTransactionTemplate(CockroachDBTransactionTemplate template) {
        super(template.getTransactionManager(), template);
        this.properties = template.properties;
        this.metrics = template.metrics;
        this.jdbcTemplate = template.jdbcTemplate;
        this.regionResolver = template.regionResolver;
        this.maxRetries = template.maxRetries;
        this.delay = template.delay;
        this.multiplier = template.multiplier;
        this.maxDelay = template.maxDelay;
        this.region = template.region;
    }

    // ---- retry and region settings --------------------------------------------------

    /** Retry a conflicting transaction up to this many times (default {@code cockroachdb.retry.max-retries}). */
    public void setMaxRetries(int maxRetries) {
        requireNonNegative("maxRetries", maxRetries);
        this.maxRetries = maxRetries;
    }

    public int getMaxRetries() {
        return maxRetries != null ? maxRetries : properties.getRetry().getMaxRetries();
    }

    /** Wait this many ms before the first retry (default {@code cockroachdb.retry.delay}). */
    public void setDelay(long delay) {
        requireNonNegative("delay", delay);
        this.delay = delay;
    }

    public long getDelay() {
        return delay != null ? delay : properties.getRetry().getDelay();
    }

    /** Multiply the delay by this after each retry (default {@code cockroachdb.retry.multiplier}). */
    public void setMultiplier(double multiplier) {
        requireNonNegative("multiplier", multiplier);
        this.multiplier = multiplier;
    }

    public double getMultiplier() {
        return multiplier != null ? multiplier : properties.getRetry().getMultiplier();
    }

    /** Never wait longer than this many ms between retries (default {@code cockroachdb.retry.max-delay}). */
    public void setMaxDelay(long maxDelay) {
        requireNonNegative("maxDelay", maxDelay);
        this.maxDelay = maxDelay;
    }

    public long getMaxDelay() {
        return maxDelay != null ? maxDelay : properties.getRetry().getMaxDelay();
    }

    /**
     * Run {@code SET crdb_region} to this region at the start of each transaction,
     * overriding {@code RegionContext}, the {@code RegionResolver} and the default region.
     * {@code null} or blank restores that resolution chain.
     *
     * @throws IllegalArgumentException if the region is malformed or not in
     *         {@code cockroachdb.region.allowed} (when validation is on)
     */
    public void setRegion(String region) {
        this.region = (region == null || region.isBlank()) ? null : validateRegion(region);
    }

    /** The explicitly set region, or {@code null} when it is resolved per call. */
    public String getRegion() {
        return region;
    }

    /** A copy of this template that runs in {@code region}; see {@link #setRegion}. */
    @Override
    public CockroachDBTransactionTemplate inRegion(String region) {
        CockroachDBTransactionTemplate copy = new CockroachDBTransactionTemplate(this);
        copy.setRegion(region);
        return copy;
    }

    // ---- execution ------------------------------------------------------------------

    /**
     * Runs {@code action} in a transaction, as {@link TransactionTemplate#execute} does,
     * and retries it in a fresh transaction when it fails with SQLSTATE {@code 40001}
     * anywhere in the cause chain, whatever exception type wraps it. Any other exception
     * is rethrown immediately.
     */
    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        int maxRetries = getMaxRetries();
        long currentDelay = getDelay();
        double multiplier = getMultiplier();
        long maxDelay = getMaxDelay();
        long jitter = properties.getRetry().getJitter();

        String region = resolveRegion();

        long start = System.nanoTime();
        int attempt = 0;

        while (true) {
            try {
                T result = super.execute(status -> {
                    setSessionRegion(region);
                    return action.doInTransaction(status);
                });
                metrics.recordTransaction(start, attempt, true);
                return result;
            } catch (RuntimeException ex) {
                // Decide on SQLSTATE alone: JPA wraps 40001 in JpaSystemException or
                // TransactionSystemException, which are not TransientDataAccessExceptions.
                if (!isSerializationError(ex) || attempt >= maxRetries) {
                    metrics.recordTransaction(start, attempt, false);
                    throw ex;
                }
                attempt++;
                sleep(currentDelay, jitter);
                currentDelay = Math.min((long) (currentDelay * multiplier), maxDelay);
            }
        }
    }

    // ---- regions --------------------------------------------------------------------

    private String resolveRegion() {
        if (region != null) {
            return region;
        }
        return RegionContext.current()
                .filter(r -> !r.isBlank())
                .map(this::validateRegion)
                .orElseGet(() -> {
                    if (regionResolver != null) {
                        String r = regionResolver.resolve();
                        if (r != null && !r.isBlank()) {
                            return validateRegion(r);
                        }
                    }
                    String def = properties.getRegion().getDefaultRegion();
                    return (def != null && !def.isBlank()) ? validateRegion(def) : null;
                });
    }

    private void setSessionRegion(String region) {
        if (region == null || region.isBlank()) {
            return;
        }
        jdbcTemplate.execute("SET crdb_region = '" + region + "'");
    }

    private String validateRegion(String region) {
        if (!REGION_PATTERN.matcher(region).matches()) {
            throw new IllegalArgumentException(
                    "Invalid region format: '" + region + "' (must match [a-z][a-z0-9-]*)");
        }

        CockroachDBProperties.Region regionProps = properties.getRegion();
        if (regionProps.isValidate()) {
            List<String> allowed = regionProps.getAllowed();
            if (!allowed.isEmpty() && !allowed.contains(region)) {
                throw new IllegalArgumentException(
                        "Region '" + region + "' not in allowed list: " + allowed);
            }
        }
        return region;
    }

    // ---- helpers --------------------------------------------------------------------

    /** True when any exception in the cause chain is a {@link SQLException} with SQLSTATE 40001. */
    public static boolean isSerializationError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sql
                    && "40001".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void sleep(long delay, long jitter) {
        try {
            long jitteredDelay = delay;
            if (jitter > 0) {
                jitteredDelay += ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
            }
            Thread.sleep(Math.max(0, jitteredDelay));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void requireNonNegative(String name, double value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }
}
