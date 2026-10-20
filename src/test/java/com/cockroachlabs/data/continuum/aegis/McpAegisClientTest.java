package com.cockroachlabs.data.continuum.aegis;

import com.cockroachlabs.data.continuum.aegis.McpAegisClient.ToolNames;
import com.cockroachlabs.data.continuum.mcp.McpClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpAegisClientTest {

    /** Scripts CallResults by tool name and records calls. */
    private static final class FakeMcp implements McpClient {
        private final Map<String, CallResult> results;
        final List<Map.Entry<String, Map<String, Object>>> calls = new ArrayList<>();

        FakeMcp(Map<String, CallResult> results) {
            this.results = results;
        }

        @Override
        public List<Tool> listTools() {
            return List.of();
        }

        @Override
        public CallResult callTool(String name, Map<String, Object> arguments) {
            calls.add(Map.entry(name, arguments == null ? Map.of() : arguments));
            return results.getOrDefault(name, new CallResult(List.of(), false));
        }
    }

    private static McpClient.CallResult text(String s) {
        return new McpClient.CallResult(List.of(new McpClient.Content("text", s)), false);
    }

    @Test
    void findingsAndRecommendationsParsed() {
        FakeMcp mcp = new FakeMcp(Map.of(
                "list_findings",
                text("[{\"id\":\"f1\",\"severity\":\"warning\",\"title\":\"hot\",\"detail\":\"d\"}]"),
                "list_recs",
                text("[{\"id\":\"r1\",\"title\":\"split\",\"detail\":\"ALTER\"}]")));
        McpAegisClient c = new McpAegisClient(mcp, new ToolNames("list_findings", "list_recs", ""));

        assertThat(c.findings()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("f1");
            assertThat(f.severity()).isEqualTo("warning");
        });
        assertThat(c.recommendations()).singleElement()
                .extracting(Recommendation::detail).isEqualTo("ALTER");
    }

    @Test
    void emitSendsArgs() {
        FakeMcp mcp = new FakeMcp(Map.of());
        McpAegisClient c = new McpAegisClient(mcp, new ToolNames("", "", "emit_event"));
        c.emit(new AppEvent("retry", "svc", Map.of("attempt", 2), 99L));
        assertThat(mcp.calls).singleElement().satisfies(e -> {
            assertThat(e.getKey()).isEqualTo("emit_event");
            assertThat(e.getValue()).containsEntry("kind", "retry").containsEntry("epochMillis", 99L);
        });
    }

    @Test
    void unconfiguredToolThrows() {
        McpAegisClient c = new McpAegisClient(new FakeMcp(Map.of()), new ToolNames("", "", ""));
        assertThatThrownBy(c::findings).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(c::recommendations).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.emit(new AppEvent("x", "y", Map.of(), 0L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toolErrorSurfaced() {
        FakeMcp mcp = new FakeMcp(Map.of(
                "list_findings",
                new McpClient.CallResult(List.of(new McpClient.Content("text", "boom")), true)));
        McpAegisClient c = new McpAegisClient(mcp, new ToolNames("list_findings", "", ""));
        assertThatThrownBy(c::findings).hasMessageContaining("reported error");
    }
}
