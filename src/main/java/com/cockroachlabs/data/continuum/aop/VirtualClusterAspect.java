package com.cockroachlabs.data.continuum.aop;

import com.cockroachlabs.data.continuum.annotation.VirtualCluster;
import com.cockroachlabs.data.continuum.annotation.Workload;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;

/**
 * Applies Cockroach vCluster workload tagging to {@link VirtualCluster}-annotated
 * methods: opens a transaction and issues {@code SET LOCAL application_name} for
 * the vCluster name and, when {@link Workload} is present, {@code SET LOCAL
 * default_transaction_quality_of_service} for the priority — then proceeds.
 *
 * <p>{@code SET LOCAL} scopes the tags to the transaction so pooled connections
 * are not polluted for the next borrower.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 2)
public class VirtualClusterAspect {

    private final PlatformTransactionManager txManager;
    private final JdbcTemplate jdbcTemplate;

    public VirtualClusterAspect(PlatformTransactionManager txManager, JdbcTemplate jdbcTemplate) {
        this.txManager = txManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Around("@annotation(virtualCluster)")
    public Object around(ProceedingJoinPoint pjp, VirtualCluster virtualCluster) throws Throwable {
        Workload workload = workloadOf(pjp);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        return txTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL application_name = " + quote(virtualCluster.value()));
            if (workload != null) {
                jdbcTemplate.execute("SET LOCAL default_transaction_quality_of_service = "
                        + quote(workload.value().qos()));
            }
            try {
                return pjp.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Workload workloadOf(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return method.getAnnotation(Workload.class);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
