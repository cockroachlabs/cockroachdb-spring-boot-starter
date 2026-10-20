package com.cockroachlabs.data.cdc.autoconfigure;

import com.cockroachlabs.data.cdc.handler.CDCHandlerRegistry;
import com.cockroachlabs.data.cdc.metrics.CDCMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CockroachDBCDCMetricsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "cockroachdb.cdc", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CockroachDBCDCProperties.class)
public class CockroachDBCDCAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public static CDCHandlerRegistry cdcHandlerRegistry() {
        return new CDCHandlerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(CDCMetrics.class)
    public CDCMetrics cdcMetrics() {
        return new CDCMetrics.InMemory();
    }
}
