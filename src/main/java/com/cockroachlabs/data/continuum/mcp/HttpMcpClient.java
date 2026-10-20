package com.cockroachlabs.data.continuum.mcp;

import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP-backed {@link McpClient} speaking the MCP Streamable HTTP transport (spec
 * 2025-06-18). Lazily performs the initialize handshake before the first tools
 * call, echoes the server-assigned {@code Mcp-Session-Id} and the
 * {@code MCP-Protocol-Version} header, and handles both {@code application/json} and
 * {@code text/event-stream} responses.
 *
 * <p>To stay independent of the host application's HTTP message converters and
 * Jackson version, responses are retrieved as text and parsed with Spring Boot's
 * {@link JsonParser}, and request bodies are written as compact JSON strings.
 * Construct with {@link #create(String, String)} in production, or pass a pre-built
 * {@link RestClient} (e.g. bound to a {@code MockRestServiceServer}) in tests.
 */
public class HttpMcpClient implements McpClient {

    /** The MCP revision this client negotiates. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final JsonParser JSON = JsonParserFactory.getJsonParser();

    private final RestClient rest;
    private final String clientName;
    private final String clientVersion;
    private final AtomicLong ids = new AtomicLong();
    private String sessionId;
    private boolean init;

    public HttpMcpClient(RestClient rest) {
        this(rest, "cockroachdb-spring", "0.1");
    }

    public HttpMcpClient(RestClient rest, String clientName, String clientVersion) {
        this.rest = rest;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    /** Builds a client posting to {@code endpoint} with a Bearer token. */
    public static HttpMcpClient create(String endpoint, String token) {
        RestClient rc = RestClient.builder()
                .baseUrl(endpoint)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
        return new HttpMcpClient(rc);
    }

    @Override
    public List<Tool> listTools() {
        ensureInit();
        Map<String, Object> result = call("tools/list", Map.of());
        List<Tool> out = new ArrayList<>();
        if (result.get("tools") instanceof List<?> arr) {
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new Tool(str(m, "name"), str(m, "title"), str(m, "description")));
                }
            }
        }
        return out;
    }

    @Override
    public CallResult callTool(String name, Map<String, Object> arguments) {
        ensureInit();
        Map<String, Object> result = call(
                "tools/call", Map.of("name", name, "arguments", arguments == null ? Map.of() : arguments));
        List<Content> content = new ArrayList<>();
        if (result.get("content") instanceof List<?> arr) {
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    content.add(new Content(str(m, "type"), str(m, "text")));
                }
            }
        }
        return new CallResult(content, Boolean.TRUE.equals(result.get("isError")));
    }

    private void ensureInit() {
        if (init) {
            return;
        }
        call("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", clientName, "version", clientVersion)));
        try {
            notifyServer("notifications/initialized");
        } catch (RuntimeException ignored) {
            // A rejected notification must not block us.
        }
        init = true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", ids.incrementAndGet());
        payload.put("method", method);
        payload.put("params", params);

        String text = post(payload);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("mcp: " + method + ": unexpected empty response");
        }
        Map<String, Object> env = JSON.parseMap(text);
        if (env.get("error") instanceof Map<?, ?> err) {
            throw new RuntimeException(
                    "mcp: " + method + ": server error " + err.get("code") + ": " + err.get("message"));
        }
        return env.get("result") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void notifyServer(String method) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        post(payload);
    }

    private String post(Map<String, Object> payload) {
        RestClient.RequestBodySpec spec = rest.post()
                .uri("")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON);
        if (sessionId != null) {
            spec = spec.header("Mcp-Session-Id", sessionId);
        }
        if (init) {
            spec = spec.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }

        ResponseEntity<String> entity = spec.body(toJson(payload)).retrieve().toEntity(String.class);
        String sid = entity.getHeaders().getFirst("Mcp-Session-Id");
        if (sid != null && !sid.isBlank()) {
            sessionId = sid;
        }
        if (entity.getStatusCode().value() == 202) {
            return null;
        }
        String body = entity.getBody();
        MediaType ct = entity.getHeaders().getContentType();
        if (ct != null && ct.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            return extractSse(body);
        }
        return body;
    }

    private static String extractSse(String body) {
        if (body == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            String l = line.strip();
            if (l.startsWith("data:")) {
                out.append(l.substring(5).strip());
            }
        }
        return out.toString();
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    // Minimal JSON writer (strings/numbers/booleans/maps/lists), independent of Jackson.
    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(toJson(e.getKey())).append(':').append(toJson(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(toJson(list.get(i)));
            }
            return b.append(']').toString();
        }
        return toJson(value.toString());
    }
}
