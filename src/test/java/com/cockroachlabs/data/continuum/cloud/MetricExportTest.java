package com.cockroachlabs.data.continuum.cloud;

import com.cockroachlabs.data.continuum.cloud.CloudClient.MetricExportConfig;
import com.cockroachlabs.data.continuum.cloud.CloudClient.MetricExportTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricExportTest {

    @Test
    void datadogBuildsSiteAndApiKey() {
        MetricExportConfig cfg = MetricExport.datadog("key-1", "US1");
        assertThat(cfg.target()).isEqualTo(MetricExportTarget.DATADOG);
        assertThat(cfg.settings()).containsEntry("api_key", "key-1").containsEntry("site", "US1");
    }

    @Test
    void cloudWatchBuildsFieldsAndOmitsBlankRegion() {
        MetricExportConfig cfg = MetricExport.cloudWatch("arn:aws:iam::1:role/R", "crdb-logs", "us-east-1");
        assertThat(cfg.target()).isEqualTo(MetricExportTarget.CLOUDWATCH);
        assertThat(cfg.settings())
                .containsEntry("role_arn", "arn:aws:iam::1:role/R")
                .containsEntry("log_group_name", "crdb-logs")
                .containsEntry("target_region", "us-east-1");

        assertThat(MetricExport.cloudWatch("arn", "lg", "").settings()).doesNotContainKey("target_region");
    }

    @Test
    void prometheusHasNoSettings() {
        MetricExportConfig cfg = MetricExport.prometheus();
        assertThat(cfg.target()).isEqualTo(MetricExportTarget.PROMETHEUS);
        assertThat(cfg.settings()).isEmpty();
    }
}
