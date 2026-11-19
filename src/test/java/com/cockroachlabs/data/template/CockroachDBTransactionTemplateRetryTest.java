package com.cockroachlabs.data.template;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.transaction.CockroachDBRegions;
import com.cockroachlabs.data.transaction.SerializationRetry;
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
 * Retry decisions in {@link CockroachDBTransactionTemplate}: retry when SQLSTATE 40001 is anywhere in
 * the cause chain, whatever exception type wraps it, and rethrow everything else at once.
 */
class CockroachDBTransactionTemplateRetryTest {

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final CockroachDBMetrics.InMemory metrics = new CockroachDBMetrics.InMemory();
    private final AtomicInteger attempts = new AtomicInteger();
    private CockroachDBTransactionTemplate template;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        CockroachDBProperties properties = new CockroachDBProperties();
        properties.getRetry().setMaxRetries(3);
        properties.getRetry().setDelay(0);
        properties.getRetry().setJitter(0);

        template = new CockroachDBTransactionTemplate(txManager,
                new SerializationRetry(properties.getRetry(), metrics),
                new CockroachDBRegions(properties.getRegion(), null, mock(JdbcTemplate.class)));
    }

    static SQLException sqlState(String state) {
        return new SQLException("simulated", state);
    }

    /** Fails with {@code failure} on the first {@code failures} attempts, then returns "ok". */
    private String failFirst(int failures, RuntimeException failure) {
        return template.execute(() -> {
            if (attempts.incrementAndGet() <= failures) {
                throw failure;
            }
            return "ok";
        });
    }

    @Test
    void retriesJpaSystemExceptionWrapping40001() {
        assertThat(failFirst(1, new JpaSystemException(new RuntimeException(sqlState("40001")))))
                .isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(metrics.getRetryCount()).isEqualTo(1);
        assertThat(metrics.getFailureCount()).isZero();
    }

    @Test
    void retriesTransactionSystemExceptionWrapping40001() {
        // What JpaTransactionManager throws when the commit itself hits 40001.
        assertThat(failFirst(2, new TransactionSystemException("Could not commit JPA transaction", sqlState("40001"))))
                .isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(metrics.getRetryCount()).isEqualTo(2);
    }

    @Test
    void stillRetriesTranslatedJdbcException() {
        assertThat(failFirst(1, new CannotAcquireLockException("conflict", sqlState("40001"))))
                .isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryOtherSqlStates() {
        JpaSystemException duplicateKey = new JpaSystemException(new RuntimeException(sqlState("23505")));

        assertThatThrownBy(() -> failFirst(1, duplicateKey)).isSameAs(duplicateKey);
        assertThat(attempts).hasValue(1);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
    }

    @Test
    void doesNotRetryNonSqlExceptions() {
        IllegalStateException bug = new IllegalStateException("bug");

        assertThatThrownBy(() -> failFirst(1, bug)).isSameAs(bug);
        assertThat(attempts).hasValue(1);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
    }

    @Test
    void stopsAtMaxRetriesAndRecordsFailure() {
        JpaSystemException conflict = new JpaSystemException(new RuntimeException(sqlState("40001")));

        assertThatThrownBy(() -> failFirst(Integer.MAX_VALUE, conflict)).isSameAs(conflict);
        assertThat(attempts).hasValue(4);
        assertThat(metrics.getRetryCount()).isEqualTo(3);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
    }
}
