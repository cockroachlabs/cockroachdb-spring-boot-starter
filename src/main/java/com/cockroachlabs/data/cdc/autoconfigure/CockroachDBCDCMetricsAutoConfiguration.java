package com.cockroachlabs.data.cdc.autoconfigure;

import com.cockroachlabs.data.cdc.metrics.CDCMetrics;
import com.cockroachlabs.data.cdc.metrics.MicrometerCDCMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = CockroachDBCDCAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "cockroachdb.cdc", name = "enabled", havingValue = "true")
public class CockroachDBCDCMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CDCMetrics.class)
    public CDCMetrics cdcMicrometerMetrics(MeterRegistry registry) {
        return new MicrometerCDCMetrics(registry);
    }
}
