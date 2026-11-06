package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import com.cockroachlabs.data.ai.VectorSearchResult;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves "find similar ideas" on a single CockroachDB node using deterministic,
 * hand-built 3-dimensional vectors — no embedding model. Four docs are stored on
 * distinct axes; a query aligned with one of them returns the nearest docs in
 * cosine-similarity order.
 *
 * <p>Skips (does not fail) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SemanticSearchIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", crdb::getJdbcUrl);
        registry.add("spring.datasource.username", crdb::getUsername);
        registry.add("spring.datasource.password", crdb::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Turn on the starter's AI module so VectorSearchTemplate is auto-configured.
        registry.add("cockroachdb.ai.enabled", () -> "true");
        registry.add("cockroachdb.ai.vector-search.enable-vector-index", () -> "true");
    }

    @Autowired
    VectorSearchTemplate vectorSearchTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    SemanticIdeaService service;

    // Four ideas with hand-built 3-D vectors.
    private final UUID cats = UUID.randomUUID();     // [1, 0, 0]
    private final UUID kittens = UUID.randomUUID();  // [0.9, 0.1, 0] — close to cats
    private final UUID dogs = UUID.randomUUID();     // [0, 1, 0]
    private final UUID sky = UUID.randomUUID();      // [0, 0, 1]

    @BeforeEach
    void seed() {
        service = new SemanticIdeaService(vectorSearchTemplate, jdbcTemplate);
        service.ensureSchema();
        jdbcTemplate.execute("DELETE FROM idea_docs");
        service.store(new IdeaDoc(cats, "cats are great", new float[] {1f, 0f, 0f}));
        service.store(new IdeaDoc(kittens, "kittens are cute", new float[] {0.9f, 0.1f, 0f}));
        service.store(new IdeaDoc(dogs, "dogs are loyal", new float[] {0f, 1f, 0f}));
        service.store(new IdeaDoc(sky, "the sky is blue", new float[] {0f, 0f, 1f}));
    }

    // tag::vector-it[]
    @Test
    void findsNearestIdeasInSimilarityOrder() {
        // Query vector points straight along the "cats" axis.
        float[] query = {1f, 0f, 0f};

        List<VectorSearchResult<Object>> results = service.findSimilar(query, 2);

        assertThat(results).hasSize(2);
        // Exact match first, the near-parallel "kittens" vector second.
        assertThat(results.get(0).id()).hasToString(cats.toString());
        assertThat(results.get(1).id()).hasToString(kittens.toString());
        // Distances come back in ascending (nearest-first) order.
        assertThat(results.get(0).distance()).isLessThanOrEqualTo(results.get(1).distance());
    }
    // end::vector-it[]
}
