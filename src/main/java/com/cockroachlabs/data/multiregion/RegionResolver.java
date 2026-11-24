package com.cockroachlabs.data.multiregion;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface RegionResolver {

    /** The region for the current call, or {@code null} when this resolver has none. */
    @Nullable String resolve();
}
