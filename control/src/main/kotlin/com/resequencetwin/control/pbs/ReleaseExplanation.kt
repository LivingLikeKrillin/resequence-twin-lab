package com.resequencetwin.control.pbs

import com.fasterxml.jackson.annotation.JsonProperty

data class ReleaseExplanation(
    @field:JsonProperty("chosenLaneId") val chosenLaneId: String,
    @field:JsonProperty("chosenBodyId") val chosenBodyId: String,
    @field:JsonProperty("reason") val reason: ReleaseReason,
    @field:JsonProperty("candidates") val candidates: List<CandidateScore>
)
