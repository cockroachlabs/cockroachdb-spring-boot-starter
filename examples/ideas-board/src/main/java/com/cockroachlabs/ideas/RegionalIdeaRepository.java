package com.cockroachlabs.ideas;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionalIdeaRepository extends JpaRepository<RegionalIdea, UUID> {
}
