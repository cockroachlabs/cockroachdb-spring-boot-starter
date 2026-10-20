package com.cockroachlabs.data.continuum.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflects over {@code @AgentTool}-annotated methods on provider objects and
 * exposes them as described, invokable tools. Least-privilege: destructive tools
 * are only registered when {@code allowDestructive} is set.
 */
public class ToolRegistry {

    private record Registered(Object target, Method method, ToolDescriptor descriptor) {
    }

    private final Map<String, Registered> tools = new LinkedHashMap<>();

    /** Register every eligible {@code @AgentTool} method on {@code provider}. */
    public void register(Object provider, boolean allowDestructive) {
        for (Method m : provider.getClass().getMethods()) {
            AgentTool t = m.getAnnotation(AgentTool.class);
            if (t == null || (t.destructive() && !allowDestructive)) {
                continue;
            }
            String desc = t.description().isBlank() ? t.value() : t.description();
            tools.put(t.value(),
                    new Registered(provider, m, new ToolDescriptor(t.value(), desc, schemaFor(m))));
        }
    }

    /** All registered tool descriptors, in registration order (MCP tools/list). */
    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> out = new ArrayList<>();
        for (Registered r : tools.values()) {
            out.add(r.descriptor());
        }
        return out;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** Invoke a tool by name with named arguments (MCP tools/call). */
    public Object invoke(String name, Map<String, Object> args) {
        Registered r = tools.get(name);
        if (r == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        Parameter[] params = r.method().getParameters();
        Object[] call = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Object v = args == null ? null : args.get(params[i].getName());
            call[i] = coerce(v, params[i].getType());
        }
        try {
            return r.method().invoke(r.target(), call);
        } catch (Exception e) {
            throw new RuntimeException("Tool invocation failed: " + name, e);
        }
    }

    private static String schemaFor(Method m) {
        StringBuilder props = new StringBuilder();
        StringBuilder required = new StringBuilder();
        Parameter[] params = m.getParameters();
        for (int i = 0; i < params.length; i++) {
            String pname = params[i].getName();
            if (props.length() > 0) {
                props.append(",");
                required.append(",");
            }
            props.append("\"").append(pname).append("\":{\"type\":\"")
                    .append(jsonType(params[i].getType())).append("\"}");
            required.append("\"").append(pname).append("\"");
        }
        return "{\"type\":\"object\",\"properties\":{" + props
                + "},\"required\":[" + required + "]}";
    }

    private static String jsonType(Class<?> t) {
        if (t == boolean.class || t == Boolean.class) {
            return "boolean";
        }
        if (t == int.class || t == long.class || t == short.class
                || t == Integer.class || t == Long.class || t == Short.class) {
            return "integer";
        }
        if (t == double.class || t == float.class || Number.class.isAssignableFrom(t)) {
            return "number";
        }
        return "string";
    }

    private static Object coerce(Object v, Class<?> t) {
        if (v == null) {
            return t.isPrimitive() ? defaultPrimitive(t) : null;
        }
        if (t.isInstance(v)) {
            return v;
        }
        String s = v.toString().trim();
        if (t == String.class) {
            return v.toString();
        }
        if (t == boolean.class || t == Boolean.class) {
            return Boolean.parseBoolean(s);
        }
        if (t == int.class || t == Integer.class) {
            return Integer.parseInt(s);
        }
        if (t == long.class || t == Long.class) {
            return Long.parseLong(s);
        }
        if (t == double.class || t == Double.class) {
            return Double.parseDouble(s);
        }
        return v;
    }

    private static Object defaultPrimitive(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0d;
        }
        return 0;
    }
}
