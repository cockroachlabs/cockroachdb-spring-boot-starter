package com.cockroachlabs.data.continuum.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent / MCP facade over {@code @AgentTool}-annotated providers.
 *
 * <pre>{@code
 * CrdbAgent agent = CrdbAgent.from(new CrdbOpsTools(storage, jdbc));
 * List<ToolDescriptor> tools = agent.listTools();      // MCP tools/list
 * Object result = agent.invoke("health", Map.of());    // MCP tools/call
 * }</pre>
 *
 * <p>Least-privilege: destructive tools are excluded unless
 * {@link #allowDestructive(boolean)} is enabled. A full MCP stdio/HTTP server is
 * out of scope for this increment; adapt {@link #listTools()}/{@link #invoke}
 * onto your transport of choice.
 */
public final class CrdbAgent {

    private final List<Object> providers;
    private boolean allowDestructive;
    private ToolRegistry registry;

    private CrdbAgent(List<Object> providers) {
        this.providers = providers;
    }

    public static CrdbAgent from(Object... providers) {
        return new CrdbAgent(List.of(providers));
    }

    /** Allow destructive tools to be registered. Call before listing/invoking. */
    public CrdbAgent allowDestructive(boolean allow) {
        this.allowDestructive = allow;
        this.registry = null; // force rebuild with the new policy
        return this;
    }

    /** MCP {@code tools/list}. */
    public List<ToolDescriptor> listTools() {
        return registry().descriptors();
    }

    /** Alias for {@link #listTools()}. */
    public List<ToolDescriptor> tools() {
        return listTools();
    }

    public ToolDescriptor tool(String name) {
        return registry().descriptors().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** MCP {@code tools/call}. */
    public Object invoke(String name, Map<String, Object> args) {
        return registry().invoke(name, args);
    }

    private ToolRegistry registry() {
        if (registry == null) {
            ToolRegistry r = new ToolRegistry();
            for (Object p : providers) {
                r.register(p, allowDestructive);
            }
            registry = r;
        }
        return registry;
    }
}
