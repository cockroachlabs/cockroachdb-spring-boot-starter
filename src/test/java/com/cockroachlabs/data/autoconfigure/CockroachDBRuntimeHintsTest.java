package com.cockroachlabs.data.autoconfigure;

import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.annotation.ComputedColumn;
import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.aop.CockroachDBAspect;
import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.annotation.CockroachDBCDC;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.multiregion.RegionResolver;
import com.cockroachlabs.data.template.CockroachDBTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that all GraalVM native image hints are registered correctly.
 */
class CockroachDBRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    CockroachDBRuntimeHintsTest() {
        new CockroachDBRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void registersAnnotationsForReflection() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CockroachDB.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(FollowerRead.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(ComputedColumn.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CockroachDBCDC.class).test(hints)).isTrue();
    }

    @Test
    void registersAspectsForReflection() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CockroachDBAspect.class).test(hints)).isTrue();
    }

    @Test
    void registersCDCDtosForSerialization() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CDCEnvelope.class).test(hints)).isTrue();
    }

    @Test
    void registersServicesForReflection() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CockroachDBTemplate.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CockroachDBProperties.class).test(hints)).isTrue();
    }

    @Test
    void registersProxies() {
        assertThat(RuntimeHintsPredicates.proxies()
                .forInterfaces(RegionResolver.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.proxies()
                .forInterfaces(CockroachDBMetrics.class).test(hints)).isTrue();
    }
}
