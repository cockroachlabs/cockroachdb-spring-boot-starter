package com.cockroachlabs.data.transaction;

import java.lang.reflect.Method;
import org.aopalliance.aop.Advice;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.Ordered;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionalProxy;

/**
 * Applies {@link CockroachDBTransactionInterceptor} to every method Spring treats as
 * transactional: {@code @Transactional} on a method, class or interface, composed annotations
 * such as {@code @CockroachDBTransactional}, and {@code jakarta.transaction.Transactional}.
 * <p>
 * It matches through the same {@link TransactionAttributeSource} as Spring's transaction
 * advisor, so the two always agree on which methods are transactional. It is ordered at
 * {@code LOWEST_PRECEDENCE - 2}, ahead of Spring's transaction advisor (which defaults to
 * {@code LOWEST_PRECEDENCE}), so it wraps the transaction and each retry starts a new one.
 */
public class CockroachDBTransactionAdvisor extends AbstractPointcutAdvisor
        implements BeanFactoryAware, SmartInitializingSingleton {

    /** Outside Spring's transaction advisor ({@code LOWEST_PRECEDENCE}) and {@code @FollowerRead}. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    private final TransactionAttributeSource transactionAttributeSource;
    private final CockroachDBTransactionInterceptor interceptor;
    private @Nullable BeanFactory beanFactory;

    private final Pointcut pointcut = new StaticMethodMatcherPointcut() {

        {
            // The same classes Spring's own transaction pointcut leaves alone.
            setClassFilter(new ClassFilter() {
                @Override
                public boolean matches(Class<?> clazz) {
                    if (TransactionalProxy.class.isAssignableFrom(clazz)
                            || TransactionManager.class.isAssignableFrom(clazz)
                            || PersistenceExceptionTranslator.class.isAssignableFrom(clazz)) {
                        return false;
                    }
                    return transactionAttributeSource.isCandidateClass(clazz);
                }
            });
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return transactionAttributeSource.getTransactionAttribute(method, targetClass) != null;
        }
    };

    CockroachDBTransactionAdvisor(TransactionAttributeSource transactionAttributeSource,
                                         CockroachDBTransactionInterceptor interceptor) {
        this.transactionAttributeSource = transactionAttributeSource;
        this.interceptor = interceptor;
        setOrder(ORDER);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Fails startup unless Spring's transaction advisor is present and runs inside this one.
     * Without it, {@code @Transactional} methods have no transaction to retry. Ordered outside
     * this one, retries would happen inside a transaction that CockroachDB has already aborted; in
     * practice every call would find a transaction active and quietly never retry.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null) {
            return;
        }
        if (!beanFactory.containsBean(TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)) {
            throw new IllegalStateException("@EnableCockroachDBTransactionManagement needs Spring's"
                    + " annotation-driven transaction management, whose transaction advisor it wraps."
                    + " Add @EnableTransactionManagement (Spring Boot adds it when there is a transaction manager).");
        }
        Object springAdvisor = beanFactory.getBean(TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME);
        checkOrder(springAdvisor instanceof Ordered ordered ? ordered.getOrder() : Ordered.LOWEST_PRECEDENCE);
    }

    /**
     * Throws if an advisor of order {@code transactionAdvisorOrder} would not run inside this one.
     *
     * @throws IllegalStateException if Spring's transaction advisor is ordered at or before this one
     */
    void checkOrder(int transactionAdvisorOrder) {
        if (transactionAdvisorOrder <= getOrder()) {
            throw new IllegalStateException("Spring's transaction advisor has order " + transactionAdvisorOrder
                    + ", but the CockroachDB retry advisor (order " + getOrder() + ") must run outside it so"
                    + " that each retry starts a new transaction. Give @EnableTransactionManagement an order"
                    + " greater than " + getOrder() + " (its default, Ordered.LOWEST_PRECEDENCE, works), or"
                    + " @EnableCockroachDBTransactionManagement an order lower than " + transactionAdvisorOrder + ".");
        }
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return interceptor;
    }
}
