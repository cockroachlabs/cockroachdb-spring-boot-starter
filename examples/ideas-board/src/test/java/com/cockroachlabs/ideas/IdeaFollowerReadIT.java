package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * Proves {@code @FollowerRead} serves the leaderboard from a historical snapshot and
 * returns ideas ordered by votes. Because the default follower read is ~4.2s in the past,
 * the test lets the seed data settle before reading.
 *
 * <p>Skips (does not fail) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IdeaFollowerReadIT {

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
    IdeaReadService readService;

    @Autowired
    IdeaRepository repository;

    // tag::follower-read-it[]
    @Test
    void topIdeas_returnsIdeasOrderedByVotesDescending() throws InterruptedException {
        repository.deleteAll();
        repository.save(new Idea(UUID.randomUUID(), "Dark mode", 3));
        repository.save(new Idea(UUID.randomUUID(), "CSV export", 10));
        repository.save(new Idea(UUID.randomUUID(), "Keyboard shortcuts", 7));

        // Follower reads are served as of follower_read_timestamp() (~4.2s ago), so let the
        // just-committed rows age past that bound before reading the historical snapshot.
        Thread.sleep(6_000);

        List<Idea> leaderboard = readService.topIdeas();

        assertThat(leaderboard).isNotEmpty();
        assertThat(leaderboard).extracting(Idea::getVotes).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
        assertThat(leaderboard.get(0).getText()).isEqualTo("CSV export");
    }
    // end::follower-read-it[]
}
