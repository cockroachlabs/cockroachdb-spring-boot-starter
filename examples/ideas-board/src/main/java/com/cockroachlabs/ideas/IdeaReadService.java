package com.cockroachlabs.ideas;

import com.cockroachlabs.data.annotation.FollowerRead;
import java.util.List;
import org.springframework.stereotype.Service;

// Spring-managed so the @FollowerRead aspect can wrap topIdeas().

/**
 * Read-only leaderboard served from a historical snapshot. {@code @FollowerRead} runs
 * {@code topIdeas()} in its own read-only transaction at {@code follower_read_timestamp()}
 * (~4.2s in the past), so the nearest replica can serve it without contacting the
 * leaseholder. The trade-off is slight staleness: ideas written in the last few seconds
 * may not appear yet.
 */
@Service
public class IdeaReadService {

    private final IdeaRepository repository;

    public IdeaReadService(IdeaRepository repository) {
        this.repository = repository;
    }

    // tag::follower-read[]
    @FollowerRead
    public List<Idea> topIdeas() {
        return repository.findAllByOrderByVotesDesc();
    }
    // end::follower-read[]
}
