package com.cockroachlabs.data.continuum.plenum;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Read-only Cockroach Plenum storage insights.
 *
 * <p>Surfaces usage-based storage signals — per-table estimated row counts and,
 * where the running version exposes it, approximate on-disk size — using only
 * customer-safe {@code SHOW} surfaces (never {@code crdb_internal}, which is
 * restricted). Version-tolerant: signals a given version does not expose are
 * returned as {@link OptionalLong#empty()} rather than raising.
 */
public class StorageInsights {

    private final JdbcTemplate jdbcTemplate;

    public StorageInsights(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Summarize storage for every table in the current database. */
    public List<TableStorage> summary() {
        Map<String, Long> sizes = tableSizes();
        List<TableStorage> out = new ArrayList<>();
        List<Map<String, Object>> tables = safeQuery("SHOW TABLES");
        for (Map<String, Object> row : tables) {
            String schema = str(row, "schema_name", "public");
            String table = str(row, "table_name", null);
            if (table == null) {
                continue;
            }
            out.add(new TableStorage(
                    schema,
                    table,
                    optional(asLong(row.get("estimated_row_count"))),
                    optional(sizes.get(table))));
        }
        return out;
    }

    /**
     * Best-effort approximate on-disk size (bytes) per table, aggregated from
     * {@code SHOW RANGES ... WITH TABLES, DETAILS}. Fully defensive: any failure
     * or missing column yields an empty map.
     */
    private Map<String, Long> tableSizes() {
        Map<String, Long> map = new HashMap<>();
        String db = currentDatabase();
        String sql = "SHOW RANGES FROM DATABASE " + quoteIdent(db) + " WITH TABLES, DETAILS";
        for (Map<String, Object> row : safeQuery(sql)) {
            String table = str(row, "table_name", null);
            Long size = asLong(row.get("range_size"));
            if (table != null && size != null) {
                map.merge(table, size, Long::sum);
            }
        }
        return map;
    }

    private String currentDatabase() {
        try {
            String db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
            return db != null ? db : "defaultdb";
        } catch (RuntimeException e) {
            return "defaultdb";
        }
    }

    private List<Map<String, Object>> safeQuery(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (RuntimeException e) {
            // Optional signal — this version/shape differs; degrade gracefully.
            return List.of();
        }
    }

    private static String str(Map<String, Object> row, String key, String dflt) {
        Object v = row.get(key);
        return v != null ? v.toString() : dflt;
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OptionalLong optional(Long v) {
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
