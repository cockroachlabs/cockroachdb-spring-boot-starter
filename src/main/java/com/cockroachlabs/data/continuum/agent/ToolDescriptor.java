package com.cockroachlabs.data.continuum.agent;

/**
 * A described, agent-callable tool.
 *
 * @param name            the tool name (MCP tool name)
 * @param description     human-readable description
 * @param inputSchemaJson JSON Schema (as a string) for the tool's arguments
 */
public record ToolDescriptor(String name, String description, String inputSchemaJson) {
}
