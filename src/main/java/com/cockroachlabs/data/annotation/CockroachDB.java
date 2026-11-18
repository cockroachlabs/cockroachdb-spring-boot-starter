package com.cockroachlabs.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

/**
 * A drop-in replacement for {@code @Transactional} that also retries the method, in a
 * fresh transaction each time, when CockroachDB reports a serialization conflict
 * (SQLSTATE {@code 40001}).
 * <p>
 * The transaction attributes ({@link #transactionManager}, {@link #propagation},
 * {@link #isolation}, {@link #timeout}, {@link #readOnly} and the rollback rules) mean the
 * same as on {@code @Transactional}, with two differences:
 * <ul>
 *   <li>{@link #isolation} defaults to SERIALIZABLE, CockroachDB's default level.</li>
 *   <li>There is no {@code label} attribute.</li>
 * </ul>
 * On top of those it adds retry settings ({@link #maxRetries}, {@link #delay},
 * {@link #multiplier}, {@link #maxDelay}) and a session {@link #region}.
 * <p>
 * Put it on a method, or on a class to apply it to every public method; an annotation on
 * the method wins. Don't combine it with {@code @Transactional} on the same method.
 * Methods run through {@code CockroachDBTransactionTemplate}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface CockroachDB {

    // ---- @Transactional attributes --------------------------------------------------

    /** Alias for {@link #transactionManager}. */
    @AliasFor("transactionManager")
    String value() default "";

    /**
     * Qualifier (bean name or {@code @Qualifier} value) of the
     * {@code PlatformTransactionManager} to use. Empty uses the starter's default.
     */
    @AliasFor("value")
    String transactionManager() default "";

    /** Transaction propagation, as on {@code @Transactional}. */
    Propagation propagation() default Propagation.REQUIRED;

    /** Isolation level. Unlike {@code @Transactional}, defaults to SERIALIZABLE. */
    Isolation isolation() default Isolation.SERIALIZABLE;

    /** Timeout in seconds for each attempt; -1 uses the transaction manager's default. */
    int timeout() default TransactionDefinition.TIMEOUT_DEFAULT;

    /** {@link #timeout} as a string, in seconds; may be a {@code ${...}} placeholder. */
    String timeoutString() default "";

    /** Marks the transaction read-only, as on {@code @Transactional}. */
    boolean readOnly() default false;

    /** Exception types that roll back the transaction, as on {@code @Transactional}. */
    Class<? extends Throwable>[] rollbackFor() default {};

    /** Exception type name patterns that roll back the transaction, as on {@code @Transactional}. */
    String[] rollbackForClassName() default {};

    /** Exception types that do not roll back the transaction, as on {@code @Transactional}. */
    Class<? extends Throwable>[] noRollbackFor() default {};

    /** Exception type name patterns that do not roll back the transaction, as on {@code @Transactional}. */
    String[] noRollbackForClassName() default {};

    // ---- CockroachDB extensions -----------------------------------------------------

    /** Retries after a 40001 conflict; -1 uses {@code cockroachdb.retry.max-retries}. */
    int maxRetries() default -1;

    /** Milliseconds before the first retry; -1 uses {@code cockroachdb.retry.delay}. */
    long delay() default -1;

    /** Factor applied to the delay after each retry; -1 uses {@code cockroachdb.retry.multiplier}. */
    double multiplier() default -1;

    /** Longest wait between retries in milliseconds; -1 uses {@code cockroachdb.retry.max-delay}. */
    long maxDelay() default -1;

    /**
     * Target region for REGIONAL BY ROW operations, set with {@code SET crdb_region} at the
     * start of each transaction. Empty uses {@code RegionContext}, the {@code RegionResolver},
     * then the default region.
     */
    String region() default "";
}
