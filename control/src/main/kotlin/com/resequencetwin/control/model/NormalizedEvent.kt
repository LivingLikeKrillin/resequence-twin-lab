package com.resequencetwin.control.model

/**
 * A normalized plant event received from the Ditto twin layer.
 *
 * All fields are plain Kotlin types; no Jackson annotations needed here
 * because this record is only deserialized from internal sources.
 *
 * @property eventId      globally unique event identifier (idempotency key)
 * @property eventType    event type string (e.g. "UnitArrived", "ResourceOccupied")
 * @property simTs        simulation timestamp (seconds)
 * @property seq          monotonic sequence number within the event stream
 * @property unitId       Ditto Thing ID of the car body unit
 * @property resourceId   Ditto Thing ID of the plant resource (station/lane)
 * @property moveId       identifier of the associated move operation
 * @property causationId  parent event that caused this event (traceability)
 */
data class NormalizedEvent(
    val eventId: String,
    val eventType: String,
    val simTs: Double,
    val seq: Int,
    val unitId: String,
    val resourceId: String,
    val moveId: String,
    val causationId: String
)
