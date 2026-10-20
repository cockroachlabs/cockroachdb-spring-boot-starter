package com.cockroachlabs.data.continuum.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrdbAgentTest {

    /** A provider with safe and destructive tools for exercising the registry. */
    static class Tools {

        @AgentTool(value = "ping", description = "Liveness ping")
        public String ping() {
            return "pong";
        }

        @AgentTool(value = "echo")
        public String echo(String msg) {
            return msg;
        }

        @AgentTool(value = "wipe", destructive = true)
        public String wipe() {
            return "wiped";
        }
    }

    @Test
    void listsSafeToolsAndHidesDestructiveByDefault() {
        CrdbAgent agent = CrdbAgent.from(new Tools());
        assertThat(agent.listTools()).extracting(ToolDescriptor::name)
                .containsExactlyInAnyOrder("ping", "echo");
        // Schema is a JSON object descriptor.
        assertThat(agent.tool("echo").inputSchemaJson()).contains("\"type\":\"object\"");
    }

    @Test
    void invokesNoArgTool() {
        CrdbAgent agent = CrdbAgent.from(new Tools());
        assertThat(agent.invoke("ping", Map.of())).isEqualTo("pong");
    }

    @Test
    void destructiveToolAvailableWhenAllowed() {
        CrdbAgent agent = CrdbAgent.from(new Tools()).allowDestructive(true);
        List<String> names = agent.listTools().stream().map(ToolDescriptor::name).toList();
        assertThat(names).contains("wipe");
        assertThat(agent.invoke("wipe", Map.of())).isEqualTo("wiped");
    }

    @Test
    void unknownToolThrows() {
        CrdbAgent agent = CrdbAgent.from(new Tools());
        assertThatThrownBy(() -> agent.invoke("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
