package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Multi-region proof for {@code @RegionalByRow} + region-scoped writes. A single
 * Testcontainers node cannot {@code ADD REGION}, so this test runs ONLY against a real
 * multi-region cluster and SKIPS cleanly otherwise (it never fails the suite).
 *
 * <p>Enable by exporting {@code CRDB_MULTIREGION=true} and pointing the datasource at a
 * multi-region cluster via {@code CRDB_URL} / {@code CRDB_USER} / {@code CRDB_PASSWORD}.
 * The target database must already have {@code us-east1} and {@code us-west1} as regions.
 */
// tag::region-gate[]
@EnabledIfEnvironmentVariable(named = "CRDB_MULTIREGION", matches = "true")
@SpringBootTest
class RegionalIdeaIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getenv().getOrDefault("CRDB_URL",
                        "jdbc:postgresql://localhost:26257/defaultdb?sslmode=disable"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("CRDB_USER", "root"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRDB_PASSWORD", ""));
        // The real cluster owns the schema; don't let Hibernate rewrite it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
    // end::region-gate[]

    @Autowired
    RegionalIdeaService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void ensureRegionalTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS regional_ideas ("
                + "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
                + "text STRING NOT NULL, votes INT NOT NULL DEFAULT 0)");
        try {
            jdbc.execute("ALTER TABLE regional_ideas SET LOCALITY REGIONAL BY ROW");
        } catch (RuntimeException alreadyRegional) {
            // Table is already REGIONAL BY ROW — nothing to do.
        }
        jdbc.execute("DELETE FROM regional_ideas");
    }

    // tag::region-it[]
    @Test
    void writesRowIntoTheChosenRegion() {
        RegionalIdea east = service.createInRegion("us-east1", "ship it from the east");
        RegionalIdea west = service.createInRegion("us-west1", "ship it from the west");

        String eastRegion = jdbc.queryForObject(
                "SELECT crdb_region::string FROM regional_ideas WHERE id = ?",
                String.class, east.getId());
        String westRegion = jdbc.queryForObject(
                "SELECT crdb_region::string FROM regional_ideas WHERE id = ?",
                String.class, west.getId());

        assertThat(eastRegion).isEqualTo("us-east1");
        assertThat(westRegion).isEqualTo("us-west1");
    }
    // end::region-it[]
}
