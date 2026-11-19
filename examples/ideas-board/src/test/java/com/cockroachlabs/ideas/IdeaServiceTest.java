package com.cockroachlabs.ideas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Fast unit test of {@link IdeaService}'s read-modify-write logic with a mocked
 * repository and no database.
 *
 * <p>Note: the {@code @CockroachDBTransactional} auto-retry is applied by Spring AOP (the
 * starter's retry advisor) and is only active when the service is a Spring-managed proxy. A
 * service constructed with {@code new} here has no advice, so this test cannot exercise retries — that is proven
 * end-to-end in {@link IdeaServiceConcurrencyIT}. What this test does verify is the
 * service's own data logic: that each upvote increments by exactly one and that reset
 * zeroes the count.
 */
class IdeaServiceTest {

    // tag::unit-retry[]
    @Test
    void upvoteIncrementsVotesByOne() {
        IdeaRepository repository = org.mockito.Mockito.mock(IdeaRepository.class);
        IdeaService service = new IdeaService(repository);

        UUID id = UUID.randomUUID();
        Idea idea = new Idea(id, "Add dark mode", 41);
        when(repository.findById(id)).thenReturn(Optional.of(idea));

        service.upvote(id);

        ArgumentCaptor<Idea> saved = ArgumentCaptor.forClass(Idea.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getVotes()).isEqualTo(42);
    }
    // end::unit-retry[]

    @Test
    void upvoteWithoutRetryIncrementsVotesByOne() {
        IdeaRepository repository = org.mockito.Mockito.mock(IdeaRepository.class);
        IdeaService service = new IdeaService(repository);

        UUID id = UUID.randomUUID();
        Idea idea = new Idea(id, "Add dark mode", 0);
        when(repository.findById(id)).thenReturn(Optional.of(idea));

        service.upvoteWithoutRetry(id);

        verify(repository).save(any(Idea.class));
        assertThat(idea.getVotes()).isEqualTo(1);
    }

    @Test
    void resetVotesZeroesTheCount() {
        IdeaRepository repository = org.mockito.Mockito.mock(IdeaRepository.class);
        IdeaService service = new IdeaService(repository);

        UUID id = UUID.randomUUID();
        Idea idea = new Idea(id, "Add dark mode", 99);
        when(repository.findById(id)).thenReturn(Optional.of(idea));

        service.resetVotes(id);

        ArgumentCaptor<Idea> saved = ArgumentCaptor.forClass(Idea.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getVotes()).isZero();
    }
}
