package com.cockroachlabs.data.continuum.aegis;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory Cockroach Aegis client for local development and tests. Returns a
 * small canned set of findings/recommendations and records every emitted
 * {@link AppEvent} so tests (and demos) can inspect the exhaust.
 */
public class SimulatedAegisClient implements AegisClient {

    private final List<AppEvent> emitted = new CopyOnWriteArrayList<>();

    @Override
    public List<Finding> findings() {
        return List.of(
                new Finding("sim-hot-range", "warning",
                        "Hot range detected",
                        "A range is receiving disproportionate traffic; consider load-based splitting."),
                new Finding("sim-retry-rate", "info",
                        "Elevated serialization retries",
                        "Serialization retries are above baseline for one workload."));
    }

    @Override
    public List<Recommendation> recommendations() {
        return List.of(
                new Recommendation("sim-rec-split",
                        "Enable load-based splitting",
                        "Allow the cluster to split the hot range to spread load."));
    }

    @Override
    public void emit(AppEvent event) {
        emitted.add(event);
    }

    /** All events emitted so far (for tests/inspection). */
    public List<AppEvent> emittedEvents() {
        return List.copyOf(emitted);
    }
}
