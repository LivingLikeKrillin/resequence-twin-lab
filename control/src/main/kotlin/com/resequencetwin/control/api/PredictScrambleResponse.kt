package com.resequencetwin.control.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.resequencetwin.control.pbs.ScrambleForecast

data class PredictScrambleResponse(
    @field:JsonProperty("synthetic")        val synthetic: Boolean,
    @field:JsonProperty("disclaimer")       val disclaimer: String,
    @field:JsonProperty("seed")             val seed: Long,
    @field:JsonProperty("afterReleases")    val afterReleases: Int,
    @field:JsonProperty("assemblyOutSize")  val assemblyOutSize: Int,
    @field:JsonProperty("forecast")         val forecast: ScrambleForecast
) {
    companion object {
        fun of(
            seed: Long,
            afterReleases: Int,
            assemblyOutSize: Int,
            forecast: ScrambleForecast
        ): PredictScrambleResponse {
            val env = AdvisoryEnvelope.standard()
            return PredictScrambleResponse(
                synthetic       = env.synthetic,
                disclaimer      = env.disclaimer,
                seed            = seed,
                afterReleases   = afterReleases,
                assemblyOutSize = assemblyOutSize,
                forecast        = forecast
            )
        }
    }
}
