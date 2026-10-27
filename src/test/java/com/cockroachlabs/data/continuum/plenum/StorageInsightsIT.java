package com.cockroachlabs.data.continuum.plenum;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live Cockroach Plenum StorageInsights test against a Testcontainers CockroachDB.
 */
@Testcontainers
class StorageInsightsIT {

    @Container
    static final CockroachContainer crdb =
            new CockroachContainer("cockroachdb/cockroach:v26.3.3");

    @Test
    void summaryIncludesCreatedTableWithoutUsingCrdbInternal() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE widgets (id INT PRIMARY KEY, name STRING)");
        jdbc.execute("INSERT INTO widgets (id, name) VALUES (1, 'a'), (2, 'b')");

        List<TableStorage> summary = new StorageInsights(jdbc).summary();

        assertThat(summary).isNotEmpty();
        assertThat(summary).anySatisfy(t -> {
            assertThat(t.table()).isEqualTo("widgets");
            // rows/bytes are version-dependent optional signals (e.g. v24.3 omits
            // estimated_row_count); when present they must be non-negative, and the
            // reader must never throw regardless of which signals a version exposes.
            t.rows().ifPresent(r -> assertThat(r).isGreaterThanOrEqualTo(0L));
            t.bytes().ifPresent(b -> assertThat(b).isGreaterThanOrEqualTo(0L));
        });
    }
}
