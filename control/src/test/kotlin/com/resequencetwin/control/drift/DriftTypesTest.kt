package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftTypesTest {

    @Test
    fun `ExpectedConfig and ObservedConfig hold lanes and blocked sets`() {
        val expected = ExpectedConfig(lanes = mapOf("L1" to 10, "L2" to 10), expectedBlocked = emptySet())
        val observed = ObservedConfig(lanes = mapOf("L1" to 8, "L2" to 10), blocked = setOf("L2"))

        assertThat(expected.lanes["L1"]).isEqualTo(10)
        assertThat(observed.lanes["L1"]).isEqualTo(8)
        assertThat(observed.blocked).containsExactly("L2")
    }

    @Test
    fun `ObservedKpi mirrors the three comparable cumulative LiveSnapshot fields`() {
        val kpi = ObservedKpi(releases = 5, colourChanges = 2, assemblyOut = 5)
        assertThat(kpi.releases).isEqualTo(5)
        assertThat(kpi.colourChanges).isEqualTo(2)
        assertThat(kpi.assemblyOut).isEqualTo(5)
    }

    @Test
    fun `ConfigDriftFinding carries a ReconciliationProposal`() {
        val f = ConfigDriftFinding(
            laneId = "L1", kind = DriftKind.CAPACITY_CHANGED,
            expected = "10", observed = "8",
            proposal = ReconciliationProposal(action = "ADJUST_CAPACITY", description = "twin L1 10 -> 8"),
        )
        assertThat(f.kind).isEqualTo(DriftKind.CAPACITY_CHANGED)
        assertThat(f.proposal.action).isEqualTo("ADJUST_CAPACITY")
    }

    @Test
    fun `BehavioralDriftFinding exposes residual, ewma and breached`() {
        val b = BehavioralDriftFinding(metric = "releases", predicted = 3.0, observed = 1.0,
            residual = 2.0, ewma = 1.4, breached = true)
        assertThat(b.breached).isTrue()
        assertThat(b.metric).isEqualTo("releases")
    }
}
