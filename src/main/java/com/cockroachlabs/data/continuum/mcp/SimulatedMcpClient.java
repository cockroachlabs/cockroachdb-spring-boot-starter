package com.cockroachlabs.data.continuum.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link McpClient} for development and tests. Advertises a small canned
 * tool set and echoes tool calls, so code that drives an MCP server runs offline.
 * Records every call for assertions via {@link #calls()}.
 */
public class SimulatedMcpClient implements McpClient {

    private final List<Map.Entry<String, Map<String, Object>>> calls = new ArrayList<>();

    @Override
    public List<Tool> listTools() {
        return List.of(
                new Tool("ping", "Ping", "Health check; returns pong."),
                new Tool("echo", "Echo", "Echoes its arguments as JSON text."));
    }

    @Override
    public CallResult callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        calls.add(Map.entry(name, args));
        String text = "ping".equals(name) ? "pong" : toJson(args);
        return new CallResult(List.of(new Content("text", text)), false);
    }

    /** Recorded (name, arguments) tool calls, in order. */
    public List<Map.Entry<String, Map<String, Object>>> calls() {
        return List.copyOf(calls);
    }

    // Minimal JSON for the echo payload (simulated-only; strings/numbers/booleans/maps).
    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(toJson(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return b.append('}').toString();
        }
        return toJson(String.valueOf(value));
    }
}
