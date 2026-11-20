package com.cockroachlabs.data.aop;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * How exceptions from a {@code @FollowerRead} method reach its caller: a real
 * {@link DataSourceTransactionManager} runs the transaction over a mocked JDBC connection.
 */
class FollowerReadAspectExceptionTest {

    @FollowerRead
    void followerRead() {
    }

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CockroachDBMetrics metrics = mock(CockroachDBMetrics.class);
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

    private FollowerReadAspect aspect;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        Signature sig = mock(Signature.class);
        when(sig.toShortString()).thenReturn("Catalog.count(..)");
        when(pjp.getSignature()).thenReturn(sig);
        aspect = new FollowerReadAspect(
                new DataSourceTransactionManager(dataSource), new JdbcTemplate(dataSource), metrics);
    }

    private FollowerRead annotation() throws NoSuchMethodException {
        return getClass().getDeclaredMethod("followerRead").getAnnotation(FollowerRead.class);
    }

    @Test
    void checkedExceptionReachesTheCallerAsTheSameInstance() throws Throwable {
        IOException checked = new IOException("disk on fire");
        when(pjp.proceed()).thenThrow(checked);

        assertThatThrownBy(() -> aspect.around(pjp, annotation())).isSameAs(checked);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(metrics).recordFollowerRead(anyLong(), eq(false));
    }

    @Test
    void uncheckedExceptionReachesTheCallerAsTheSameInstance() throws Throwable {
        IllegalStateException unchecked = new IllegalStateException("bug");
        when(pjp.proceed()).thenThrow(unchecked);

        assertThatThrownBy(() -> aspect.around(pjp, annotation())).isSameAs(unchecked);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(metrics).recordFollowerRead(anyLong(), eq(false));
    }

    @Test
    void successCommitsAndReturnsTheResult() throws Throwable {
        when(pjp.proceed()).thenReturn(42);

        assertThat(aspect.around(pjp, annotation())).isEqualTo(42);
        verify(connection).commit();
        verify(metrics).recordFollowerRead(anyLong(), eq(true));
    }
}
