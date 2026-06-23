package com.resequencetwin.control.pbs

import com.fasterxml.jackson.annotation.JsonProperty

data class ContributingFactor(
    @field:JsonProperty("name") val name: String,
    @field:JsonProperty("value") val value: Double,
    @field:JsonProperty("weightedContribution") val weightedContribution: Double,
    @field:JsonProperty("drivingLaneId") val drivingLaneId: String?,
    @field:JsonProperty("drivingBodyId") val drivingBodyId: String?
) {
    constructor(name: String, value: Double, weightedContribution: Double) :
        this(name, value, weightedContribution, null, null)
}
