package com.cockroachlabs.data.transaction;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.cockroachlabs.data.annotation.CockroachDBTransactional;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodClassKey;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.function.SingletonSupplier;

/**
 * Retries transactional methods that fail with SQLSTATE {@code 40001}, and binds the session
 * region for the transactions they begin.
 * <p>
 * Runs outside Spring's transaction interceptor (see {@link CockroachDBTransactionAdvisor}), so
 * every attempt re-enters that interceptor through a clone of the invocation and gets a fresh
 * transaction. It retries only at the outermost transactional call: when a transaction is
 * already active, the method runs once and a {@code 40001} propagates to the caller that owns
 * the transaction.
 */
class CockroachDBTransactionInterceptor implements MethodInterceptor {

    // Resolved on first use: an advisor is created while bean post-processors are still being
    // registered, and eager collaborators would be created too early to be post-processed.
    private final Supplier<SerializationRetry> retry;
    private final Supplier<CockroachDBRegions> regions;
    private final BooleanSupplier applyToTransactionalMethods;
    private final Map<MethodClassKey, Plan> plans = new ConcurrentHashMap<>();

    /**
     * @param applyToTransactionalMethods whether plain {@code @Transactional} methods are
     *        retried too ({@code cockroachdb.retry.apply-to-transactional-methods})
     */
    CockroachDBTransactionInterceptor(SerializationRetry retry,
                                             CockroachDBRegions regions,
                                             boolean applyToTransactionalMethods) {
        this(() -> retry, () -> regions, () -> applyToTransactionalMethods);
    }

    /** Like the other constructor, with each collaborator looked up once, on first use. */
    CockroachDBTransactionInterceptor(Supplier<SerializationRetry> retry,
                                             Supplier<CockroachDBRegions> regions,
                                             BooleanSupplier applyToTransactionalMethods) {
        this.retry = SingletonSupplier.of(retry);
        this.regions = SingletonSupplier.of(regions);
        Supplier<Boolean> apply = SingletonSupplier.of(applyToTransactionalMethods::getAsBoolean);
        this.applyToTransactionalMethods = apply::get;
    }

    /** What to do for one method: whether and how to retry it, and its explicit region. */
    private record Plan(boolean retried, RetrySettings settings, @Nullable String region) {
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        Class<?> targetClass = invocation.getThis() != null ? AopUtils.getTargetClass(invocation.getThis()) : null;
        Plan plan = plans.computeIfAbsent(new MethodClassKey(invocation.getMethod(), targetClass),
                key -> plan(invocation.getMethod(), targetClass));

        try (CockroachDBRegions.Scope scope = CockroachDBRegions.bind(plan.region())) {
            boolean outermost = !TransactionSynchronizationManager.isActualTransactionActive();
            if (!plan.retried() || !outermost || !(invocation instanceof ProxyMethodInvocation proxyInvocation)) {
                return invocation.proceed();
            }
            return retry.get().execute(plan.settings(), () -> proxyInvocation.invocableClone().proceed());
        }
    }

    private Plan plan(Method method, @Nullable Class<?> targetClass) {
        CockroachDBTransactional annotation = findAnnotation(method, targetClass);
        RetrySettings settings = retry.get().defaults();
        if (annotation == null) {
            return new Plan(applyToTransactionalMethods.getAsBoolean(), settings, null);
        }
        if (annotation.maxRetries() >= 0) {
            settings = settings.withMaxRetries(annotation.maxRetries());
        }
        if (annotation.delay() >= 0) {
            settings = settings.withDelay(annotation.delay());
        }
        if (annotation.multiplier() >= 0) {
            settings = settings.withMultiplier(annotation.multiplier());
        }
        if (annotation.maxDelay() >= 0) {
            settings = settings.withMaxDelay(annotation.maxDelay());
        }
        String region = annotation.region().isBlank() ? null : regions.get().validate(annotation.region());
        return new Plan(true, settings, region);
    }

    /**
     * The {@code @CockroachDBTransactional} that applies, looked up the way Spring looks up
     * {@code @Transactional}: the most specific method first, then its class.
     */
    private static @Nullable CockroachDBTransactional findAnnotation(Method method, @Nullable Class<?> targetClass) {
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        CockroachDBTransactional annotation =
                AnnotatedElementUtils.findMergedAnnotation(specificMethod, CockroachDBTransactional.class);
        if (annotation == null && specificMethod != method) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method, CockroachDBTransactional.class);
        }
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(
                    specificMethod.getDeclaringClass(), CockroachDBTransactional.class);
        }
        if (annotation == null && targetClass != null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, CockroachDBTransactional.class);
        }
        return annotation;
    }
}
