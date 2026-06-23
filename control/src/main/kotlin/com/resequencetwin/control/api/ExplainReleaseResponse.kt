package com.resequencetwin.control.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.resequencetwin.control.pbs.ReleaseExplanation

data class ExplainReleaseResponse(
    @field:JsonProperty("synthetic")        val synthetic: Boolean,
    @field:JsonProperty("disclaimer")       val disclaimer: String,
    @field:JsonProperty("seed")             val seed: Long,
    @field:JsonProperty("afterReleases")    val afterReleases: Int,
    @field:JsonProperty("assemblyOutSize")  val assemblyOutSize: Int,
    @field:JsonProperty("explanation")      val explanation: ReleaseExplanation
) {
    companion object {
        fun of(
            seed: Long,
            afterReleases: Int,
            assemblyOutSize: Int,
            explanation: ReleaseExplanation
        ): ExplainReleaseResponse {
            val env = AdvisoryEnvelope.standard()
            return ExplainReleaseResponse(
                synthetic       = env.synthetic,
                disclaimer      = env.disclaimer,
                seed            = seed,
                afterReleases   = afterReleases,
                assemblyOutSize = assemblyOutSize,
                explanation     = explanation
            )
        }
    }
}
