package com.cockroachlabs.data.continuum.aegis;

/**
 * A Cockroach Aegis recommendation: a suggested action tied to one or more
 * findings.
 *
 * @param id     stable recommendation id
 * @param title  short human-readable summary
 * @param detail longer explanation / suggested action
 */
public record Recommendation(String id, String title, String detail) {
}
