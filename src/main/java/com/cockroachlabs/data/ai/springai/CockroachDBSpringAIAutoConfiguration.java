package com.cockroachlabs.data.ai.springai;

import com.cockroachlabs.data.ai.autoconfigure.CockroachDBAIAutoConfiguration;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(
        after = CockroachDBAIAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
        }
)
@ConditionalOnClass({VectorStore.class, EmbeddingModel.class})
@ConditionalOnProperty(prefix = "cockroachdb.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CockroachDBVectorStoreProperties.class)
public class CockroachDBSpringAIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CockroachDBFilterExpressionConverter cockroachDBFilterExpressionConverter() {
        return new CockroachDBFilterExpressionConverter();
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    @ConditionalOnBean({EmbeddingModel.class, JdbcTemplate.class, PlatformTransactionManager.class})
    public CockroachDBVectorStore cockroachDBVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            PlatformTransactionManager txManager,
            AIMetrics aiMetrics,
            CockroachDBFilterExpressionConverter filterConverter,
            CockroachDBVectorStoreProperties properties) {

        CockroachDBVectorStore store = CockroachDBVectorStore.builder(jdbcTemplate, embeddingModel)
                .transactionManager(txManager)
                .metrics(aiMetrics)
                .filterConverter(filterConverter)
                .tableName(properties.getTableName())
                .contentColumn(properties.getContentColumn())
                .embeddingColumn(properties.getEmbeddingColumn())
                .metadataColumn(properties.getMetadataColumn())
                .distanceMetric(properties.getDistanceType())
                .beamSize(properties.getBeamSize())
                .initializeSchema(properties.isInitializeSchema())
                .createVectorIndex(properties.isCreateVectorIndex())
                .dimensions(properties.getDimensions())
                .build();

        store.afterPropertiesSet();
        return store;
    }
}
