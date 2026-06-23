package com.resequencetwin.control.stream

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Sealed hierarchy of events consumed by [LivePbsProcessor].
 *
 * Every event carries:
 * - [eventId]: globally unique ID used for idempotent dedup (string UUID or any opaque key).
 * - [seq]: monotonic source-sequence number. The chosen policy is **process-anyway**:
 *   the event is applied regardless of ordering, but [LiveSnapshot.outOfOrder] is incremented.
 *
 * **Rush orders** are modelled as a [BodyArrived] with a very low [BodyArrived.dueDateSeq]
 * (e.g. 0). The DynamicSequencingPolicy anti-starvation overdue-override promotes them
 * automatically — no separate event type needed.
 *
 * **Wire format**: JSON with a `type` discriminator field (value = simple class name).
 * Example: `{"type":"BodyArrived","eventId":"e1","seq":1,"bodyId":"B1",...}`
 */
/** Enables polymorphic JSON deserialization via the `type` discriminator field.
 *  All four concrete subtypes must be listed here so Jackson can resolve them. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = BodyArrived::class,    name = "BodyArrived"),
    JsonSubTypes.Type(value = ReleaseTick::class,    name = "ReleaseTick"),
    JsonSubTypes.Type(value = LaneBlocked::class,    name = "LaneBlocked"),
    JsonSubTypes.Type(value = LaneUnblocked::class,  name = "LaneUnblocked"),
)
sealed interface PbsEvent {
    val eventId: String
    val seq: Long
}

/**
 * A car body arrives from the paint shop and must be assigned to a PBS lane.
 *
 * @property bodyId      unique body identifier
 * @property color       paint color (non-blank)
 * @property model       vehicle model name (non-blank)
 * @property options     installed option codes
 * @property dueDateSeq  JIS target assembly position (>=0); set to 0 for a rush order
 */
data class BodyArrived(
    override val eventId: String,
    override val seq: Long,
    val bodyId: String,
    val color: String,
    val model: String,
    val options: Set<String> = emptySet(),
    val dueDateSeq: Int = 0,
) : PbsEvent

/**
 * Cadence tick: the assembly line requests one body. Processor calls selection logic
 * on non-blocked, non-empty lanes. If nothing is releasable, returns [ProcessResult.Idle].
 */
data class ReleaseTick(
    override val eventId: String,
    override val seq: Long,
) : PbsEvent

/**
 * A lane enters a fault state (maintenance / jam). Excluded from assignment/release
 * until [LaneUnblocked] is received.
 */
data class LaneBlocked(
    override val eventId: String,
    override val seq: Long,
    val laneId: String,
) : PbsEvent

/**
 * A previously blocked lane becomes available again. Processor drains buffered bodies
 * into the newly available lane on receipt.
 */
data class LaneUnblocked(
    override val eventId: String,
    override val seq: Long,
    val laneId: String,
) : PbsEvent
