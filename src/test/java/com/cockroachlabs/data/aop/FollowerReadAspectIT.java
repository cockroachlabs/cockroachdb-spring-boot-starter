package com.cockroachlabs.data.aop;

import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/**
 * Runs {@link FollowerReadAspect} against a real CockroachDB. A follower read is at
 * least ~4.8s stale, so a row inserted just before the call must not be visible.
 */
@Testcontainers
class FollowerReadAspectIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    static JdbcTemplate jdbc;
    static DataSourceTransactionManager txManager;
    static CockroachDBMetrics.InMemory metrics;
    static Catalog catalog;

    public static class Catalog {
        JdbcTemplate jdbc;

        @FollowerRead
        public Integer count() {
            return jdbc.queryForObject("SELECT count(*) FROM items", Integer.class);
        }

        @FollowerRead(secondsAgo = 5)
        public Integer countFiveSecondsAgo() {
            return jdbc.queryForObject("SELECT count(*) FROM items", Integer.class);
        }

        @FollowerRead(hoursAgo = 1)
        public Integer countAnHourAgo() {
            return jdbc.queryForObject("SELECT count(*) FROM items", Integer.class);
        }
    }

    @BeforeAll
    static void setUp() throws InterruptedException {
        var ds = new DriverManagerDataSource(crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        jdbc = new JdbcTemplate(ds);
        txManager = new DataSourceTransactionManager(ds);
        metrics = new CockroachDBMetrics.InMemory();

        jdbc.execute("CREATE TABLE items (id INT PRIMARY KEY)");
        // Let the follower-read timestamp move past the table's creation.
        Thread.sleep(8000);

        var target = new Catalog();
        target.jdbc = jdbc;
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new FollowerReadAspect(txManager, jdbc, metrics));
        catalog = factory.getProxy();
    }

    private static int insertRow() {
        int id = (int) (System.nanoTime() & 0x7fffffff);
        jdbc.update("INSERT INTO items VALUES (?)", id);
        return id;
    }

    private static int currentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM items", Integer.class);
    }

    @Test
    void defaultReadsHistoricalSnapshot() {
        insertRow();
        assertThat(catalog.count()).isLessThan(currentCount());
    }

    @Test
    void asOfIntervalReadsHistoricalSnapshot() {
        insertRow();
        assertThat(catalog.countFiveSecondsAgo()).isLessThan(currentCount());
    }

    @Test
    void asOfIntervalIsApplied() {
        // An hour ago the table didn't exist yet, so the query can only fail.
        assertThatThrownBy(catalog::countAnHourAgo)
                .hasMessageContaining("does not exist");
    }

    @Test
    void nestedInReadWriteTransactionDoesNotJoinIt() {
        int before = currentCount();
        Integer staleCount = new TransactionTemplate(txManager).execute(status -> {
            jdbc.update("INSERT INTO items VALUES (?)", -1);
            Integer n = catalog.count();
            jdbc.update("INSERT INTO items VALUES (?)", -2);
            return n;
        });

        // The follower read ran in its own transaction and didn't see the outer writes...
        assertThat(staleCount).isLessThanOrEqualTo(before);
        // ...and the outer transaction stayed read-write and committed both rows.
        assertThat(currentCount()).isEqualTo(before + 2);
    }

    @Test
    void jdbcTemplateOnAnotherDataSourceFailsInsteadOfReadingCurrentData() {
        // Same database, but a different DataSource object: the transaction manager binds
        // its connection to txManager's DataSource, so this JdbcTemplate would run the SET
        // on a connection of its own.
        var otherDs = new DriverManagerDataSource(crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        var target = new Catalog();
        target.jdbc = jdbc;
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new FollowerReadAspect(txManager, new JdbcTemplate(otherDs), metrics));
        Catalog misconfigured = factory.getProxy();

        assertThatIllegalStateException()
                .isThrownBy(misconfigured::count)
                .withMessageContaining("same DataSource");
    }

    @Test
    void recordsFollowerReads() {
        long before = metrics.getFollowerReadCount();
        catalog.count();
        catalog.countFiveSecondsAgo();
        assertThat(metrics.getFollowerReadCount()).isEqualTo(before + 2);
    }
}
