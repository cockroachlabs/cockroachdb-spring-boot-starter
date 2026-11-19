package com.cockroachlabs.ideas;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// tag::repo[]
public interface IdeaRepository extends JpaRepository<Idea, UUID> {

    // tag::leaderboard-finder[]
    List<Idea> findAllByOrderByVotesDesc();
    // end::leaderboard-finder[]
}
// end::repo[]
