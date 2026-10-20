package com.cockroachlabs.data.continuum.aegis;

import java.util.List;

/**
 * Client for Cockroach Aegis autonomous database operations.
 *
 * <p>Read-first: list findings/recommendations the agents produced. Also accepts
 * application "exhaust" via {@link #emit(AppEvent)} so app-attributed signals can
 * inform the agents.
 *
 * <p>Aegis is preview/EA and its API is not yet public. The default,
 * fully-functional implementation is {@link SimulatedAegisClient}; a real
 * {@link HttpAegisClient} is stubbed until the API lands.
 */
public interface AegisClient {

    List<Finding> findings();

    List<Recommendation> recommendations();

    void emit(AppEvent event);
}
