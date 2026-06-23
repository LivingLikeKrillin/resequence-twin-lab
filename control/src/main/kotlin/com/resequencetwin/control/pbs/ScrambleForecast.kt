package com.resequencetwin.control.pbs

import com.fasterxml.jackson.annotation.JsonProperty

data class ScrambleForecast(
    @field:JsonProperty("riskScore") val riskScore: Double,
    @field:JsonProperty("level") val level: ScrambleLevel,
    @field:JsonProperty("factors") val factors: List<ContributingFactor>,
    @field:JsonProperty("topContributor") val topContributor: ContributingFactor,
    @field:JsonProperty("stabilizationHint") val stabilizationHint: String
)
