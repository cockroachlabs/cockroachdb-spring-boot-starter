package com.cockroachlabs.data.aop;

import javax.sql.DataSource;
import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

        String setAsOf = "SET TRANSACTION AS OF SYSTEM TIME " + asOfExpression(pjp, followerRead);

        // REQUIRES_NEW: joining a caller's transaction would apply AS OF SYSTEM TIME to
        // it, which CockroachDB rejects once that transaction has read or written data. The
        // caller's transaction (and connection) is suspended instead, so a second pooled
        // connection is used for the duration of the call.
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.setReadOnly(true);

        long start = System.nanoTime();
        try {
            Object result = txTemplate.execute(status -> {
                requireTransactionBoundConnection(pjp);
                jdbcTemplate.execute(setAsOf);
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
        } catch (RuntimeException | Error ex) {
            metrics.recordFollowerRead(start, false);
            throw ex;
        }
    }

    static String asOfExpression(ProceedingJoinPoint pjp, FollowerRead followerRead) {
        long hoursAgo = followerRead.hoursAgo();
        long minutesAgo = followerRead.minutesAgo();
        long secondsAgo = followerRead.secondsAgo();
        if (hoursAgo < 0 || minutesAgo < 0 || secondsAgo < 0) {
            throw new IllegalStateException("@FollowerRead(hoursAgo = " + hoursAgo
                    + ", minutesAgo = " + minutesAgo + ", secondsAgo = " + secondsAgo
                    + ") on " + pjp.getSignature().toShortString()
                    + ": hoursAgo, minutesAgo and secondsAgo must not be negative");
        }
        long totalSecondsAgo = Math.addExact(Math.addExact(
                Math.multiplyExact(hoursAgo, 3600),
                Math.multiplyExact(minutesAgo, 60)),
                secondsAgo);
        // AS OF SYSTEM TIME can't take a placeholder, so the value is inlined; it is
        // built from a validated number, never from user text.
        return totalSecondsAgo == 0 ? "follower_read_timestamp()" : "'-" + totalSecondsAgo + "s'";
    }

    /**
     * SET TRANSACTION only affects the method's queries if JdbcTemplate runs it on the
     * connection the transaction manager bound for this transaction. Without that binding
     * (a different DataSource, or a transaction manager that doesn't expose its JDBC
     * connection), it would go to some other connection and the reads would silently be
     * current, so fail instead.
     */
    private void requireTransactionBoundConnection(ProceedingJoinPoint pjp) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("@FollowerRead on " + pjp.getSignature().toShortString()
                    + ": " + txManager.getClass().getSimpleName() + " has no JDBC connection bound"
                    + " for the JdbcTemplate's DataSource, so AS OF SYSTEM TIME can't be applied to"
                    + " the method's queries; use the same DataSource for the JdbcTemplate and the"
                    + " transaction manager");
        }
    }
}
