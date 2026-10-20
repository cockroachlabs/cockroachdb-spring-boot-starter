package com.cockroachlabs.data.continuum.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes the annotated method as an agent-callable tool (e.g. for Cockroach
 * Aegis or any MCP client). Only {@code @AgentTool} methods are exposed, and
 * {@link #destructive()} tools are hidden unless the caller explicitly opts in —
 * least privilege by default.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentTool {

    /** The tool name exposed to agents (MCP tool name). */
    String value();

    /** Human-readable description surfaced in the tool listing. */
    String description() default "";

    /** Whether this tool mutates state; hidden unless destructive tools are allowed. */
    boolean destructive() default false;
}
