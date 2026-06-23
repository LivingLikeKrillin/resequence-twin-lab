package com.resequencetwin.control.api

import com.fasterxml.jackson.annotation.JsonProperty

/** Metadata for one body in the trajectory bodies map. */
data class TrajectoryBodyDto(
    @field:JsonProperty("color")      val color: String,
    @field:JsonProperty("model")      val model: String,
    @field:JsonProperty("options")    val options: List<String>,
    @field:JsonProperty("dueDateSeq") val dueDateSeq: Int
)

/** One lane in the trajectory lanes array. */
data class TrajectoryLaneDto(
    @field:JsonProperty("id")       val id: String,
    @field:JsonProperty("capacity") val capacity: Int
)

/** One per-step frame in the trajectory frames array. */
data class TrajectoryFrameDto(
    @field:JsonProperty("step")     val step: Int,
    @field:JsonProperty("lanes")    val lanes: Map<String, List<String>>,
    @field:JsonProperty("released") val released: String?,
    @field:JsonProperty("arrived")  val arrived: List<String>
)

/**
 * Full trajectory response for GET /api/trajectory.
 *
 * Wraps the honesty envelope + seed/policy context + lane specs +
 * body metadata map + per-step frames.
 */
data class TrajectoryResponse(
    @field:JsonProperty("synthetic")  val synthetic: Boolean,
    @field:JsonProperty("disclaimer") val disclaimer: String,
    @field:JsonProperty("seed")       val seed: Long,
    @field:JsonProperty("policy")     val policy: String,
    @field:JsonProperty("lanes")      val lanes: List<TrajectoryLaneDto>,
    @field:JsonProperty("bodies")     val bodies: Map<String, TrajectoryBodyDto>,
    @field:JsonProperty("frames")     val frames: List<TrajectoryFrameDto>
)
