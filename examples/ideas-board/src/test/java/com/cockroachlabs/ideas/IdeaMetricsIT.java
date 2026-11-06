package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the starter publishes transaction metrics through Micrometer. Each {@code @CockroachDB}
 * call records a transaction, so after a handful of upvotes the starter's
 * {@code cockroachdb.transactions.*} meters are registered with a positive count.
 *
 * <p>Skips (does not fail) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IdeaMetricsIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", crdb::getJdbcUrl);
        registry.add("spring.datasource.username", crdb::getUsername);
        registry.add("spring.datasource.password", crdb::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    IdeaService service;

    @Autowired
    IdeaRepository repository;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void upvotes_incrementTransactionMeters() {
        repository.deleteAll();
        UUID id = UUID.randomUUID();
        repository.save(new Idea(id, "Track transaction metrics", 0));

        for (int i = 0; i < 5; i++) {
            service.upvote(id);
        }

        // tag::metrics-it[]
        Counter total = meterRegistry.find("cockroachdb.transactions.total").counter();
        Counter success = meterRegistry.find("cockroachdb.transactions.success").counter();

        assertThat(total).as("cockroachdb.transactions.total meter registered").isNotNull();
        assertThat(total.count()).isPositive();
        assertThat(success).as("cockroachdb.transactions.success meter registered").isNotNull();
        assertThat(success.count()).isPositive();
        // end::metrics-it[]
    }
}
