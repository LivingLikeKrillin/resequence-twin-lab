package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigDriftDetectorTest {

    private val detector = ConfigDriftDetector()
    private val baseline = ExpectedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), expectedBlocked = emptySet())

    @Test
    fun `no drift when observed equals baseline returns empty`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = emptySet())
        assertThat(detector.detect(baseline, observed)).isEmpty()
    }

    @Test
    fun `capacity change is reported with expected and observed values and a proposal`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 8, "L2" to 10, "L3" to 10), blocked = emptySet())
        val findings = detector.detect(baseline, observed)

        assertThat(findings).hasSize(1)
        val f = findings.single()
        assertThat(f.laneId).isEqualTo("L1")
        assertThat(f.kind).isEqualTo(DriftKind.CAPACITY_CHANGED)
        assertThat(f.expected).isEqualTo("10")
        assertThat(f.observed).isEqualTo("8")
        assertThat(f.proposal.action).isEqualTo("ADJUST_CAPACITY")
    }

    @Test
    fun `unexpected block is reported`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = setOf("L2"))
        val findings = detector.detect(baseline, observed)
        assertThat(findings.map { it.kind }).containsExactly(DriftKind.UNEXPECTED_BLOCK)
        assertThat(findings.single().laneId).isEqualTo("L2")
    }

    @Test
    fun `missing expected lane is reported`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10), blocked = emptySet())
        val findings = detector.detect(baseline, observed)
        assertThat(findings.map { it.kind }).containsExactly(DriftKind.EXPECTED_LANE_MISSING)
        assertThat(findings.single().laneId).isEqualTo("L3")
    }

    @Test
    fun `findings are ordered deterministically by laneId then kind`() {
        val observed = ObservedConfig(lanes = mapOf("L1" to 8, "L2" to 10), blocked = setOf("L1"))
        val findings = detector.detect(baseline, observed)
        // L1 capacity + L1 block + L3 missing — stable order
        assertThat(findings.map { it.laneId }).isEqualTo(listOf("L1", "L1", "L3"))
    }
}
