package com.cockroachlabs.data.aop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.template.CockroachDBTransactionTemplate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;

/**
 * Runs {@link CockroachDB @CockroachDB} methods through {@link CockroachDBTransactionTemplate}.
 * <p>
 * The aspect only translates the annotation into a configured copy of the template; the
 * transaction handling and the 40001 retry loop live in the template. Like
 * {@code @Transactional}, a method-level annotation wins over a class-level one, and an
 * exception rolls the transaction back only when the rollback rules say so (by default,
 * unchecked exceptions and errors); otherwise the transaction commits and the exception
 * still reaches the caller unchanged. A serialization conflict always rolls back.
 * <p>
 * Runs at {@code LOWEST_PRECEDENCE - 2}, outside Spring's transaction advisor, so each
 * retry gets a fresh transaction.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 2)
public class CockroachDBAspect implements BeanFactoryAware {

    private final CockroachDBTransactionTemplate template;
    private final Map<CockroachDB, Prepared> prepared = new ConcurrentHashMap<>();
    private BeanFactory beanFactory;

    public CockroachDBAspect(CockroachDBTransactionTemplate template) {
        this.template = template;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Around("@annotation(cockroachDB)")
    public Object around(ProceedingJoinPoint pjp, CockroachDB cockroachDB) throws Throwable {
        return invoke(pjp, cockroachDB);
    }

    @Around("@within(cockroachDB) && execution(public * *(..)) "
            + "&& !@annotation(com.cockroachlabs.data.annotation.CockroachDB)")
    public Object aroundClass(ProceedingJoinPoint pjp, CockroachDB cockroachDB) throws Throwable {
        return invoke(pjp, cockroachDB);
    }

    private Object invoke(ProceedingJoinPoint pjp, CockroachDB cockroachDB) throws Throwable {
        Prepared p = prepared.computeIfAbsent(cockroachDB, this::prepare);

        // An exception the rollback rules say to commit on; thrown after the commit.
        Throwable[] committedWith = new Throwable[1];
        try {
            Object result = p.template().execute(status -> {
                committedWith[0] = null;
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    if (!CockroachDBTransactionTemplate.isSerializationError(t) && !p.rules().rollbackOn(t)) {
                        committedWith[0] = t;
                        return null;
                    }
                    throw tunnel(t);
                }
            });
            if (committedWith[0] != null) {
                throw committedWith[0];
            }
            return result;
        } catch (ThrowableTunnel tunnel) {
            throw tunnel.getCause();
        }
    }

    /** Builds the template copy and rollback rules for one annotation; cached per annotation value. */
    private Prepared prepare(CockroachDB cockroachDB) {
        CockroachDBTransactionTemplate tx = new CockroachDBTransactionTemplate(template);

        String qualifier = !cockroachDB.transactionManager().isEmpty()
                ? cockroachDB.transactionManager() : cockroachDB.value();
        if (!qualifier.isEmpty()) {
            if (beanFactory == null) {
                throw new IllegalStateException(
                        "@CockroachDB(transactionManager = \"" + qualifier + "\") needs a BeanFactory");
            }
            tx.setTransactionManager(BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                    beanFactory, PlatformTransactionManager.class, qualifier));
        }
        tx.setPropagationBehavior(cockroachDB.propagation().value());
        tx.setIsolationLevel(cockroachDB.isolation().value());
        tx.setTimeout(timeout(cockroachDB));
        tx.setReadOnly(cockroachDB.readOnly());

        if (cockroachDB.maxRetries() >= 0) {
            tx.setMaxRetries(cockroachDB.maxRetries());
        }
        if (cockroachDB.delay() >= 0) {
            tx.setDelay(cockroachDB.delay());
        }
        if (cockroachDB.multiplier() >= 0) {
            tx.setMultiplier(cockroachDB.multiplier());
        }
        if (cockroachDB.maxDelay() >= 0) {
            tx.setMaxDelay(cockroachDB.maxDelay());
        }
        if (!cockroachDB.region().isBlank()) {
            tx.setRegion(cockroachDB.region());
        }

        return new Prepared(tx, rollbackRules(cockroachDB));
    }

    private int timeout(CockroachDB cockroachDB) {
        String timeoutString = cockroachDB.timeoutString();
        if (timeoutString.isBlank()) {
            return cockroachDB.timeout();
        }
        if (cockroachDB.timeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
            throw new IllegalArgumentException("Specify 'timeout' or 'timeoutString', not both");
        }
        if (beanFactory instanceof ConfigurableBeanFactory cbf) {
            timeoutString = cbf.resolveEmbeddedValue(timeoutString);
        }
        try {
            return Integer.parseInt(timeoutString.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid timeoutString '" + timeoutString + "': must be a number of seconds", e);
        }
    }

    /** The same rules {@code @Transactional} builds from its rollbackFor/noRollbackFor attributes. */
    private static RuleBasedTransactionAttribute rollbackRules(CockroachDB cockroachDB) {
        List<RollbackRuleAttribute> rules = new ArrayList<>();
        for (Class<?> type : cockroachDB.rollbackFor()) {
            rules.add(new RollbackRuleAttribute(type));
        }
        for (String name : cockroachDB.rollbackForClassName()) {
            rules.add(new RollbackRuleAttribute(name));
        }
        for (Class<?> type : cockroachDB.noRollbackFor()) {
            rules.add(new NoRollbackRuleAttribute(type));
        }
        for (String name : cockroachDB.noRollbackForClassName()) {
            rules.add(new NoRollbackRuleAttribute(name));
        }
        RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
        attribute.setRollbackRules(rules);
        return attribute;
    }

    private static RuntimeException tunnel(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new ThrowableTunnel(t);
    }

    private record Prepared(CockroachDBTransactionTemplate template, RuleBasedTransactionAttribute rules) {
    }

    /** Carries a checked exception out of the transaction callback; unwrapped before it reaches the caller. */
    private static final class ThrowableTunnel extends RuntimeException {
        ThrowableTunnel(Throwable cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }
}
