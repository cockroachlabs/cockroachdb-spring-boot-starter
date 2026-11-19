package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Same "3 concurrent persons" proof as {@link IdeaServiceConcurrencyIT}, but driven with a
 * raw {@link ExecutorService} + {@link Future} instead of the starter's
 * {@code CockroachDBConcurrencyRunner}. This shows the contention can be reproduced with
 * nothing but the JDK, and that {@code @CockroachDBTransactional} still retries every 40001 conflict so
 * all 60 votes land.
 *
 * <p>Skips (does not fail) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IdeaServiceExecutorIT {

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

    @Test
    void rawExecutorConcurrencyLandsEveryVote() throws Exception {
        int users = 3, clicksEach = 20; // 3 people * 20 clicks = 60 upvotes on one idea
        service.resetVotes(id);

        // tag::executor-concurrency[]
        ExecutorService pool = Executors.newFixedThreadPool(users);
        // A shared start gate is the critical part: without it, tasks submitted to a pool
        // tend to start as each thread becomes free and run nearly sequentially, so the
        // read-modify-write windows never overlap and you may see NO 40001 contention at all.
        // Holding every task at the gate, then releasing them together, forces real overlap.
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int u = 0; u < users; u++) {
                futures.add(pool.submit(() -> {
                    startGate.await(); // park until all tasks are ready
                    for (int c = 0; c < clicksEach; c++) {
                        service.upvote(id); // @CockroachDBTransactional retries the 40001 conflicts
                    }
                    return null;
                }));
            }

            startGate.countDown(); // release all users at once -> maximum contention

            // get() joins each task and re-throws any exception, so a failure fails the test.
            for (Future<?> f : futures) {
                f.get();
            }

            // Every concurrent vote counted, because @CockroachDBTransactional retried the conflicts.
            assertThat(service.currentVotes(id)).isEqualTo(users * clicksEach); // == 60
        } finally {
            pool.shutdownNow();
        }
        // end::executor-concurrency[]

        // Modern equivalent: CompletableFuture.runAsync(() -> service.upvote(id), pool) x N,
        // then CompletableFuture.allOf(cf1, cf2, ...).join().
    }
}
