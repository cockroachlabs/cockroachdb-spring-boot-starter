package com.cockroachlabs.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.cockroachlabs.data.transaction.CockroachDBTransactionAdvisor;
import com.cockroachlabs.data.transaction.CockroachDBTransactionManagementConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Enables CockroachDB's retry paradigm for transactional methods, the counterpart of Spring's
 * {@code @EnableTransactionManagement}.
 * <p>
 * Spring's {@code @EnableTransactionManagement} makes {@code @Transactional} start, commit and
 * roll back transactions. This annotation adds what CockroachDB's SERIALIZABLE model needs on
 * top: methods that fail with SQLSTATE {@code 40001} are retried in a fresh transaction, and
 * transactions they begin are bound to the resolved region. It works only together with Spring's
 * transaction management, whose advisor it wraps.
 * <p>
 * <b>You don't usually need it.</b> Just as Spring Boot applies {@code @EnableTransactionManagement}
 * for you, the starter's auto-configuration applies this annotation with its defaults whenever
 * Spring's transaction management is on. Declare it yourself on a {@code @Configuration} class to
 * change the defaults, such as the {@link #order}; the auto-configuration then backs off.
 * <p>
 * Which methods are retried, and with which settings, is controlled by
 * {@code @CockroachDBTransactional} and the {@code cockroachdb.retry.*} properties.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(CockroachDBTransactionManagementConfiguration.class)
public @interface EnableCockroachDBTransactionManagement {

    /**
     * Order of the retry advisor. It must run outside Spring's transaction advisor, so it must be
     * lower (higher precedence) than {@code @EnableTransactionManagement}'s order, which defaults
     * to {@code Ordered.LOWEST_PRECEDENCE}. Startup fails if it isn't.
     */
    int order() default CockroachDBTransactionAdvisor.ORDER;
}
