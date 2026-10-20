package com.cockroachlabs.data.continuum.cloud.actuator;

import com.cockroachlabs.data.continuum.cloud.CloudClient;
import com.cockroachlabs.data.continuum.cloud.SimulatedCloudClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Health indicator for the Cockroach Continuum Cloud control-plane connection.
 * Reports UP when clusters can be listed (details include whether the client is
 * simulated or live and the cluster count), DOWN with the error otherwise.
 */
public class ContinuumCloudHealthIndicator implements HealthIndicator {

    private final CloudClient cloud;

    public ContinuumCloudHealthIndicator(CloudClient cloud) {
        this.cloud = cloud;
    }

    @Override
    public Health health() {
        String mode = cloud instanceof SimulatedCloudClient ? "simulated" : "live";
        try {
            int count = cloud.listClusters().size();
            return Health.up()
                    .withDetail("mode", mode)
                    .withDetail("clusters", count)
                    .build();
        } catch (RuntimeException e) {
            return Health.down(e).withDetail("mode", mode).build();
        }
    }
}
