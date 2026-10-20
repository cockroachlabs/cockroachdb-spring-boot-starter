package com.cockroachlabs.data.cdc.autoconfigure;

import com.cockroachlabs.data.cdc.factory.CockroachDBCDCFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration(after = CockroachDBCDCAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "cockroachdb.cdc", name = "enabled", havingValue = "true")
public class CockroachDBCDCJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cockroachdb.cdc.factory", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    public CockroachDBCDCFactory cockroachDBCDCFactory(JdbcTemplate jdbcTemplate) {
        return new CockroachDBCDCFactory(jdbcTemplate);
    }
}
