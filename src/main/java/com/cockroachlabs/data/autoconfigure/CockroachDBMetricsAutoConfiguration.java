package com.cockroachlabs.data.autoconfigure;

import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.metrics.MicrometerCockroachDBMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(
        prefix = "cockroachdb.metrics",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CockroachDBMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CockroachDBMetrics.class)
    public CockroachDBMetrics cockroachDBMicrometerMetrics(MeterRegistry registry) {
        return new MicrometerCockroachDBMetrics(registry);
    }
}
