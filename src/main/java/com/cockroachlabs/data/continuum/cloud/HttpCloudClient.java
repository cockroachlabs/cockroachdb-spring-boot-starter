package com.cockroachlabs.data.continuum.cloud;

import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP-backed {@link CloudClient} that talks to the real CockroachDB Cloud API
 * using a service-account Bearer token.
 *
 * <p>To stay independent of the host application's HTTP message converters and
 * Jackson version, responses are retrieved as text and parsed with Spring Boot's
 * {@link JsonParser}, and request bodies are written as compact JSON strings.
 * Fields are mapped by their documented (snake_case) names. Construct with
 * {@link #create(String, String)} in production, or pass a pre-built
 * {@link RestClient} (e.g. bound to a {@code MockRestServiceServer}) in tests.
 */
public class HttpCloudClient implements CloudClient {

    /** Default CockroachDB Cloud API base URL. */
    public static final String DEFAULT_BASE_URL = "https://cockroachlabs.cloud/api/v1";

    private static final JsonParser JSON = JsonParserFactory.getJsonParser();

    private final RestClient rest;

    public HttpCloudClient(RestClient rest) {
        this.rest = rest;
    }

    /** Builds an {@link HttpCloudClient} for the given base URL and API key. */
    public static HttpCloudClient create(String baseUrl, String apiKey) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
        RestClient rc = RestClient.builder()
                .baseUrl(base)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Accept", "application/json")
                .build();
        return new HttpCloudClient(rc);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Cluster> listClusters() {
        String body = rest.get().uri("/clusters").retrieve().body(String.class);
        List<Cluster> out = new ArrayList<>();
        if (body != null && !body.isBlank()) {
            Map<String, Object> root = JSON.parseMap(body);
            if (root.get("clusters") instanceof List<?> arr) {
                for (Object o : arr) {
                    if (o instanceof Map<?, ?> m) {
                        out.add(toCluster((Map<String, Object>) m));
                    }
                }
            }
        }
        return out;
    }

    @Override
    public Cluster getCluster(String clusterId) {
        String body = rest.get().uri("/clusters/{id}", clusterId).retrieve().body(String.class);
        return toCluster(body == null || body.isBlank() ? Map.of() : JSON.parseMap(body));
    }

    @Override
    public void configureMetricExport(String clusterId, MetricExportConfig config) {
        if (config == null || config.target() == null) {
            throw new IllegalArgumentException("cloud: metric export target is required");
        }
        rest.post()
                .uri("/clusters/{id}/metricexport/{target}", clusterId, config.target().path())
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(config.settings() == null ? Map.of() : config.settings()))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private static Cluster toCluster(Map<String, Object> m) {
        return new Cluster(
                str(m, "id"), str(m, "name"), str(m, "plan"), str(m, "state"),
                str(m, "cloud_provider"),
                m.get("regions") instanceof List<?> r
                        ? r.stream().map(String::valueOf).toList()
                        : List.of());
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /** Minimal JSON writer for flat setting maps (string/number/boolean/null values). */
    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return quote(v.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
