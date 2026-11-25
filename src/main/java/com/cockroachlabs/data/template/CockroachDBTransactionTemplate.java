package com.cockroachlabs.data.template;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;
import java.util.function.Supplier;
import com.cockroachlabs.data.transaction.CockroachDBRegions;
import com.cockroachlabs.data.transaction.RetrySettings;
import com.cockroachlabs.data.transaction.SerializationRetry;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 *   <li>a region ({@link #setRegion}, or the {@link #inRegion} shortcut) that new
 *       REGIONAL BY ROW entities are homed to, which defaults to {@code RegionContext}, the
 *       {@code RegionResolver}, then {@code cockroachdb.region.default-region};</li>
 *   <li>{@link Supplier} and {@link Runnable} overloads for callbacks that don't need the
 *       {@code TransactionStatus}, inherited from {@code CockroachDBTransactionOperations}.</li>
 * </ul>
 * Unlike {@code TransactionTemplate}, the isolation level defaults to SERIALIZABLE.
 * <p>
 * Like {@code @CockroachDBTransactional}, it retries only when it starts the transaction. Called
 * inside a transaction that is already running, it runs the callback once and leaves retrying
 * to whoever owns that transaction.
 * <p>
 * As with {@code TransactionTemplate}, configure an instance before sharing it between
 * threads. To vary settings, copy the shared instance with
 * {@link #CockroachDBTransactionTemplate(CockroachDBTransactionTemplate)} and configure the copy.
 */
public class CockroachDBTransactionTemplate extends TransactionTemplate
        implements CockroachDBTransactionOperations {

    private final SerializationRetry retry;
    private final CockroachDBRegions regions;

    // null = use the cockroachdb.retry.* property
    private @Nullable Integer maxRetries;
    private @Nullable Long delay;
    private @Nullable Double multiplier;
    private @Nullable Long maxDelay;
    // null = resolve from RegionContext, RegionResolver, then the default region
    private @Nullable String region;

    public CockroachDBTransactionTemplate(PlatformTransactionManager txManager,
                                          SerializationRetry retry,
                                          CockroachDBRegions regions) {
        super(txManager);
        this.retry = retry;
        this.regions = regions;
        setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /**
     * Copies {@code template}: the same transaction manager, transaction definition
     * (isolation, propagation, read-only, timeout, name), retry settings and region.
     * Configure the copy without affecting the original.
     */
    public CockroachDBTransactionTemplate(CockroachDBTransactionTemplate template) {
        super(Objects.requireNonNull(template.getTransactionManager(), "template has no transaction manager"), template);
        this.retry = template.retry;
        this.regions = template.regions;
        this.maxRetries = template.maxRetries;
        this.delay = template.delay;
        this.multiplier = template.multiplier;
        this.maxDelay = template.maxDelay;
        this.region = template.region;
    }

    // ---- retry and region settings --------------------------------------------------

    /** Retry a conflicting transaction up to this many times (default {@code cockroachdb.retry.max-retries}). */
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = settings().withMaxRetries(maxRetries).maxRetries();
    }

    public int getMaxRetries() {
        return settings().maxRetries();
    }

    /** Wait this many ms before the first retry (default {@code cockroachdb.retry.delay}). */
    public void setDelay(long delay) {
        this.delay = settings().withDelay(delay).delay();
    }

    public long getDelay() {
        return settings().delay();
    }

    /** Multiply the delay by this after each retry (default {@code cockroachdb.retry.multiplier}). */
    public void setMultiplier(double multiplier) {
        this.multiplier = settings().withMultiplier(multiplier).multiplier();
    }

    public double getMultiplier() {
        return settings().multiplier();
    }

    /** Never wait longer than this many ms between retries (default {@code cockroachdb.retry.max-delay}). */
    public void setMaxDelay(long maxDelay) {
        this.maxDelay = settings().withMaxDelay(maxDelay).maxDelay();
    }

    public long getMaxDelay() {
        return settings().maxDelay();
    }

    /**
     * Bind this region to each transaction this template begins, so new REGIONAL BY ROW
     * entities are homed to it, overriding {@code RegionContext}, the {@code RegionResolver} and the default region.
     * {@code null} or blank restores that resolution chain.
     *
     * @throws IllegalArgumentException if the region is malformed or not in
     *         {@code cockroachdb.region.allowed} (when validation is on)
     */
    public void setRegion(@Nullable String region) {
        this.region = (region == null || region.isBlank()) ? null : regions.validate(region);
    }

    /** The explicitly set region, or {@code null} when it is resolved per transaction. */
    public @Nullable String getRegion() {
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
     * is rethrown immediately. Inside a transaction that is already running, it runs once.
     */
    @Override
    public <T extends @Nullable Object> T execute(TransactionCallback<T> action) throws TransactionException {
        try (CockroachDBRegions.Scope scope = CockroachDBRegions.bind(region)) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                return super.execute(action);
            }
            return retry.execute(settings(), () -> super.execute(action));
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (Throwable ex) {
            // TransactionTemplate.execute only throws unchecked exceptions.
            throw new UndeclaredThrowableException(ex);
        }
    }

    private RetrySettings settings() {
        RetrySettings settings = retry.defaults();
        if (maxRetries != null) {
            settings = settings.withMaxRetries(maxRetries);
        }
        if (delay != null) {
            settings = settings.withDelay(delay);
        }
        if (multiplier != null) {
            settings = settings.withMultiplier(multiplier);
        }
        if (maxDelay != null) {
            settings = settings.withMaxDelay(maxDelay);
        }
        return settings;
    }
}
