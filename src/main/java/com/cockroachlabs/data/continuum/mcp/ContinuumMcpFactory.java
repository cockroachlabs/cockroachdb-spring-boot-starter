package com.cockroachlabs.data.continuum.mcp;

/**
 * Factory for {@link McpClient}: returns a {@link SimulatedMcpClient} when the
 * client is disabled or has no endpoint, otherwise an {@link HttpMcpClient}.
 */
public final class ContinuumMcpFactory {

    private ContinuumMcpFactory() {
    }

    public static McpClient create(McpClient.Config config) {
        if (config == null || !config.enabled() || config.endpoint().isBlank()) {
            return new SimulatedMcpClient();
        }
        return HttpMcpClient.create(config.endpoint(), config.token());
    }
}
