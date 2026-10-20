package com.cockroachlabs.data.continuum.aegis;

import com.cockroachlabs.data.continuum.mcp.McpClient;

import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapts an {@link McpClient} to the {@link AegisClient} interface.
 *
 * <p>Aegis is reachable read-only over the Cockroach Continuum Cloud MCP server.
 * This adapter calls the configured tools and parses their text content as JSON
 * into {@link Finding}/{@link Recommendation}. Tool names are required config
 * (discover them with {@link McpClient#listTools()}); an unset name throws rather
 * than guessing the server's tool contract.
 */
public class McpAegisClient implements AegisClient {

    private static final JsonParser JSON = JsonParserFactory.getJsonParser();

    private final McpClient mcp;
    private final ToolNames tools;

    public McpAegisClient(McpClient mcp, ToolNames tools) {
        this.mcp = mcp;
        this.tools = tools;
    }

    /**
     * Maps Aegis operations to the tool names an MCP server advertises. The
     * Continuum MCP tool contract is not pinned here; names differ by server.
     */
    public record ToolNames(String findings, String recommendations, String emit) {
        public ToolNames {
            findings = findings == null ? "" : findings;
            recommendations = recommendations == null ? "" : recommendations;
            emit = emit == null ? "" : emit;
        }
    }

    @Override
    public List<Finding> findings() {
        List<Map<String, Object>> rows = readList(tools.findings(), "findings");
        List<Finding> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new Finding(str(r, "id"), str(r, "severity"), str(r, "title"), str(r, "detail")));
        }
        return out;
    }

    @Override
    public List<Recommendation> recommendations() {
        List<Map<String, Object>> rows = readList(tools.recommendations(), "recommendations");
        List<Recommendation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new Recommendation(str(r, "id"), str(r, "title"), str(r, "detail")));
        }
        return out;
    }

    @Override
    public void emit(AppEvent event) {
        if (tools.emit().isBlank()) {
            throw new IllegalStateException("aegis: MCP tool name not configured: emit");
        }
        Map<String, Object> args = Map.of(
                "kind", event.kind(),
                "source", event.source(),
                "attrs", event.attrs() == null ? Map.of() : event.attrs(),
                "epochMillis", event.epochMillis());
        McpClient.CallResult res = mcp.callTool(tools.emit(), args);
        if (res.isError()) {
            throw new IllegalStateException("aegis: emit tool reported error: " + res.text());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String tool, String what) {
        if (tool == null || tool.isBlank()) {
            throw new IllegalStateException("aegis: MCP tool name not configured: " + what);
        }
        McpClient.CallResult res = mcp.callTool(tool, Map.of());
        if (res.isError()) {
            throw new IllegalStateException("aegis: " + what + " tool reported error: " + res.text());
        }
        String text = res.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Object> arr = JSON.parseList(text);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : arr) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}
