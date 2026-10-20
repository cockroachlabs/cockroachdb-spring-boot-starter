package com.cockroachlabs.data.continuum.cloud.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Cockroach Continuum Cloud control-plane client.
 *
 * <pre>
 * cockroachdb.continuum.cloud.enabled  = true   # expose a CloudClient bean
 * cockroachdb.continuum.cloud.base-url =        # blank → https://cockroachlabs.cloud/api/v1
 * cockroachdb.continuum.cloud.api-key  =        # blank → SimulatedCloudClient
 * </pre>
 */
@ConfigurationProperties("cockroachdb.continuum.cloud")
public class CockroachDBContinuumCloudProperties {

    private boolean enabled = true;
    private String baseUrl = "";
    private String apiKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
