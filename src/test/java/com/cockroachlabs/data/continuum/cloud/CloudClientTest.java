package com.cockroachlabs.data.continuum.cloud;

import com.cockroachlabs.data.continuum.cloud.CloudClient.Cluster;
import com.cockroachlabs.data.continuum.cloud.CloudClient.MetricExportConfig;
import com.cockroachlabs.data.continuum.cloud.CloudClient.MetricExportTarget;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CloudClientTest {

    @Test
    void simulatedServesClustersAndRecordsMetricExport() {
        SimulatedCloudClient c = new SimulatedCloudClient();
        List<Cluster> clusters = c.listClusters();
        assertThat(clusters).hasSize(1);
        assertThat(c.getCluster(clusters.get(0).id()).name()).isEqualTo("dev-cluster");
        assertThatThrownBy(() -> c.getCluster("missing")).isInstanceOf(IllegalArgumentException.class);

        c.configureMetricExport(clusters.get(0).id(),
                new MetricExportConfig(MetricExportTarget.DATADOG, Map.of("site", "us5", "api_key", "dd")));
        assertThat(c.metricExports().get(clusters.get(0).id()).target()).isEqualTo(MetricExportTarget.DATADOG);

        assertThatThrownBy(() -> c.configureMetricExport("x", new MetricExportConfig(null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpListClustersParsesSnakeCaseAndSendsBearer() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://cloud")
                .defaultHeader("Authorization", "Bearer sk-123");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://cloud/clusters"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk-123"))
                .andRespond(withSuccess(
                        "{\"clusters\":[{\"id\":\"c1\",\"name\":\"prod\",\"plan\":\"STANDARD\"," +
                                "\"state\":\"CREATED\",\"cloud_provider\":\"AWS\",\"regions\":[\"us-east-1\"]}]}",
                        MediaType.APPLICATION_JSON));

        HttpCloudClient c = new HttpCloudClient(builder.build());
        List<Cluster> clusters = c.listClusters();
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).cloudProvider()).isEqualTo("AWS");
        assertThat(clusters.get(0).regions()).containsExactly("us-east-1");
        server.verify();
    }

    @Test
    void httpMetricExportPostsToTargetPath() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cloud")
                .defaultHeader("Authorization", "Bearer sk");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://cloud/clusters/c1/metricexport/datadog"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK));

        HttpCloudClient c = new HttpCloudClient(builder.build());
        c.configureMetricExport("c1", new MetricExportConfig(MetricExportTarget.DATADOG, Map.of("site", "us5")));
        server.verify();
    }
}
