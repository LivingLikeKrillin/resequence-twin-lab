package com.resequencetwin.control.pbs

import com.fasterxml.jackson.annotation.JsonProperty

data class CandidateScore(
    @field:JsonProperty("laneId") val laneId: String,
    @field:JsonProperty("bodyId") val bodyId: String,
    @field:JsonProperty("color") val color: String,
    @field:JsonProperty("hasOptions") val hasOptions: Boolean,
    @field:JsonProperty("colorBonus") val colorBonus: Double,
    @field:JsonProperty("optionLevelingBonus") val optionLevelingBonus: Double,
    @field:JsonProperty("dueDateBonus") val dueDateBonus: Double,
    @field:JsonProperty("weightedTotal") val weightedTotal: Double,
    @field:JsonProperty("overdue") val overdue: Boolean
)
