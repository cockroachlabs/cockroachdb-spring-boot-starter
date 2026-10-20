package com.cockroachlabs.data.continuum.aegis;

import java.util.Map;

/**
 * An application-side operational event published to Cockroach Aegis ("exhaust").
 * The library sees app intent + DB behavior the cluster alone cannot attribute
 * (retry storms, circuit trips), so this feeds Aegis high-signal data.
 *
 * @param kind       event kind, e.g. {@code retry}, {@code circuit_open}
 * @param source     originating component (class/method)
 * @param attrs      arbitrary structured attributes
 * @param epochMillis event timestamp; pass explicitly for deterministic tests
 */
public record AppEvent(String kind, String source, Map<String, Object> attrs, long epochMillis) {

    /** Convenience factory stamping {@code System.currentTimeMillis()}. */
    public static AppEvent now(String kind, String source, Map<String, Object> attrs) {
        return new AppEvent(kind, source, attrs, System.currentTimeMillis());
    }
}
