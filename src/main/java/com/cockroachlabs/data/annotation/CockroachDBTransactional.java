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
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Transactional @Transactional} with CockroachDB's retry paradigm for SERIALIZABLE
 * transactions: when the transaction fails with SQLSTATE {@code 40001}, the whole method is run
 * again in a fresh transaction, with exponential backoff and jitter.
 * <p>
 * This is a composed annotation: it is meta-annotated with {@code @Transactional}, so Spring's
 * own transaction support runs the transaction, and every {@code @Transactional} attribute
 * below means exactly what it does there. The one difference is that {@link #isolation}
 * defaults to SERIALIZABLE, CockroachDB's default level.
 * <p>
 * Plain {@code @Transactional} methods get the same retries, with the
 * {@code cockroachdb.retry.*} defaults, unless
 * {@code cockroachdb.retry.apply-to-transactional-methods=false}. Use this annotation when a
 * method needs different retry settings or a fixed {@link #region}, or to keep retrying it when
 * that property is off.
 * <p>
 * Retries happen only at the outermost transactional call. A method called inside a
 * transaction that is already running runs once; if it hits a {@code 40001}, the outermost
 * call retries the whole unit of work.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Transactional
public @interface CockroachDBTransactional {

    // ---- @Transactional attributes --------------------------------------------------

    /** Alias for {@link #transactionManager}. */
    @AliasFor(annotation = Transactional.class)
    String value() default "";

    /** Qualifier of the {@code TransactionManager} to use, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    String transactionManager() default "";

    /** Transaction labels, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    String[] label() default {};

    /** Transaction propagation, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    Propagation propagation() default Propagation.REQUIRED;

    /** Isolation level. Unlike {@code @Transactional}, defaults to SERIALIZABLE. */
    @AliasFor(annotation = Transactional.class)
    Isolation isolation() default Isolation.SERIALIZABLE;

    /** Timeout in seconds for each attempt, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    int timeout() default TransactionDefinition.TIMEOUT_DEFAULT;

    /** {@link #timeout} as a string, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    String timeoutString() default "";

    /** Marks the transaction read-only, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    boolean readOnly() default false;

    /** Exception types that roll back the transaction, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    Class<? extends Throwable>[] rollbackFor() default {};

    /** Exception type name patterns that roll back the transaction, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    String[] rollbackForClassName() default {};

    /** Exception types that do not roll back the transaction, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
    Class<? extends Throwable>[] noRollbackFor() default {};

    /** Exception type name patterns that do not roll back the transaction, as on {@code @Transactional}. */
    @AliasFor(annotation = Transactional.class)
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
     * Region that new REGIONAL BY ROW entities are homed to, bound to each transaction this
     * method begins and copied into their {@code @CrdbRegion} field. No SQL is run for it. Empty uses {@code RegionContext}, the {@code RegionResolver}, then
     * {@code cockroachdb.region.default-region}.
     */
    String region() default "";
}
