package com.cockroachlabs.data.continuum.cloud.autoconfigure;

import com.cockroachlabs.data.continuum.cloud.CloudClient;
import com.cockroachlabs.data.continuum.cloud.VirtualClusterManager;
import com.cockroachlabs.data.continuum.cloud.actuator.ContinuumCloudEndpoint;
import com.cockroachlabs.data.continuum.cloud.actuator.ContinuumCloudHealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Continuum Cloud Actuator surfaces ({@code /actuator/continuum}
 * endpoint and a health indicator) when Spring Boot Actuator and the health
 * contributor API are on the classpath and a {@link CloudClient} bean exists.
 *
 * <p>Class presence is checked by name so this configuration loads harmlessly in
 * apps without Actuator.
 */
@AutoConfiguration(after = CockroachDBContinuumCloudAutoConfiguration.class)
@ConditionalOnClass(name = {
        "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        "org.springframework.boot.health.contributor.HealthIndicator"
})
@ConditionalOnBean(CloudClient.class)
public class CockroachDBContinuumCloudActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ContinuumCloudEndpoint continuumCloudEndpoint(CloudClient cloud,
                                                         ObjectProvider<VirtualClusterManager> vclusters) {
        return new ContinuumCloudEndpoint(cloud, vclusters);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContinuumCloudHealthIndicator continuumCloudHealthIndicator(CloudClient cloud) {
        return new ContinuumCloudHealthIndicator(cloud);
    }
}
