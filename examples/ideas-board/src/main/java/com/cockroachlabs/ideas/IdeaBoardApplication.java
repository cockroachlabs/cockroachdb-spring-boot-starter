package com.cockroachlabs.ideas;

import com.cockroachlabs.data.test.CockroachDBConcurrencyRunner;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class IdeaBoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdeaBoardApplication.class, args);
    }

    @Bean
    CommandLineRunner demo(IdeaRepository repository, IdeaService service) {
        return args -> {
            // Seed a single idea that 50 threads will all fight to upvote.
            UUID id = UUID.randomUUID();
            repository.save(new Idea(id, "Add dark mode", 0));

            int threads = 50;
            int iterations = 1;

            // tag::runner[]
            // --- WITHOUT retry: plain @Transactional under SERIALIZABLE ---
            CockroachDBConcurrencyRunner.Result plain =
                    CockroachDBConcurrencyRunner.run(threads, iterations,
                            () -> service.upvoteWithoutRetry(id));

            System.out.println("=== @Transactional only (no retry) ===");
            printResult(plain);
            System.out.println("Final vote count: " + service.currentVotes(id)
                    + " (expected " + threads + " — anything less means lost updates)");

            // Reset and run again, this time with automatic retry.
            service.resetVotes(id);

            // --- WITH retry: @CockroachDBTransactional retries 40001 conflicts automatically ---
            CockroachDBConcurrencyRunner.Result retried =
                    CockroachDBConcurrencyRunner.run(threads, iterations,
                            () -> service.upvote(id));

            System.out.println();
            System.out.println("=== @CockroachDBTransactional (automatic retry) ===");
            printResult(retried);
            System.out.println("Final vote count: " + service.currentVotes(id)
                    + " (expected exactly " + threads + ", zero failures)");
            // end::runner[]
        };
    }

    private static void printResult(CockroachDBConcurrencyRunner.Result r) {
        System.out.printf(
                "threads=%d iterationsPerThread=%d totalOperations=%d successes=%d "
                        + "failures=%d durationMs=%d opsPerSecond=%.1f%n",
                r.threads(),
                r.iterationsPerThread(),
                r.totalOperations(),
                r.successes(),
                r.failures(),
                r.durationMs(),
                r.opsPerSecond());
        if (!r.errors().isEmpty()) {
            System.out.println("First error: " + r.errors().get(0));
        }
    }
}
