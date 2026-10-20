package com.cockroachlabs.data.cdc.factory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class CockroachDBCDCFactory {

    private static final Logger log = LoggerFactory.getLogger(CockroachDBCDCFactory.class);

    private final JdbcTemplate jdbcTemplate;

    public CockroachDBCDCFactory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ChangefeedInfo create(ChangefeedSpec spec) {
        String sql = spec.toSQL();
        log.info("Creating changefeed: {}", sql);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql);
        long jobId = ((Number) result.get("job_id")).longValue();

        log.info("Changefeed created with job_id={}", jobId);
        return new ChangefeedInfo(jobId, "running", sql, spec.getWebhookUrl(), Instant.now(), Instant.now());
    }

    public void pause(long jobId) {
        log.info("Pausing changefeed job_id={}", jobId);
        jdbcTemplate.execute("PAUSE JOB " + jobId);
    }

    public void resume(long jobId) {
        log.info("Resuming changefeed job_id={}", jobId);
        jdbcTemplate.execute("RESUME JOB " + jobId);
    }

    public void cancel(long jobId) {
        log.info("Cancelling changefeed job_id={}", jobId);
        jdbcTemplate.execute("CANCEL JOB " + jobId);
    }

    public List<ChangefeedInfo> list() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SHOW CHANGEFEED JOBS");
        List<ChangefeedInfo> results = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            results.add(new ChangefeedInfo(
                    ((Number) row.get("job_id")).longValue(),
                    stringOrNull(row.get("status")),
                    stringOrNull(row.get("description")),
                    stringOrNull(row.get("sink_uri")),
                    toInstant(row.get("created")),
                    toInstant(row.get("modified"))
            ));
        }
        return results;
    }

    public ChangefeedInfo getJob(long jobId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SHOW CHANGEFEED JOB " + jobId);
        return new ChangefeedInfo(
                ((Number) row.get("job_id")).longValue(),
                stringOrNull(row.get("status")),
                stringOrNull(row.get("description")),
                stringOrNull(row.get("sink_uri")),
                toInstant(row.get("created")),
                toInstant(row.get("modified"))
        );
    }

    private static String stringOrNull(Object value) {
        return value != null ? value.toString() : null;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof Instant inst) return inst;
        return null;
    }
}
