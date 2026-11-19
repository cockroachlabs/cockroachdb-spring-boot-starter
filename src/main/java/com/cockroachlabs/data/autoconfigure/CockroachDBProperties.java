package com.cockroachlabs.data.autoconfigure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cockroachdb")
public class CockroachDBProperties {

    private Retry retry = new Retry();

    private Region region = new Region();

    private Metrics metrics = new Metrics();

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public static class Retry {

        /** Retries after a serialization conflict (SQLSTATE 40001) before giving up. */
        private int maxRetries = 3;

        /** Milliseconds to wait before the first retry. */
        private long delay = 100;

        /** Factor applied to the wait after each retry. */
        private double multiplier = 2.0;

        /** Longest wait between retries, in milliseconds. */
        private long maxDelay = 5000;

        /** Random milliseconds added to or subtracted from each wait. */
        private long jitter = 50;

        /**
         * Whether plain {@code @Transactional} methods are also retried on SQLSTATE 40001.
         * {@code @CockroachDBTransactional} methods are always retried.
         */
        private boolean applyToTransactionalMethods = true;

        public boolean isApplyToTransactionalMethods() {
            return applyToTransactionalMethods;
        }

        public void setApplyToTransactionalMethods(boolean applyToTransactionalMethods) {
            this.applyToTransactionalMethods = applyToTransactionalMethods;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getDelay() {
            return delay;
        }

        public void setDelay(long delay) {
            this.delay = delay;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(long maxDelay) {
            this.maxDelay = maxDelay;
        }

        public long getJitter() {
            return jitter;
        }

        public void setJitter(long jitter) {
            this.jitter = jitter;
        }
    }

    public static class Region {

        private String defaultRegion = "";

        private boolean validate = false;

        private List<String> allowed = List.of();

        private ResolverType resolver = ResolverType.CONTEXT;

        private String headerName = "X-CockroachDB-Region";

        public enum ResolverType {
            STATIC, HEADER, CONTEXT, BEAN
        }

        public String getDefaultRegion() {
            return defaultRegion;
        }

        public void setDefaultRegion(String defaultRegion) {
            this.defaultRegion = defaultRegion;
        }

        public boolean isValidate() {
            return validate;
        }

        public void setValidate(boolean validate) {
            this.validate = validate;
        }

        public List<String> getAllowed() {
            return allowed;
        }

        public void setAllowed(List<String> allowed) {
            this.allowed = allowed;
        }

        public ResolverType getResolver() {
            return resolver;
        }

        public void setResolver(ResolverType resolver) {
            this.resolver = resolver;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }
    }

    public static class Metrics {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
