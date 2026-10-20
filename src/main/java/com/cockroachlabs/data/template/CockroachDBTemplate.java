package com.cockroachlabs.data.template;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.multiregion.RegionResolver;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class CockroachDBTemplate {

    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final TransactionTemplate txTemplate;
    private final CockroachDBProperties properties;
    private final CockroachDBMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final RegionResolver regionResolver;

    public CockroachDBTemplate(PlatformTransactionManager txManager,
                               CockroachDBProperties props,
                               CockroachDBMetrics metrics,
                               JdbcTemplate jdbcTemplate,
                               RegionResolver regionResolver) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.properties = props;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.regionResolver = regionResolver;
    }

    public <T> T execute(Supplier<T> action) {
        String region = resolveRegion();
        return executeWithRetry(txTemplate, action, region,
                properties.getRetry().getMaxRetries(),
                properties.getRetry().getDelay(),
                properties.getRetry().getMultiplier(),
                properties.getRetry().getMaxDelay(),
                properties.getRetry().getJitter());
    }

    public void executeWithoutResult(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    public <T> T executeReadCommitted(Supplier<T> action) {
        TransactionTemplate rcTemplate = new TransactionTemplate(
                txTemplate.getTransactionManager());
        rcTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return rcTemplate.execute(status -> action.get());
    }

    public RegionalExecution inRegion(String region) {
        return new RegionalExecution(validateRegion(region));
    }

    public class RegionalExecution {

        private final String region;

        RegionalExecution(String region) {
            this.region = region;
        }

        public <T> T execute(Supplier<T> action) {
            return executeWithRetry(txTemplate, action, region,
                    properties.getRetry().getMaxRetries(),
                    properties.getRetry().getDelay(),
                    properties.getRetry().getMultiplier(),
                    properties.getRetry().getMaxDelay(),
                    properties.getRetry().getJitter());
        }

        public void executeWithoutResult(Runnable action) {
            execute(() -> {
                action.run();
                return null;
            });
        }
    }

    <T> T executeWithRetry(TransactionTemplate txTemplate,
                           Supplier<T> action,
                           String region,
                           int maxRetries,
                           long delay,
                           double multiplier,
                           long maxDelay,
                           long jitter) {
        long start = System.nanoTime();
        int attempt = 0;
        long currentDelay = delay;

        while (true) {
            try {
                T result = txTemplate.execute(status -> {
                    setSessionRegion(region);
                    return action.get();
                });
                metrics.recordTransaction(start, attempt, true);
                return result;
            } catch (TransientDataAccessException ex) {
                if (!isSerializationError(ex) || attempt >= maxRetries) {
                    metrics.recordTransaction(start, attempt, false);
                    throw ex;
                }
                attempt++;
                sleep(currentDelay, jitter);
                currentDelay = Math.min((long) (currentDelay * multiplier), maxDelay);
            }
        }
    }

    String resolveRegion() {
        return RegionContext.current()
                .filter(r -> !r.isBlank())
                .orElseGet(() -> {
                    if (regionResolver != null) {
                        String r = regionResolver.resolve();
                        if (r != null && !r.isBlank()) return r;
                    }
                    return properties.getRegion().getDefaultRegion();
                });
    }

    void setSessionRegion(String region) {
        if (region == null || region.isBlank()) return;
        String validated = validateRegion(region);
        jdbcTemplate.execute("SET crdb_region = '" + validated + "'");
    }

    String validateRegion(String region) {
        if (region == null || region.isBlank()) return region;

        if (!REGION_PATTERN.matcher(region).matches()) {
            throw new IllegalArgumentException(
                    "Invalid region format: '" + region + "' (must match [a-z][a-z0-9-]*)");
        }

        CockroachDBProperties.Region regionProps = properties.getRegion();
        if (regionProps.isValidate()) {
            List<String> allowed = regionProps.getAllowed();
            if (!allowed.isEmpty() && !allowed.contains(region)) {
                throw new IllegalArgumentException(
                        "Region '" + region + "' not in allowed list: " + allowed);
            }
        }
        return region;
    }

    private boolean isSerializationError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sql
                    && "40001".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void sleep(long delay, long jitter) {
        try {
            long jitteredDelay = delay;
            if (jitter > 0) {
                jitteredDelay += ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
            }
            Thread.sleep(Math.max(0, jitteredDelay));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
