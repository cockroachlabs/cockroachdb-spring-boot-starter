package com.example.retry;

import org.springframework.boot.SpringApplication;

/**
 * Starts the application against a CockroachDB container and runs the transfer demo.
 * Run it with {@code ./gradlew bootTestRun}.
 */
public class TestRetryApplication {

    public static void main(String[] args) {
        SpringApplication.from(RetryApplication::main)
                .with(TestcontainersConfiguration.class, TransferDemo.class)
                .run(args);
    }
}
