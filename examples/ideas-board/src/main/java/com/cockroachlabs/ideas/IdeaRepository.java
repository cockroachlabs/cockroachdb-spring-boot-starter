package com.cockroachlabs.ideas;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// tag::repo[]
public interface IdeaRepository extends JpaRepository<Idea, UUID> {
}
// end::repo[]
