package com.cockroachlabs.data.continuum;

import com.cockroachlabs.data.continuum.annotation.VirtualCluster;
import com.cockroachlabs.data.continuum.annotation.Workload;
import com.cockroachlabs.data.continuum.aop.VirtualClusterAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live test that the {@link VirtualClusterAspect} actually applies Cockroach
 * vCluster session tags to the transaction: {@code application_name} and the
 * workload QoS. Verified by reading {@code SHOW ...} inside the annotated method.
 */
@Testcontainers
class VClusterTaggingIT {

    @Container
    static final CockroachContainer crdb =
            new CockroachContainer("cockroachdb/cockroach:latest-v24.3");

    /** Reads the session settings that the aspect should have applied. */
    static class Probe {

        private final JdbcTemplate jdbc;

        Probe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        record Result(String qos, String appName) {
        }

        @VirtualCluster("analytics")
        @Workload(Workload.Priority.LOW)
        public Result analyticsLow() {
            return new Result(
                    jdbc.queryForObject("SHOW default_transaction_quality_of_service", String.class),
                    jdbc.queryForObject("SHOW application_name", String.class));
        }

        @VirtualCluster("oltp")
        @Workload(Workload.Priority.HIGH)
        public String oltpHighQos() {
            return jdbc.queryForObject("SHOW default_transaction_quality_of_service", String.class);
        }
    }

    private Probe proxy;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Probe(jdbc));
        factory.addAspect(new VirtualClusterAspect(txm, jdbc));
        proxy = factory.getProxy();
    }

    @Test
    void appliesApplicationNameAndBackgroundQos() {
        Probe.Result r = proxy.analyticsLow();
        assertThat(r.qos()).isEqualTo("background");
        assertThat(r.appName()).isEqualTo("analytics");
    }

    @Test
    void mapsHighPriorityToCriticalQos() {
        assertThat(proxy.oltpHighQos()).isEqualTo("critical");
    }
}
