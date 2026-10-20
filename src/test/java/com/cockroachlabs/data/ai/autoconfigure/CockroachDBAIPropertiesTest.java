package com.cockroachlabs.data.ai.autoconfigure;

import com.cockroachlabs.data.ai.DistanceMetric;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CockroachDBAIProperties defaults and configuration binding,
 * including v26.2 beam-size and vector-index settings.
 */
class CockroachDBAIPropertiesTest {

    @Test
    void defaults_aiDisabled() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void defaults_vectorSearchProperties() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        var vs = props.getVectorSearch();

        assertThat(vs.getDefaultMetric()).isEqualTo(DistanceMetric.COSINE);
        assertThat(vs.getDefaultLimit()).isEqualTo(10);
        assertThat(vs.getBeamSize()).isEqualTo(0);
        assertThat(vs.isEnableVectorIndex()).isFalse();
    }

    @Test
    void beamSize_configurable() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        props.getVectorSearch().setBeamSize(64);
        assertThat(props.getVectorSearch().getBeamSize()).isEqualTo(64);
    }

    @Test
    void beamSize_deprecatedAccessor_matchesNew() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        props.getVectorSearch().setBeamSize(32);
        // Deprecated accessor should return the same value
        assertThat(props.getVectorSearch().getDefaultBeamSize()).isEqualTo(32);
    }

    @Test
    void beamSize_deprecatedMutator_setsNew() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        props.getVectorSearch().setDefaultBeamSize(48);
        assertThat(props.getVectorSearch().getBeamSize()).isEqualTo(48);
    }

    @Test
    void enableVectorIndex_configurable() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        props.getVectorSearch().setEnableVectorIndex(true);
        assertThat(props.getVectorSearch().isEnableVectorIndex()).isTrue();
    }

    @Test
    void metricsEnabled_byDefault() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        assertThat(props.getMetrics().isEnabled()).isTrue();
    }

    @Test
    void defaultMetric_configurable() {
        CockroachDBAIProperties props = new CockroachDBAIProperties();
        props.getVectorSearch().setDefaultMetric(DistanceMetric.L2);
        assertThat(props.getVectorSearch().getDefaultMetric()).isEqualTo(DistanceMetric.L2);
    }
}
