package com.cockroachlabs.data.cdc.autoconfigure;

import com.cockroachlabs.data.cdc.handler.CDCHandlerRegistry;
import com.cockroachlabs.data.cdc.handler.CDCMethodInvoker;
import com.cockroachlabs.data.cdc.handler.CDCWebhookController;
import com.cockroachlabs.data.cdc.metrics.CDCMetrics;
import com.cockroachlabs.data.cdc.test.CDCTestHarness;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CockroachDBCDCAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnProperty(prefix = "cockroachdb.cdc", name = "enabled", havingValue = "true")
public class CockroachDBCDCJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CDCMethodInvoker cdcMethodInvoker(ObjectMapper objectMapper) {
        return new CDCMethodInvoker(objectMapper);
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    public CDCWebhookController cdcWebhookController(
            CDCHandlerRegistry registry,
            CDCMethodInvoker invoker,
            CockroachDBCDCProperties properties,
            ObjectMapper objectMapper,
            CDCMetrics metrics) {
        return new CDCWebhookController(registry, invoker, properties, objectMapper, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public CDCTestHarness cdcTestHarness(CDCHandlerRegistry registry, CDCMethodInvoker invoker) {
        return new CDCTestHarness(registry, invoker);
    }
}
