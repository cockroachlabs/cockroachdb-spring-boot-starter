package com.cockroachlabs.data.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.CannotAcquireLockException;

import static org.mockito.Mockito.*;

/**
 * A mocked JDBC {@link DataSource}, so a real {@code DataSourceTransactionManager} can run
 * transactions whose commits, rollbacks, isolation and SQL statements are observable.
 */
public final class MockJdbc {

    public final DataSource dataSource = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final Statement statement = mock(Statement.class);

    public MockJdbc() {
        try {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SQL run through {@code Statement.execute}, such as {@code SET crdb_region}. */
    public List<String> executedSql() {
        return mockingDetails(statement).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("execute"))
                .map(i -> (String) i.getArgument(0))
                .toList();
    }

    int commits() {
        return calls(connection, "commit");
    }

    int rollbacks() {
        return calls(connection, "rollback");
    }

    /** Isolation levels set on the connection, in order. */
    List<Integer> isolationLevels() {
        return mockingDetails(connection).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("setTransactionIsolation"))
                .map(i -> (Integer) i.getArgument(0))
                .toList();
    }

    private static int calls(Object mock, String method) {
        return (int) mockingDetails(mock).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(method) && i.getArguments().length == 0)
                .count();
    }

    /** What CockroachDB's 40001 looks like after Spring's JDBC exception translation. */
    public static CannotAcquireLockException serializationFailure() {
        return new CannotAcquireLockException("restart transaction", new SQLException("simulated", "40001"));
    }
}
