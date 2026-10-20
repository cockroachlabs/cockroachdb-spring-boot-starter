package com.cockroachlabs.data.multiregion;

public class StaticRegionResolver implements RegionResolver {

    private final String region;

    public StaticRegionResolver(String region) {
        this.region = region;
    }

    @Override
    public String resolve() {
        return region;
    }
}
