package com.cockroachlabs.ideas;

import com.cockroachlabs.data.annotation.CockroachDB;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdeaService {

    private final IdeaRepository repository;

    public IdeaService(IdeaRepository repository) {
        this.repository = repository;
    }

    // tag::plain[]
    @Transactional
    public void upvoteWithoutRetry(UUID id) {
        Idea idea = repository.findById(id).orElseThrow(); // <1>
        idea.setVotes(idea.getVotes() + 1);                // <2>
        repository.save(idea);                             // <3>
    }
    // end::plain[]

    // tag::retry[]
    @CockroachDB // <1>
    public void upvote(UUID id) {
        Idea idea = repository.findById(id).orElseThrow();
        idea.setVotes(idea.getVotes() + 1);
        repository.save(idea);
    }
    // end::retry[]

    @Transactional(readOnly = true)
    public int currentVotes(UUID id) {
        return repository.findById(id).orElseThrow().getVotes();
    }

    @Transactional
    public void resetVotes(UUID id) {
        Idea idea = repository.findById(id).orElseThrow();
        idea.setVotes(0);
        repository.save(idea);
    }
}
