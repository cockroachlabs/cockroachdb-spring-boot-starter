package com.cockroachlabs.data.autoconfigure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cockroachdb")
public class CockroachDBProperties {

    private String isolation = "serializable";

    private Retry retry = new Retry();

    private Region region = new Region();

    private Metrics metrics = new Metrics();

    public String getIsolation() {
        return isolation;
    }

    public void setIsolation(String isolation) {
        this.isolation = isolation;
    }

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

        private int maxRetries = 3;

        private long delay = 100;

        private double multiplier = 2.0;

        private long maxDelay = 5000;

        private long jitter = 50;

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
