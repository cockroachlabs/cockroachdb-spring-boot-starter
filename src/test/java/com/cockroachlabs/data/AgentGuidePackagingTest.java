package com.cockroachlabs.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The guide for coding agents, docs/agents/AGENTS.md, ships inside the jar at the
 * documented path, unchanged.
 */
class AgentGuidePackagingTest {

    static final String RESOURCE = "/META-INF/cockroachdb-spring-boot-starter/AGENTS.md";

    @Test
    void agentGuideIsOnTheClasspathAtTheDocumentedPath() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            String packaged = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(packaged).isEqualTo(Files.readString(Path.of("docs/agents/AGENTS.md")));
        }
    }
}
