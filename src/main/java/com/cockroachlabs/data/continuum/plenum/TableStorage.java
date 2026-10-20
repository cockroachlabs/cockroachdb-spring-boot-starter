package com.cockroachlabs.data.continuum.plenum;

import java.util.OptionalLong;

/**
 * Cockroach Plenum storage signal for a single table. Sizes are best-effort:
 * {@code bytes}/{@code rows} are {@link OptionalLong#empty()} when the running
 * CockroachDB version does not expose that signal, rather than failing.
 */
public record TableStorage(String schema, String table, OptionalLong rows, OptionalLong bytes) {

    public String qualifiedName() {
        return schema + "." + table;
    }
}
