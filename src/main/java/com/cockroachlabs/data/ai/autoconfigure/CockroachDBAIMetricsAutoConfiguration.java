package com.cockroachlabs.data.ai.autoconfigure;

import com.cockroachlabs.data.ai.metrics.AIMetrics;
import com.cockroachlabs.data.ai.metrics.MicrometerAIMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = CockroachDBAIAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "cockroachdb.ai", name = "enabled", havingValue = "true")
public class CockroachDBAIMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AIMetrics.class)
    public AIMetrics aiMicrometerMetrics(MeterRegistry registry) {
        return new MicrometerAIMetrics(registry);
    }
}
