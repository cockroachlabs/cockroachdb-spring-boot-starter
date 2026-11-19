package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import com.cockroachlabs.data.test.CockroachDBConcurrencyRunner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The real "2-3 concurrent persons" proof against a live CockroachDB. Under SERIALIZABLE
 * isolation, concurrent read-modify-write upvotes collide (40001). Without retry some
 * votes are lost or fail; with {@code @CockroachDBTransactional} every vote is counted exactly.
 *
 * <p>Skips (does not fail) when Docker is unavailable.
 */
// tag::container[]
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IdeaServiceConcurrencyIT {

    @Container
    static final CockroachContainer crdb = new CockroachContainer(
            "cockroachdb/cockroach:v26.3.3");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", crdb::getJdbcUrl);
        registry.add("spring.datasource.username", crdb::getUsername);
        registry.add("spring.datasource.password", crdb::getPassword);
        // Let Hibernate create the `ideas` table from the entity mapping.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
    // end::container[]

    @Autowired
    IdeaService service;

    @Autowired
    IdeaRepository repository;

    private UUID id;

    @BeforeEach
    void seedOneIdea() {
        repository.deleteAll();
        id = UUID.randomUUID();
        repository.save(new Idea(id, "Ship the ideas board", 0));
    }

    // tag::concurrency-it[]
    @Test
    void withoutRetry_losesVotesUnderConcurrency() {
        // Un-protected baseline: three "people" each click upvote 20 times (60 ops) with
        // no retry. Under SERIALIZABLE, collisions either throw 40001 (failures) or lose
        // updates, so the count cannot reliably reach 60.
        service.resetVotes(id);

        var result = CockroachDBConcurrencyRunner.run(3, 20, () -> service.upvoteWithoutRetry(id));

        assertThat(result.failures() > 0 || service.currentVotes(id) < 60).isTrue();
    }

    @Test
    void withRetry_countsEveryVote() {
        // @CockroachDBTransactional auto-retries on 40001, so every concurrent vote lands: three people
        // clicking 20 times each yields exactly 60, with no failures.
        service.resetVotes(id);

        var result = CockroachDBConcurrencyRunner.run(3, 20, () -> service.upvote(id));

        assertThat(result.failures()).isZero();
        assertThat(service.currentVotes(id)).isEqualTo(60);
    }
    // end::concurrency-it[]
}
