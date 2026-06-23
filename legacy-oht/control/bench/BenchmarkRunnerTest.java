package com.resequencetwin.control.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies BenchmarkRunner: report output and JSON serialization.
 */
class BenchmarkRunnerTest {

    @Test
    void runDefaultPrintsReportAndReturnsComparison() {
        BenchmarkComparison cmp = BenchmarkRunner.runDefault(42L, 200);
        BenchmarkComparison.Report report = cmp.report();

        // Report object should have sensible values
        assertThat(report.staticKpis().throughput()).isPositive();
        assertThat(report.dynamicKpis().throughput()).isPositive();
    }

    @Test
    void toJsonContainsHonestyMetadataAndAllKpis() {
        BenchmarkComparison cmp = BenchmarkRunner.runDefault(42L, 200);
        String json = BenchmarkRunner.toJson(cmp.report(), null);

        // Honesty metadata
        assertThat(json).contains("synthetic simulation, relative-to-static");

        // All required KPI fields
        assertThat(json).contains("throughput_moves_per_tick");
        assertThat(json).contains("mean_flow_lead_time_ticks");
        assertThat(json).contains("deadlock_rate");
        assertThat(json).contains("flow_time_variance");
        assertThat(json).contains("mean_utilization");

        // Relative deltas section
        assertThat(json).contains("relative_deltas_dynamic_minus_static");

        // Invariant evaluation section
        assertThat(json).contains("invariants_spec_section6");
        assertThat(json).contains("dynamic_deadlock_rate_le_static");
        assertThat(json).contains("dynamic_flow_variance_le_static");
        assertThat(json).contains("dynamic_throughput_ge_static_saturated");
    }

    @Test
    void jsonInvariantFieldsAllTrue() {
        BenchmarkComparison cmp = BenchmarkRunner.runDefault(42L, 200);
        BenchmarkComparison.Report report = cmp.report();
        String json = BenchmarkRunner.toJson(cmp.report(), null);

        // The invariants should be true in the JSON
        assertThat(json).contains("\"dynamic_deadlock_rate_le_static\" : true");
        assertThat(json).contains("\"dynamic_flow_variance_le_static\" : true");
        assertThat(json).contains("\"dynamic_throughput_ge_static_saturated\" : true");
    }
}
