package com.cockroachlabs.data.aop;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.template.CockroachDBTransactionTemplate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Retry decisions in {@link CockroachDBAspect}: retry when SQLSTATE 40001 is anywhere in
 * the cause chain, whatever exception type wraps it, and rethrow everything else at once.
 */
class CockroachDBAspectRetryTest {

    @CockroachDB
    void annotated() {
    }

    @CockroachDB(maxRetries = 1)
    void oneRetry() {
    }

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final CockroachDBMetrics.InMemory metrics = new CockroachDBMetrics.InMemory();
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    private final AtomicInteger attempts = new AtomicInteger();
    private CockroachDBAspect aspect;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        CockroachDBProperties properties = new CockroachDBProperties();
        properties.getRetry().setMaxRetries(3);
        properties.getRetry().setDelay(0);
        properties.getRetry().setJitter(0);

        aspect = new CockroachDBAspect(new CockroachDBTransactionTemplate(
                txManager, properties, metrics, mock(JdbcTemplate.class), null));
    }

    private CockroachDB annotationOf(String method) throws NoSuchMethodException {
        return getClass().getDeclaredMethod(method).getAnnotation(CockroachDB.class);
    }

    static SQLException sqlState(String state) {
        return new SQLException("simulated", state);
    }

    /** Fails with {@code failure} on the first {@code failures} attempts, then returns "ok". */
    private void failFirst(int failures, RuntimeException failure) throws Throwable {
        when(pjp.proceed()).thenAnswer(inv -> {
            if (attempts.incrementAndGet() <= failures) {
                throw failure;
            }
            return "ok";
        });
    }

    @Test
    void retriesJpaSystemExceptionWrapping40001() throws Throwable {
        failFirst(1, new JpaSystemException(new RuntimeException(sqlState("40001"))));

        assertThat(aspect.around(pjp, annotationOf("annotated"))).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(metrics.getRetryCount()).isEqualTo(1);
        assertThat(metrics.getFailureCount()).isZero();
    }

    @Test
    void retriesTransactionSystemExceptionWrapping40001() throws Throwable {
        // What JpaTransactionManager throws when the commit itself hits 40001.
        failFirst(2, new TransactionSystemException("Could not commit JPA transaction", sqlState("40001")));

        assertThat(aspect.around(pjp, annotationOf("annotated"))).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(metrics.getRetryCount()).isEqualTo(2);
    }

    @Test
    void stillRetriesTranslatedJdbcException() throws Throwable {
        failFirst(1, new CannotAcquireLockException("conflict", sqlState("40001")));

        assertThat(aspect.around(pjp, annotationOf("annotated"))).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryOtherSqlStates() throws Throwable {
        JpaSystemException duplicateKey = new JpaSystemException(new RuntimeException(sqlState("23505")));
        failFirst(1, duplicateKey);

        assertThatThrownBy(() -> aspect.around(pjp, annotationOf("annotated"))).isSameAs(duplicateKey);
        assertThat(attempts).hasValue(1);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
    }

    @Test
    void doesNotRetryNonSqlExceptions() throws Throwable {
        IllegalStateException bug = new IllegalStateException("bug");
        failFirst(1, bug);

        assertThatThrownBy(() -> aspect.around(pjp, annotationOf("annotated"))).isSameAs(bug);
        assertThat(attempts).hasValue(1);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
    }

    @Test
    void stopsAtMaxRetriesAndRecordsFailure() throws Throwable {
        JpaSystemException conflict = new JpaSystemException(new RuntimeException(sqlState("40001")));
        failFirst(Integer.MAX_VALUE, conflict);

        assertThatThrownBy(() -> aspect.around(pjp, annotationOf("annotated"))).isSameAs(conflict);
        assertThat(attempts).hasValue(4);
        assertThat(metrics.getRetryCount()).isEqualTo(3);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
    }

    @Test
    void annotationOverridesMaxRetries() throws Throwable {
        JpaSystemException conflict = new JpaSystemException(new RuntimeException(sqlState("40001")));
        failFirst(Integer.MAX_VALUE, conflict);

        assertThatThrownBy(() -> aspect.around(pjp, annotationOf("oneRetry"))).isSameAs(conflict);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void retriesCheckedExceptionWrapping40001AndThrowsItUnwrapped() throws Throwable {
        IOException conflict = new IOException("wrapped", sqlState("40001"));
        when(pjp.proceed()).thenThrow(conflict);

        assertThatThrownBy(() -> aspect.around(pjp, annotationOf("annotated"))).isSameAs(conflict);
        assertThat(metrics.getRetryCount()).isEqualTo(3);
    }
}
