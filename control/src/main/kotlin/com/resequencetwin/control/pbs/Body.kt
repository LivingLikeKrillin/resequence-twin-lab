package com.resequencetwin.control.pbs

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A car body unit in the Paint Buffer Store (PBS).
 *
 * Bodies flow from the paint shop (color-batched) into the PBS buffer lanes and
 * are released to assembly in an order that balances color-batch cohesion with
 * JIS (Just-In-Sequence) due-date adherence.
 *
 * @property id         unique body identifier (e.g. "BODY-00042")
 * @property color      paint color (e.g. "RED", "SILVER")
 * @property model      vehicle model name
 * @property options    set of installed option codes (e.g. "SUNROOF")
 * @property dueDateSeq JIS target assembly sequence number (0-indexed)
 * @property currentLane PBS lane the body is currently assigned to; null if unassigned
 * @property lanePos    position within the lane (0 = front); -1 if unassigned
 */
data class Body(
    @field:JsonProperty("id") val id: String,
    @field:JsonProperty("color") val color: String,
    @field:JsonProperty("model") val model: String,
    @field:JsonProperty("options") val options: Set<String>,
    @field:JsonProperty("dueDateSeq") val dueDateSeq: Int,
    @field:JsonProperty("currentLane") val currentLane: String?,
    @field:JsonProperty("lanePos") val lanePos: Int
) {
    init {
        require(id.isNotBlank()) { "Body id must not be blank" }
        require(color.isNotBlank()) { "color must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(dueDateSeq >= 0) { "dueDateSeq must be >= 0" }
    }

    fun withLaneAssignment(laneId: String, pos: Int): Body =
        copy(currentLane = laneId, lanePos = pos)

    fun isAssigned(): Boolean = currentLane != null

    companion object {
        fun incoming(id: String, color: String, model: String, options: Set<String>, dueDateSeq: Int): Body =
            Body(id, color, model, options.toSet(), dueDateSeq, null, -1)
    }
}
