package com.cockroachlabs.data.ai.autoconfigure;

import javax.sql.DataSource;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import com.cockroachlabs.data.ai.aop.SemanticSearchAspect;
import com.cockroachlabs.data.ai.aop.VectorSearchAspect;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import com.cockroachlabs.data.ddl.VectorIndexHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "cockroachdb.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CockroachDBAIProperties.class)
public class CockroachDBAIAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CockroachDBAIAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AIMetrics aiMetrics() {
        return new AIMetrics.InMemory();
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorSearchTemplate vectorSearchTemplate(JdbcTemplate jdbcTemplate,
                                                     PlatformTransactionManager txManager,
                                                     AIMetrics metrics,
                                                     CockroachDBAIProperties properties) {
        if (properties.getVectorSearch().isEnableVectorIndex()) {
            try {
                jdbcTemplate.execute(VectorIndexHelper.enableVectorIndexSetting());
                log.info("Enabled feature.vector_index.enabled cluster setting");
            } catch (Exception ex) {
                log.warn("Could not enable feature.vector_index.enabled: {}", ex.getMessage());
            }
        }
        return new VectorSearchTemplate(jdbcTemplate, txManager, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorSearchAspect vectorSearchAspect(VectorSearchTemplate vectorSearchTemplate,
                                                  AIMetrics metrics) {
        return new VectorSearchAspect(vectorSearchTemplate, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public SemanticSearchAspect semanticSearchAspect(VectorSearchTemplate vectorSearchTemplate,
                                                      AIMetrics metrics) {
        return new SemanticSearchAspect(vectorSearchTemplate, metrics);
    }
}
