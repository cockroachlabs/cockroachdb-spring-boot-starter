package com.cockroachlabs.data.continuum.cloud;

import com.cockroachlabs.data.continuum.cloud.CloudClient.VirtualCluster;
import com.cockroachlabs.data.continuum.cloud.actuator.ContinuumCloudEndpoint;
import com.cockroachlabs.data.continuum.cloud.actuator.ContinuumCloudHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VirtualClusterManagerTest {

    @Test
    void createDropIssueValidatedDDL() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VirtualClusterManager m = new VirtualClusterManager(jdbc);
        m.create("analytics");
        m.drop("reporting");
        verify(jdbc).execute("CREATE VIRTUAL CLUSTER analytics");
        verify(jdbc).execute("DROP VIRTUAL CLUSTER reporting");
    }

    @Test
    void listReturnsRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(new VirtualCluster("analytics", "3", "analytics", null)));
        VirtualClusterManager m = new VirtualClusterManager(jdbc);
        List<VirtualCluster> vcs = m.list();
        assertThat(vcs).hasSize(1);
        assertThat(vcs.get(0).name()).isEqualTo("analytics");
    }

    @Test
    void rejectsBadNames() {
        VirtualClusterManager m = new VirtualClusterManager(mock(JdbcTemplate.class));
        for (String bad : List.of("", "1abc", "drop table users;", "has space", "a--b")) {
            assertThatThrownBy(() -> m.create(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void routingConnStringAddsCClusterOption() {
        String out = VirtualClusterManager.routingConnString(
                "jdbc:postgresql://host:26257/defaultdb?sslmode=verify-full", "analytics");
        assertThat(out).contains("options=-ccluster%3Danalytics");
        assertThat(out).contains("sslmode=verify-full");
        assertThatThrownBy(() -> VirtualClusterManager.routingConnString("jdbc:postgresql://h/db", "bad name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actuatorEndpointAndHealthReportSimulatedMode() {
        SimulatedCloudClient cloud = new SimulatedCloudClient();
        @SuppressWarnings("unchecked")
        ObjectProvider<VirtualClusterManager> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);

        Map<String, Object> out = new ContinuumCloudEndpoint(cloud, none).continuum();
        assertThat(out).containsEntry("mode", "simulated");
        assertThat(out).containsKey("clusters");

        Health h = new ContinuumCloudHealthIndicator(cloud).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("mode", "simulated");
    }
}
