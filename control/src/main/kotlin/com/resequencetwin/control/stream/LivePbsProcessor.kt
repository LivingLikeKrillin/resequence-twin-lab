package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Body
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.pbs.PbsState
import java.util.concurrent.ConcurrentHashMap

/**
 * Live event-driven PBS state machine.
 *
 * ## Threading model
 * Designed for **single-consumer** use (one Kafka partition listener thread per
 * processor instance). [process] and [snapshot] are @Synchronized as a safety belt.
 * A future multi-partition deployment should use one processor per partition.
 *
 * ## Idempotency / replay safety
 * Every [PbsEvent.eventId] is recorded via ConcurrentHashMap.putIfAbsent.
 * Re-processing the same id returns [ProcessResult.Duplicate] without mutating state.
 * Replaying a full event log yields the same [snapshot] as the original run.
 *
 * ## Out-of-order policy: process-anyway
 * Stale events (seq <= last-seen) are still applied; [LiveSnapshot.outOfOrder] increments.
 * Rationale: silently dropping risks losing real mutations (e.g. LaneBlocked arriving late).
 *
 * ## Lane-block degradation
 * Blocked lanes are excluded from assignment and release. Bodies that cannot be assigned
 * enter [deferredBuffer] and are retried on LaneUnblocked and after each ReleaseTick.
 *
 * ## Release selection and lane assignment
 * Release and assignment delegate directly to [DynamicSequencingPolicy] with an
 * eligible-lane filter: [DynamicSequencingPolicy.selectRelease] and
 * [DynamicSequencingPolicy.assignLane] each accept a `Set<String>` of eligible lane ids,
 * ensuring the policy's internal tracking (colour continuity, option leveling, due-date
 * urgency) remains the single source of truth.
 *
 * @param lanes list of Lane instances defining the PBS buffer topology.
 */
class LivePbsProcessor(lanes: List<Lane>) {

    private val state: PbsState = PbsState.withLanes(lanes)
    private val assignPolicy: DynamicSequencingPolicy = DynamicSequencingPolicy()

    // ConcurrentHashMap is used here deliberately even though process() and snapshot() are
    // @Synchronized: it communicates that the map's atomicity contract (putIfAbsent) is the
    // load-bearing idempotency primitive, not just the method-level lock. The lock protects
    // the Int counters (releases, colourChanges, etc.) which are not thread-safe on their own.
    // A future lock-free read of processedIds (e.g., "has event X been seen?") is also possible
    // without holding the object monitor. Do NOT downgrade to HashMap without adding AtomicInteger
    // for all counters.
    // Key = eventId (idempotency guard). Value = event.seq at first processing (aids replay/debug).
    private val processedIds = ConcurrentHashMap<String, Long>()
    private val blockedLaneIds: MutableSet<String> = mutableSetOf()
    private val deferredBuffer: ArrayDeque<Body> = ArrayDeque()
    private var lastSeq: Long = Long.MIN_VALUE

    private var releases: Int = 0
    private var colourChanges: Int = 0
    private var lastReleasedColour: String? = null
    private var rejected: Int = 0
    private var duplicates: Int = 0
    private var outOfOrder: Int = 0

    @Synchronized
    fun process(event: PbsEvent): ProcessResult {
        if (processedIds.putIfAbsent(event.eventId, event.seq) != null) {
            duplicates++
            return ProcessResult.Duplicate
        }
        if (event.seq <= lastSeq) outOfOrder++ else lastSeq = event.seq

        return when (event) {
            is BodyArrived   -> handleBodyArrived(event)
            is ReleaseTick   -> handleReleaseTick()
            is LaneBlocked   -> handleLaneBlocked(event)
            is LaneUnblocked -> handleLaneUnblocked(event)
        }
    }

