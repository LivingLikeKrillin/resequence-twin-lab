package com.resequencetwin.control.drift

import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.BodyArrived
import com.resequencetwin.control.stream.LivePbsProcessor
import com.resequencetwin.control.stream.ReleaseTick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftMonitorTest {

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))

    private fun monitor(
        processor: LivePbsProcessor,
        cfg: SimulatedConfigSource,
        tel: SimulatedTelemetrySource,
    ) = DriftMonitor(processor, cfg, tel, DisabledSetpointDriftDetector,
        enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0)

    private fun baselineConfig() =
        ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = emptySet())

    @Test
    fun `disabled monitor never mutates the report on tick`() {
        val proc = LivePbsProcessor(lanes)
        val m = DriftMonitor(proc, SimulatedConfigSource(baselineConfig()),
            SimulatedTelemetrySource(ObservedKpi(0, 0, 0)), DisabledSetpointDriftDetector,
            enabled = false, ewmaAlpha = 0.5, residualThreshold = 1.0)
        m.tick()
        assertThat(m.currentReport()).isEqualTo(DriftReport.empty())
    }

    @Test
    fun `captureBaseline snapshots the live config source`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val m = monitor(LivePbsProcessor(lanes), cfg, SimulatedTelemetrySource(ObservedKpi(0, 0, 0)))
        val baseline = m.captureBaseline()
        assertThat(baseline.lanes).isEqualTo(mapOf("L1" to 10, "L2" to 10, "L3" to 10))
        assertThat(baseline.expectedBlocked).isEmpty()
    }

    @Test
    fun `no config mutation yields no config findings`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val m = monitor(LivePbsProcessor(lanes), cfg, SimulatedTelemetrySource(ObservedKpi(0, 0, 0)))
        m.tick()  // captures baseline + first observation
        m.tick()
        assertThat(m.currentReport().sourceAvailable).isTrue()
        assertThat(m.currentReport().configFindings).isEmpty()
    }

    @Test
    fun `injected capacity drift surfaces as a config finding with a proposal`() {
        val cfg = SimulatedConfigSource(baselineConfig())
        val m = monitor(LivePbsProcessor(lanes), cfg, SimulatedTelemetrySource(ObservedKpi(0, 0, 0)))
        m.tick()                 // baseline captured at 10
        cfg.setCapacity("L1", 8) // inject drift
        m.tick()

        val findings = m.currentReport().configFindings
        assertThat(findings).hasSize(1)
        assertThat(findings.single().kind).isEqualTo(DriftKind.CAPACITY_CHANGED)
        assertThat(m.currentReport().reconciliationProposals).hasSize(1)
    }

    @Test
    fun `behavioral residual breaches when observed KPI lags twin prediction`() {
        val proc = LivePbsProcessor(lanes)
        val cfg = SimulatedConfigSource(baselineConfig())
        val tel = SimulatedTelemetrySource(ObservedKpi(0, 0, 0))
        val m = monitor(proc, cfg, tel)  // alpha=0.5, threshold=1.0

        m.tick()  // seed: both sides at 0, no delta yet

        // The residual is a per-tick DELTA of cumulative values, so a one-time jump decays away.
        // To create a SUSTAINED residual we advance the twin by +3 releases between EVERY monitor
        // tick while observed telemetry stays at 0. Each tick then sees dPred=+3, dObs=0,
        // residual=+3; with alpha=0.5 the EWMA climbs to 1.5 on the first such tick (> threshold 1.0)
        // and stays breached. All bodies are RED, so colourChanges stays matched (0 vs 0).
        var idx = 0
        var seq = 1L
        repeat(3) {
            repeat(3) {
                proc.process(BodyArrived(eventId = "e$seq", seq = seq++, bodyId = "B${idx++}",
                    color = "RED", model = "M1", dueDateSeq = 0))
            }
            repeat(3) { proc.process(ReleaseTick(eventId = "t$seq", seq = seq++)) }
            m.tick()  // observed telemetry still 0 -> residual = +3 on the "releases" metric this tick
        }

        val breaches = m.currentReport().behavioralFindings.filter { it.breached }
        assertThat(breaches).isNotEmpty()
        assertThat(breaches.map { it.metric }).contains("releases")
    }

    @Test
    fun `source outage is non-fatal and preserves the baseline`() {
        val throwing = object : LiveConfigSource {
            var fail = false
            override fun readConfig(): ObservedConfig =
                if (fail) throw RuntimeException("source down") else baselineConfig()
        }
        val m = DriftMonitor(LivePbsProcessor(lanes), throwing,
            SimulatedTelemetrySource(ObservedKpi(0, 0, 0)), DisabledSetpointDriftDetector,
            enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0)
        m.tick()                       // baseline captured
        throwing.fail = true
        m.tick()                       // must not throw
        assertThat(m.currentReport().sourceAvailable).isFalse()
        assertThat(m.currentReport().baseline).isNotNull()
    }
}
