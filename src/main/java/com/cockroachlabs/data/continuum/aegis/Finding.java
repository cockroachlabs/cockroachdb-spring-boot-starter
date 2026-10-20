package com.cockroachlabs.data.continuum.aegis;

/**
 * A Cockroach Aegis finding: an observation the autonomous-ops agents surfaced
 * about a cluster.
 *
 * @param id       stable finding id
 * @param severity {@code info} / {@code warning} / {@code critical}
 * @param title    short human-readable summary
 * @param detail   longer explanation
 */
public record Finding(String id, String severity, String title, String detail) {
}
