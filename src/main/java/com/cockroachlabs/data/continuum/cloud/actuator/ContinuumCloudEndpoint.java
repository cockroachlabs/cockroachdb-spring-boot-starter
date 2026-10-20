package com.cockroachlabs.data.continuum.cloud.actuator;

import com.cockroachlabs.data.continuum.cloud.CloudClient;
import com.cockroachlabs.data.continuum.cloud.SimulatedCloudClient;
import com.cockroachlabs.data.continuum.cloud.VirtualClusterManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator endpoint {@code /actuator/continuum} that surfaces the Cockroach
 * Continuum control-plane state: whether the client is live or simulated, the
 * visible clusters, and (when a {@link VirtualClusterManager} is present) the
 * virtual clusters. Read-only.
 */
@Endpoint(id = "continuum")
public class ContinuumCloudEndpoint {

    private final CloudClient cloud;
    private final ObjectProvider<VirtualClusterManager> vclusters;

    public ContinuumCloudEndpoint(CloudClient cloud, ObjectProvider<VirtualClusterManager> vclusters) {
        this.cloud = cloud;
        this.vclusters = vclusters;
    }

    @ReadOperation
    public Map<String, Object> continuum() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", cloud instanceof SimulatedCloudClient ? "simulated" : "live");
        try {
            out.put("clusters", cloud.listClusters());
        } catch (RuntimeException e) {
            out.put("clustersError", e.getMessage());
        }
        VirtualClusterManager m = vclusters.getIfAvailable();
        if (m != null) {
            try {
                out.put("virtualClusters", m.list());
            } catch (RuntimeException e) {
                out.put("virtualClustersError", e.getMessage());
            }
        }
        return out;
    }
}
