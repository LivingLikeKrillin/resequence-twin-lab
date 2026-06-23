package com.resequencetwin.control.pbs

import java.util.LinkedHashMap

/**
 * Mutable state of the Paint Buffer Store (PBS) at a single simulation instant.
 *
 * Holds all [Lane] instances keyed by lane id, the paint-shop arrival stream
 * ([paintStream]), and the assembly-out sequence ([assemblyOut]).
 *
 * Create via [withLanes].
 */
class PbsState private constructor(lanes: List<Lane>) {

    private val lanes: Map<String, Lane>
    private val _paintStream: MutableList<Body> = mutableListOf()
    private val _assemblyOut: MutableList<Body> = mutableListOf()

    init {
        val map = LinkedHashMap<String, Lane>()
        for (l in lanes) {
            if (map.put(l.id, l) != null)
                throw IllegalArgumentException("Duplicate lane id: ${l.id}")
        }
        this.lanes = map.toMap()
    }

    companion object {
        fun withLanes(lanes: List<Lane>): PbsState = PbsState(lanes)
    }

    fun lane(laneId: String): Lane? = lanes[laneId]

    fun laneIds(): List<String> = lanes.keys.toList()

    fun paintStream(): List<Body> = _paintStream.toList()

    fun assemblyOut(): List<Body> = _assemblyOut.toList()

    fun arrive(body: Body) {
        _paintStream.add(body)
    }

    fun assignToLane(body: Body, laneId: String): Body {
        val lane = requireLane(laneId)
        return lane.assign(body)
    }

    fun releaseFromLane(laneId: String): Body {
        val lane = requireLane(laneId)
        val released = lane.release()
        _assemblyOut.add(released)
        return released
    }

    private fun requireLane(laneId: String): Lane =
        lanes[laneId] ?: throw IllegalArgumentException("Unknown lane: $laneId")
}
