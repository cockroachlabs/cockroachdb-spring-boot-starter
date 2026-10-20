package com.cockroachlabs.data.continuum.aegis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AegisTest {

    @Test
    void simulatedClientReturnsCannedDataAndRecordsEmissions() {
        SimulatedAegisClient client = new SimulatedAegisClient();
        assertThat(client.findings()).isNotEmpty();
        assertThat(client.recommendations()).isNotEmpty();

        client.emit(new AppEvent("retry", "svc", Map.of("attempt", 2), 123L));
        assertThat(client.emittedEvents()).hasSize(1);
        assertThat(client.emittedEvents().get(0).epochMillis()).isEqualTo(123L);
        assertThat(client.emittedEvents().get(0).kind()).isEqualTo("retry");
    }

    @Test
    void exhaustEmitsAppAttributedSignals() {
        SimulatedAegisClient client = new SimulatedAegisClient();
        AegisExhaust exhaust = new AegisExhaust(client);

        exhaust.recordRetry("PayrollService.runPayroll", 3);
        exhaust.recordCircuitOpen("PayrollService.runPayroll");

        assertThat(client.emittedEvents()).extracting(AppEvent::kind)
                .containsExactly("retry", "circuit_open");
        assertThat(client.emittedEvents().get(0).source()).isEqualTo("PayrollService.runPayroll");
    }
}
