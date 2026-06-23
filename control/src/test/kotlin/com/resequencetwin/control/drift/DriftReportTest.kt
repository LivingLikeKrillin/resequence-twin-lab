package com.resequencetwin.control.drift

import com.resequencetwin.control.api.AdvisoryEnvelope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftReportTest {

    @Test
    fun `empty report has the synthetic envelope, no findings, source unavailable`() {
        val r = DriftReport.empty()
        assertThat(r.synthetic).isTrue()
        assertThat(r.disclaimer).isEqualTo(AdvisoryEnvelope.DISCLAIMER_TEXT)
        assertThat(r.sourceAvailable).isFalse()
        assertThat(r.configFindings).isEmpty()
        assertThat(r.behavioralFindings).isEmpty()
        assertThat(r.reconciliationProposals).isEmpty()
        assertThat(r.baseline).isNull()
        assertThat(r.observed).isNull()
    }

    @Test
    fun `of() flattens reconciliation proposals from config findings and carries the envelope`() {
        val baseline = ExpectedConfig(mapOf("L1" to 10), emptySet())
        val observed = ObservedConfig(mapOf("L1" to 8), emptySet())
        val cf = ConfigDriftFinding("L1", DriftKind.CAPACITY_CHANGED, "10", "8",
            ReconciliationProposal("ADJUST_CAPACITY", "twin L1 10 -> 8"))
        val bf = BehavioralDriftFinding("releases", 3.0, 1.0, 2.0, 1.4, true)

        val r = DriftReport.of(sourceAvailable = true, baseline = baseline, observed = observed,
            configFindings = listOf(cf), behavioralFindings = listOf(bf))

        assertThat(r.synthetic).isTrue()
        assertThat(r.sourceAvailable).isTrue()
        assertThat(r.reconciliationProposals).containsExactly(cf.proposal)
        assertThat(r.behavioralFindings).containsExactly(bf)
    }
}
