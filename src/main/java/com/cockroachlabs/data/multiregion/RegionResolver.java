package com.cockroachlabs.data.multiregion;

@FunctionalInterface
public interface RegionResolver {

    String resolve();
}
