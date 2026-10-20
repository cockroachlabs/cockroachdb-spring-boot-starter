package com.cockroachlabs.data.aop;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.multiregion.RegionResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 2)
public class CockroachDBAspect {

    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final PlatformTransactionManager txManager;
    private final CockroachDBProperties properties;
    private final CockroachDBMetrics metrics;
    private final JdbcTemplate jdbcTemplate;
    private final RegionResolver regionResolver;

    public CockroachDBAspect(PlatformTransactionManager txManager,
                             CockroachDBProperties properties,
                             CockroachDBMetrics metrics,
                             JdbcTemplate jdbcTemplate,
                             RegionResolver regionResolver) {
        this.txManager = txManager;
        this.properties = properties;
        this.metrics = metrics;
        this.jdbcTemplate = jdbcTemplate;
        this.regionResolver = regionResolver;
    }

    @Around("@annotation(cockroachDB)")
    public Object around(ProceedingJoinPoint pjp,
                         CockroachDB cockroachDB) throws Throwable {

        CockroachDBProperties.Retry defaults = properties.getRetry();

        int maxRetries = resolve(cockroachDB.maxRetries(), defaults.getMaxRetries());
        long delay = resolve(cockroachDB.delay(), defaults.getDelay());
        double multiplier = resolve(cockroachDB.multiplier(), defaults.getMultiplier());
        long maxDelay = resolve(cockroachDB.maxDelay(), defaults.getMaxDelay());
        long jitter = defaults.getJitter();

        String region = resolveRegion(cockroachDB.region());

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setIsolationLevel(cockroachDB.isolation().value());
        txTemplate.setReadOnly(cockroachDB.readOnly());

        long start = System.nanoTime();
        int attempt = 0;
        long currentDelay = delay;

        while (true) {
            try {
                Object result = txTemplate.execute(status -> {
                    setSessionRegion(region);
                    try {
                        return pjp.proceed();
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
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

    private String resolveRegion(String annotationRegion) {
        if (annotationRegion != null && !annotationRegion.isBlank()) {
            return validateRegion(annotationRegion);
        }

        return RegionContext.current()
                .filter(r -> !r.isBlank())
                .map(this::validateRegion)
                .orElseGet(() -> {
                    if (regionResolver != null) {
                        String r = regionResolver.resolve();
                        if (r != null && !r.isBlank()) return validateRegion(r);
                    }
                    String def = properties.getRegion().getDefaultRegion();
                    return (def != null && !def.isBlank()) ? validateRegion(def) : null;
                });
    }

    private void setSessionRegion(String region) {
        if (region == null || region.isBlank()) return;
        jdbcTemplate.execute("SET crdb_region = '" + region + "'");
    }

    private String validateRegion(String region) {
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

    private int resolve(int annotationVal, int propertyVal) {
        return annotationVal >= 0 ? annotationVal : propertyVal;
    }

    private long resolve(long annotationVal, long propertyVal) {
        return annotationVal >= 0 ? annotationVal : propertyVal;
    }

    private double resolve(double annotationVal, double propertyVal) {
        return annotationVal >= 0 ? annotationVal : propertyVal;
    }
}
