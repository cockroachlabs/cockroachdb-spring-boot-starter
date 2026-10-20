package com.cockroachlabs.data.continuum.agent;

import com.cockroachlabs.data.continuum.plenum.StorageInsights;
import com.cockroachlabs.data.continuum.plenum.TableStorage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Built-in, safe (non-destructive) {@link AgentTool}s exposing common CockroachDB
 * operations to an agent: connectivity, Cockroach Plenum storage usage, and a
 * pointer to retry metrics.
 */
public class CrdbOpsTools {

    private final JdbcTemplate jdbcTemplate;
    private final StorageInsights storageInsights;

    public CrdbOpsTools(JdbcTemplate jdbcTemplate, StorageInsights storageInsights) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageInsights = storageInsights;
    }

    @AgentTool(value = "health", description = "Check CockroachDB connectivity")
    public String health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "ok";
        } catch (RuntimeException e) {
            return "down: " + e.getMessage();
        }
    }

    @AgentTool(value = "storage_summary",
            description = "Per-table Cockroach Plenum storage usage (rows/bytes)")
    public String storageSummary() {
        List<TableStorage> tables = storageInsights.summary();
        if (tables.isEmpty()) {
            return "no tables";
        }
        return tables.stream()
                .map(t -> t.qualifiedName()
                        + " rows=" + t.rows().orElse(-1L)
                        + " bytes=" + t.bytes().orElse(-1L))
                .collect(Collectors.joining("; "));
    }

    @AgentTool(value = "retry_stats",
            description = "Where to find serialization-retry metrics")
    public String retryStats() {
        return "Retry metrics are published via Micrometer under cockroachdb.* "
                + "— query /actuator/metrics.";
    }
}
