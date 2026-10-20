package com.cockroachlabs.data.continuum.cloud.autoconfigure;

import com.cockroachlabs.data.autoconfigure.CockroachDBAutoConfiguration;
import com.cockroachlabs.data.continuum.cloud.CloudClient;
import com.cockroachlabs.data.continuum.cloud.HttpCloudClient;
import com.cockroachlabs.data.continuum.cloud.SimulatedCloudClient;
import com.cockroachlabs.data.continuum.cloud.VirtualClusterManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Auto-configuration for the Cockroach Continuum Cloud control-plane surface.
 * Exposes a {@link CloudClient} (simulated until an API key is set) and, when a
 * {@link JdbcTemplate} is available, a {@link VirtualClusterManager} for
 * virtual-cluster lifecycle. Optional and independent of the core transaction
 * path.
 */
@AutoConfiguration(after = CockroachDBAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(CockroachDBContinuumCloudProperties.class)
@ConditionalOnProperty(prefix = "cockroachdb.continuum.cloud", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CockroachDBContinuumCloudAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CloudClient cockroachCloudClient(CockroachDBContinuumCloudProperties props) {
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            return HttpCloudClient.create(props.getBaseUrl(), props.getApiKey());
        }
        return new SimulatedCloudClient();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public VirtualClusterManager cockroachVirtualClusterManager(JdbcTemplate jdbcTemplate) {
        return new VirtualClusterManager(jdbcTemplate);
    }
}
