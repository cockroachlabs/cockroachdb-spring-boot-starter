package com.cockroachlabs.data.continuum.aegis;

import java.util.List;

/**
 * Placeholder for the real Cockroach Aegis HTTP client.
 *
 * <p>// TODO: wire the real Aegis API (findings / recommendations / emit) once it
 * is public. Until then every operation throws so callers never silently believe
 * they are talking to a live Aegis; use {@link SimulatedAegisClient} for
 * development and tests.
 */
public class HttpAegisClient implements AegisClient {

    private final String endpoint;
    private final String token;

    public HttpAegisClient(String endpoint, String token) {
        this.endpoint = endpoint;
        this.token = token;
    }

    public String endpoint() {
        return endpoint;
    }

    @Override
    public List<Finding> findings() {
        throw new UnsupportedOperationException("Aegis API not yet public");
    }

    @Override
    public List<Recommendation> recommendations() {
        throw new UnsupportedOperationException("Aegis API not yet public");
    }

    @Override
    public void emit(AppEvent event) {
        throw new UnsupportedOperationException("Aegis API not yet public");
    }
}
