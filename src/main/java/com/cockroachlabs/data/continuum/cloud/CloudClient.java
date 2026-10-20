package com.cockroachlabs.data.continuum.cloud;

import java.util.List;
import java.util.Map;

/**
 * Client for the CockroachDB Cloud control-plane API (part of Cockroach
 * Continuum): list clusters and configure metric export.
 *
 * <p>This is an optional, separate surface from the core transaction path. Like
 * {@code AegisClient}, a {@link SimulatedCloudClient} is used whenever no API key
 * is configured, so applications compile and run without a Continuum
 * organization; set {@code cockroachdb.continuum.cloud.api-key} to target the
 * real API (default base URL {@code https://cockroachlabs.cloud/api/v1}).
 * Authentication uses a service-account secret key sent as a Bearer token.
 */
public interface CloudClient {

    /** Returns the clusters visible to the configured service account. */
    List<Cluster> listClusters();

    /** Returns a single cluster by its control-plane ID. */
    Cluster getCluster(String clusterId);

    /** Enables a metric-export sink for a cluster. */
    void configureMetricExport(String clusterId, MetricExportConfig config);

    /** A CockroachDB Cloud cluster (control-plane view). */
    record Cluster(String id, String name, String plan, String state,
                   String cloudProvider, List<String> regions) {
    }

    /**
     * A Cockroach virtual cluster. {@code routingId} is the identifier used when
     * connecting (which differs from the control-plane cluster ID); see
     * {@link VirtualClusterManager#routingConnString}.
     */
    record VirtualCluster(String name, String id, String routingId, String state) {
    }

    /** A Cloud metric-export sink. */
    enum MetricExportTarget {
        DATADOG, CLOUDWATCH, PROMETHEUS;

        /** The lowercase path segment used by the Cloud API. */
        public String path() {
            return name().toLowerCase();
        }
    }

    /**
     * Metric-export configuration. {@code settings} carries the target-specific
     * fields (datadog: {@code site}, {@code api_key}; cloudwatch: {@code role_arn},
     * {@code target_region}, {@code log_group_name}; etc.).
     */
    record MetricExportConfig(MetricExportTarget target, Map<String, Object> settings) {
    }
}
