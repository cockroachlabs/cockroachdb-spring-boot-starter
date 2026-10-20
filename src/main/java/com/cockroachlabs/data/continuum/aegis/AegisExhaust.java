package com.cockroachlabs.data.continuum.aegis;

import java.util.Map;

/**
 * Publishes application-side operational signals ("exhaust") to Cockroach Aegis.
 *
 * <p>The library sees app-attributed events — serialization retries, circuit
 * trips — that the cluster alone cannot tie back to a specific service/method.
 * Call these convenience methods from your retry/circuit hooks (or wire them into
 * the metrics layer) to feed Aegis. Backed by any {@link AegisClient}
 * (the {@link SimulatedAegisClient} by default).
 */
public class AegisExhaust {

    private final AegisClient client;

    public AegisExhaust(AegisClient client) {
        this.client = client;
    }

    /** Emit an arbitrary event. */
    public void record(AppEvent event) {
        client.emit(event);
    }

    /** Emit a serialization-retry event attributed to {@code source}. */
    public void recordRetry(String source, int attempt) {
        client.emit(AppEvent.now("retry", source, Map.of("attempt", attempt)));
    }

    /** Emit a circuit-open event attributed to {@code source}. */
    public void recordCircuitOpen(String source) {
        client.emit(AppEvent.now("circuit_open", source, Map.of()));
    }
}