    @Synchronized
    fun snapshot(): LiveSnapshot {
        val laneOccupants = state.laneIds().associateWith { id ->
            state.lane(id)!!.occupants().map { it.id }
        }
        return LiveSnapshot(
            laneOccupants = laneOccupants,
            assemblyOutSize = state.assemblyOut().size,
            releases = releases,
            colourChanges = colourChanges,
            rejected = rejected,
            duplicates = duplicates,
            outOfOrder = outOfOrder,
            buffered = deferredBuffer.size,
            blockedLanes = blockedLaneIds.toSet(),
        )
    }

    private fun handleBodyArrived(event: BodyArrived): ProcessResult {
        if (event.bodyId.isBlank()) { rejected++; return ProcessResult.Rejected("bodyId must not be blank") }
        if (event.color.isBlank())  { rejected++; return ProcessResult.Rejected("color must not be blank") }
        if (event.model.isBlank())  { rejected++; return ProcessResult.Rejected("model must not be blank") }
        if (event.dueDateSeq < 0)   { rejected++; return ProcessResult.Rejected("dueDateSeq must be >= 0, got ${event.dueDateSeq}") }

        val body = Body.incoming(event.bodyId, event.color, event.model, event.options, event.dueDateSeq)
        state.arrive(body)
        return tryAssign(body)
    }

    private fun handleReleaseTick(): ProcessResult {
        val eligibleIds = eligibleForRelease()
        if (eligibleIds.isEmpty()) return ProcessResult.Idle
        // Pre-check: ensure at least one eligible lane has a releasable body (should always be
        // true when eligibleIds is non-empty, since eligibleForRelease filters empty lanes).
        // The pre-check avoids crashing the processor if state is inconsistent.
        val released = assignPolicy.selectRelease(state, eligibleIds.toSet())

        releases++
        val colourChanged = lastReleasedColour != null && lastReleasedColour != released.color
        if (colourChanged) colourChanges++
        lastReleasedColour = released.color
        // NOTE: do NOT update the policy's internal tracking here (colour continuity,
        // option levelling, due-date urgency) — those are maintained exclusively inside
        // DynamicSequencingPolicy.doRelease. `lastReleasedColour` above is a KPI field
        // for snapshot reporting only.

        drainBuffer()
        return ProcessResult.Released(released, colourChanged)
    }

    private fun handleLaneBlocked(event: LaneBlocked): ProcessResult {
        if (event.laneId !in state.laneIds()) {
            rejected++
            return ProcessResult.Rejected("Unknown lane: ${event.laneId}")
        }
        blockedLaneIds += event.laneId
        return ProcessResult.LaneStatusChanged(event.laneId, blocked = true)
    }

    private fun handleLaneUnblocked(event: LaneUnblocked): ProcessResult {
        if (event.laneId !in state.laneIds()) {
            rejected++
            return ProcessResult.Rejected("Unknown lane: ${event.laneId}")
        }
        blockedLaneIds -= event.laneId
        drainBuffer()
        return ProcessResult.LaneStatusChanged(event.laneId, blocked = false)
    }

    private fun eligibleForAssignment(): List<String> =
        state.laneIds().filter { it !in blockedLaneIds && state.lane(it)?.isFull() == false }

    private fun eligibleForRelease(): List<String> =
        state.laneIds().filter { it !in blockedLaneIds && state.lane(it)?.isEmpty() == false }

    private fun tryAssign(body: Body): ProcessResult {
        val available = eligibleForAssignment()
        if (available.isEmpty()) {
            deferredBuffer.addLast(body)
            return ProcessResult.Deferred(body.id)
        }
        val laneId = assignPolicy.assignLane(body, state, available.toSet())
        state.assignToLane(body, laneId)
        return ProcessResult.Assigned(body.id, laneId)
    }

    private fun drainBuffer() {
        val iter = deferredBuffer.iterator()
        while (iter.hasNext()) {
            val body = iter.next()
            val available = eligibleForAssignment()
            if (available.isEmpty()) break
            iter.remove()
            val laneId = assignPolicy.assignLane(body, state, available.toSet())
            state.assignToLane(body, laneId)
        }
    }
}
