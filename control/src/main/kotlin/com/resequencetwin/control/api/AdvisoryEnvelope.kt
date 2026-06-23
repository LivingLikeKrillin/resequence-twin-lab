package com.resequencetwin.control.api

import com.fasterxml.jackson.annotation.JsonProperty

data class AdvisoryEnvelope(
    @field:JsonProperty("synthetic")  val synthetic: Boolean,
    @field:JsonProperty("disclaimer") val disclaimer: String
) {
    companion object {
        const val DISCLAIMER_TEXT: String =
            "Synthetic PoC — values are generated from a seeded deterministic simulation, " +
            "not real plant data. KPI deltas are relative-to-static-baseline. " +
            "Ford Saarlouis numbers (arXiv 2507.17422) are a published reference, " +
            "NOT this PoC's measurement."

        fun standard(): AdvisoryEnvelope = AdvisoryEnvelope(true, DISCLAIMER_TEXT)
    }
}
