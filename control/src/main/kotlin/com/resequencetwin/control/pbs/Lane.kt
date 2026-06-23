package com.resequencetwin.control.pbs

import java.util.ArrayDeque
import java.util.NoSuchElementException

/**
 * A single FIFO buffer lane in the Paint Buffer Store.
 *
 * Bodies enter at the rear ([assign]) and leave from the front ([release]).
 * Capacity is fixed at construction time; assignments beyond capacity throw [LaneFullException].
 *
 * @property id       unique lane identifier
 * @property capacity maximum number of bodies the lane can hold simultaneously
 */
class Lane(val id: String, val capacity: Int) {

    class LaneFullException(laneId: String, capacity: Int) :
        RuntimeException("Lane $laneId is full (capacity=$capacity)")

    private val occupants: ArrayDeque<Body>

    init {
        require(id.isNotBlank()) { "Lane id must not be blank" }
        require(capacity > 0) { "Lane capacity must be > 0" }
        occupants = ArrayDeque(capacity)
    }

    fun size(): Int = occupants.size
    fun isEmpty(): Boolean = occupants.isEmpty()
    fun isFull(): Boolean = occupants.size >= capacity

    fun occupants(): List<Body> = occupants.toList()

    fun front(): Body? = occupants.peekFirst()

    fun assign(body: Body): Body {
        if (isFull()) throw LaneFullException(id, capacity)
        val pos = occupants.size
        val assigned = body.withLaneAssignment(id, pos)
        occupants.addLast(assigned)
        return assigned
    }

    fun release(): Body {
        if (occupants.isEmpty()) throw NoSuchElementException("Lane $id is empty")
        return occupants.pollFirst()
    }
}
