package com.cockroachlabs.data.continuum.autoconfigure;

import com.cockroachlabs.data.autoconfigure.CockroachDBAutoConfiguration;
import com.cockroachlabs.data.continuum.aegis.AegisClient;
import com.cockroachlabs.data.continuum.aegis.AegisExhaust;
import com.cockroachlabs.data.continuum.aegis.HttpAegisClient;
import com.cockroachlabs.data.continuum.aegis.SimulatedAegisClient;
import com.cockroachlabs.data.continuum.agent.CrdbAgent;
import com.cockroachlabs.data.continuum.agent.CrdbOpsTools;
import com.cockroachlabs.data.continuum.aop.VirtualClusterAspect;
import com.cockroachlabs.data.continuum.plenum.StorageInsights;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Auto-configuration for the Cockroach Continuum surfaces: vCluster tagging,
 * Plenum storage insights, Aegis (simulated by default), and the agent/MCP tool
 * surface. Runs after {@link CockroachDBAutoConfiguration} so the shared
 * {@code JdbcTemplate} is available.
 */
@AutoConfiguration(after = CockroachDBAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(CockroachDBContinuumProperties.class)
public class CockroachDBContinuumAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VirtualClusterAspect virtualClusterAspect(PlatformTransactionManager txManager,
                                                     JdbcTemplate jdbcTemplate) {
        return new VirtualClusterAspect(txManager, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public StorageInsights cockroachStorageInsights(JdbcTemplate jdbcTemplate) {
        return new StorageInsights(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cockroachdb.continuum.aegis", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AegisClient aegisClient(CockroachDBContinuumProperties props) {
        CockroachDBContinuumProperties.Aegis a = props.getAegis();
        if (a.getEndpoint() != null && !a.getEndpoint().isBlank()) {
            return new HttpAegisClient(a.getEndpoint(), a.getToken());
        }
        return new SimulatedAegisClient();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AegisClient.class)
    public AegisExhaust aegisExhaust(AegisClient aegisClient) {
        return new AegisExhaust(aegisClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cockroachdb.continuum.agent", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public CrdbOpsTools crdbOpsTools(JdbcTemplate jdbcTemplate, StorageInsights storageInsights) {
        return new CrdbOpsTools(jdbcTemplate, storageInsights);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CrdbOpsTools.class)
    public CrdbAgent crdbAgent(CrdbOpsTools crdbOpsTools, CockroachDBContinuumProperties props) {
        return CrdbAgent.from(crdbOpsTools).allowDestructive(props.getAgent().isAllowDestructive());
    }
}
