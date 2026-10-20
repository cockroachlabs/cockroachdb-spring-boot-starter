package com.cockroachlabs.data.continuum;

import com.cockroachlabs.data.continuum.annotation.Workload.Priority;

import java.util.Optional;

/**
 * Thread-scoped Cockroach vCluster context for programmatic use, mirroring
 * {@code RegionContext}. When set, the {@code VirtualClusterAspect} and
 * {@code CockroachDBTemplate} apply the tag/QoS to the transaction.
 */
public final class VirtualClusterContext {

    /** Immutable vCluster binding: a name and (optionally) a workload priority. */
    public record Binding(String name, Priority priority) {
    }

    private static final ThreadLocal<Binding> HOLDER = new ThreadLocal<>();

    private VirtualClusterContext() {
    }

    public static void set(String name, Priority priority) {
        HOLDER.set(new Binding(name, priority));
    }

    public static Optional<Binding> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** Bind {@code name}/{@code priority} for a try-with-resources scope. */
    public static Scope scoped(String name, Priority priority) {
        return new Scope(name, priority);
    }

    public static final class Scope implements AutoCloseable {

        private final Binding previous;

        Scope(String name, Priority priority) {
            this.previous = HOLDER.get();
            HOLDER.set(new Binding(name, priority));
        }

        @Override
        public void close() {
            if (previous != null) {
                HOLDER.set(previous);
            } else {
                HOLDER.remove();
            }
        }
    }
}
