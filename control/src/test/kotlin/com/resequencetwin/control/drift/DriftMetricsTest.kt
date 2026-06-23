package com.resequencetwin.control.drift

import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.LivePbsProcessor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftMetricsTest {

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))
    private fun baselineConfig() = ObservedConfig(mapOf("L1" to 10, "L2" to 10, "L3" to 10), emptySet())

    @Test
    fun `gauges expose config finding and behavioral breach counts from the monitor`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val monitor = DriftMonitor(LivePbsProcessor(lanes), cfg,
            SimulatedTelemetrySource(ObservedKpi(0, 0, 0)),
            enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0)

        val registry = SimpleMeterRegistry()
        DriftMetrics(monitor).bindTo(registry)

        monitor.tick()
        cfg.setCapacity("L1", 8)
        monitor.tick()

        val findings = registry.get("pbs.drift.config.findings").gauge().value()
        val breaches = registry.get("pbs.drift.behavioral.breaches").gauge().value()
        assertThat(findings).isEqualTo(1.0)
        assertThat(breaches).isGreaterThanOrEqualTo(0.0)
    }
}
