package com.resequencetwin.control.stream

/**
 * Immutable point-in-time view of the live PBS state machine.
 *
 * @property laneOccupants   map of laneId → ordered list of bodyIds (front first)
 * @property assemblyOutSize total number of bodies released to assembly so far
 * @property releases        count of Released events produced
 * @property colourChanges   count of consecutive-release colour switches
 * @property rejected        count of Rejected events
 * @property duplicates      count of Duplicate events
 * @property outOfOrder      count of events with out-of-order seq
 * @property buffered        current size of the deferred-body buffer
 * @property blockedLanes    set of lane ids currently blocked
 */
data class LiveSnapshot(
    val laneOccupants: Map<String, List<String>>,
    val assemblyOutSize: Int,
    val releases: Int,
    val colourChanges: Int,
    val rejected: Int,
    val duplicates: Int,
    val outOfOrder: Int,
    val buffered: Int,
    val blockedLanes: Set<String>,
)
