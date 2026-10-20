package com.cockroachlabs.data.ai.autoconfigure;

import com.cockroachlabs.data.ai.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cockroachdb.ai")
public class CockroachDBAIProperties {

    private boolean enabled = false;

    private VectorSearch vectorSearch = new VectorSearch();

    private Metrics metrics = new Metrics();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public VectorSearch getVectorSearch() {
        return vectorSearch;
    }

    public void setVectorSearch(VectorSearch vectorSearch) {
        this.vectorSearch = vectorSearch;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public static class VectorSearch {

        private DistanceMetric defaultMetric = DistanceMetric.COSINE;

        private int defaultLimit = 10;

        /**
         * Default beam size for vector search accuracy
         * (maps to {@code SET LOCAL vector_search_beam_size}).
         * 0 = use database default. Configurable via
         * {@code cockroachdb.ai.vector-search.beam-size}.
         */
        private int beamSize = 0;

        /**
         * When true, execute {@code SET CLUSTER SETTING feature.vector_index.enabled = true}
         * on startup. Required for CockroachDB v26.2 vector index support.
         */
        private boolean enableVectorIndex = false;

        public DistanceMetric getDefaultMetric() {
            return defaultMetric;
        }

        public void setDefaultMetric(DistanceMetric defaultMetric) {
            this.defaultMetric = defaultMetric;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getBeamSize() {
            return beamSize;
        }

        public void setBeamSize(int beamSize) {
            this.beamSize = beamSize;
        }

        /** @deprecated Use {@link #getBeamSize()} instead. */
        @Deprecated
        public int getDefaultBeamSize() {
            return beamSize;
        }

        /** @deprecated Use {@link #setBeamSize(int)} instead. */
        @Deprecated
        public void setDefaultBeamSize(int defaultBeamSize) {
            this.beamSize = defaultBeamSize;
        }

        public boolean isEnableVectorIndex() {
            return enableVectorIndex;
        }

        public void setEnableVectorIndex(boolean enableVectorIndex) {
            this.enableVectorIndex = enableVectorIndex;
        }
    }

    public static class Metrics {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
