package com.example.retry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.cockroachdb.CockroachContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    CockroachContainer cockroach() {
        return new CockroachContainer("cockroachdb/cockroach:v26.3.3");
    }
}
