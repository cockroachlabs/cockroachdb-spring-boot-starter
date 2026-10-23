package com.cockroachlabs.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Proves which JVM runs the test suite: prints its version/vendor/home (surfaced
 * in the build log by the Gradle test task) and fails if it is not the version
 * requested via {@code -PtestJvm}.
 */
class TestJvmTest {

    @Test
    void runsOnRequestedJvm() {
        System.out.printf("Test JVM: %s %s (%s) at %s%n",
                System.getProperty("java.vm.name"),
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.home"));

        int expected = Integer.parseInt(System.getProperty("expectedTestJvm"));
        assertThat(Runtime.version().feature()).isEqualTo(expected);
    }
}
