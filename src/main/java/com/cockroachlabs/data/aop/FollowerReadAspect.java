package com.cockroachlabs.data.aop;

import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class FollowerReadAspect {

    private final PlatformTransactionManager txManager;
    private final JdbcTemplate jdbcTemplate;
    private final CockroachDBMetrics metrics;

    public FollowerReadAspect(PlatformTransactionManager txManager,
                              JdbcTemplate jdbcTemplate,
                              CockroachDBMetrics metrics) {
        this.txManager = txManager;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    @Around("@annotation(followerRead)")
    public Object around(ProceedingJoinPoint pjp,
                         FollowerRead followerRead) throws Throwable {

        if (!shouldUseFollowerRead(pjp, followerRead)) {
            return pjp.proceed();
        }

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setReadOnly(true);

        long start = System.nanoTime();
        try {
            Object result = txTemplate.execute(status -> {
                jdbcTemplate.execute(
                        "SET TRANSACTION AS OF SYSTEM TIME follower_read_timestamp()");
                try {
                    return pjp.proceed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            metrics.recordFollowerRead(start, true);
            return result;
        } catch (Exception ex) {
            metrics.recordFollowerRead(start, false);
            throw ex;
        }
    }

    private boolean shouldUseFollowerRead(ProceedingJoinPoint pjp,
                                          FollowerRead followerRead) {
        String staleParam = followerRead.staleParam();
        if (staleParam == null || staleParam.isBlank()) {
            return true;
        }

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            if (staleParam.equals(paramNames[i]) && args[i] instanceof Boolean b) {
                return b;
            }
        }
        return false;
    }
}
