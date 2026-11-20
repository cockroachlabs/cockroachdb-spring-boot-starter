package com.cockroachlabs.data.continuum.aop;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import com.cockroachlabs.data.continuum.annotation.VirtualCluster;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * How exceptions from a {@code @VirtualCluster} method reach its caller: a real
 * {@link DataSourceTransactionManager} runs the transaction over a mocked JDBC connection.
 */
class VirtualClusterAspectTest {

    @VirtualCluster("analytics")
    void report() {
    }

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

    private VirtualClusterAspect aspect;
    private VirtualCluster annotation;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("report"));
        when(pjp.getSignature()).thenReturn(sig);
        annotation = getClass().getDeclaredMethod("report").getAnnotation(VirtualCluster.class);
        aspect = new VirtualClusterAspect(
                new DataSourceTransactionManager(dataSource), new JdbcTemplate(dataSource));
    }

    @Test
    void checkedExceptionReachesTheCallerAsTheSameInstance() throws Throwable {
        IOException checked = new IOException("disk on fire");
        when(pjp.proceed()).thenThrow(checked);

        assertThatThrownBy(() -> aspect.around(pjp, annotation)).isSameAs(checked);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void uncheckedExceptionReachesTheCallerAsTheSameInstance() throws Throwable {
        IllegalStateException unchecked = new IllegalStateException("bug");
        when(pjp.proceed()).thenThrow(unchecked);

        assertThatThrownBy(() -> aspect.around(pjp, annotation)).isSameAs(unchecked);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void successCommitsAndReturnsTheResult() throws Throwable {
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.around(pjp, annotation)).isEqualTo("ok");
        verify(connection).commit();
    }
}
