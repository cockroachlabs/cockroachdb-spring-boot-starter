package com.cockroachlabs.data.multiregion;

import java.util.Optional;

public final class RegionContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RegionContext() {}

    public static void set(String region) {
        HOLDER.set(region);
    }

    public static Optional<String> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static RegionScope scoped(String region) {
        return new RegionScope(region);
    }

    public static class RegionScope implements AutoCloseable {

        private final String previous;

        RegionScope(String region) {
            this.previous = HOLDER.get();
            HOLDER.set(region);
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
