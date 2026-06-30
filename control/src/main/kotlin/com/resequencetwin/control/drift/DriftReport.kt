package com.resequencetwin.control.drift

import com.fasterxml.jackson.annotation.JsonProperty
import com.resequencetwin.control.api.AdvisoryEnvelope

/**
 * Read-only aggregate exposed at `GET /api/drift`. Carries the synthetic honesty envelope plus the
 * captured baseline, the latest observed config, and config + behavioral findings. The advisory
 * [reconciliationProposals] are a flattened convenience view of the per-finding proposals.
 *
 * @property sourceAvailable false when the live source could not be read on the latest tick.
 */
data class DriftReport(
    @field:JsonProperty("synthetic")              val synthetic: Boolean,
    @field:JsonProperty("disclaimer")             val disclaimer: String,
    @field:JsonProperty("sourceAvailable")        val sourceAvailable: Boolean,
    @field:JsonProperty("baseline")               val baseline: ExpectedConfig?,
    @field:JsonProperty("observed")               val observed: ObservedConfig?,
    @field:JsonProperty("configFindings")         val configFindings: List<ConfigDriftFinding>,
    @field:JsonProperty("behavioralFindings")     val behavioralFindings: List<BehavioralDriftFinding>,
    @field:JsonProperty("setpointFindings")       val setpointFindings: List<SetpointDriftFinding>,
    @field:JsonProperty("reconciliationProposals") val reconciliationProposals: List<ReconciliationProposal>,
) {
    companion object {
        /** Initial report before the first tick (or while the scheduler is disabled). */
        fun empty(): DriftReport {
            val env = AdvisoryEnvelope.standard()
            return DriftReport(env.synthetic, env.disclaimer, sourceAvailable = false,
                baseline = null, observed = null,
                configFindings = emptyList(), behavioralFindings = emptyList(),
                setpointFindings = emptyList(),
                reconciliationProposals = emptyList())
        }

        fun of(
            sourceAvailable: Boolean,
            baseline: ExpectedConfig?,
            observed: ObservedConfig?,
            configFindings: List<ConfigDriftFinding>,
            behavioralFindings: List<BehavioralDriftFinding>,
            setpointFindings: List<SetpointDriftFinding> = emptyList(),
        ): DriftReport {
            val env = AdvisoryEnvelope.standard()
            return DriftReport(env.synthetic, env.disclaimer, sourceAvailable, baseline, observed,
                configFindings, behavioralFindings, setpointFindings,
                configFindings.map { it.proposal } + setpointFindings.map { it.proposal })
        }
    }
}
