# Live Event-Driven PBS State Machine Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure event-driven `LivePbsProcessor` in package `com.resequencetwin.control.stream` that processes a stream of `PbsEvent`s one at a time and maintains live PBS state — with idempotent replay, graceful lane-block degradation, body buffering when all lanes are blocked, and out-of-order tolerance — without touching Kafka, Spring, or any existing `pbs/`/`bench/`/`api/`/`legacy-oht/` files.

**Architecture:** A sealed `PbsEvent` hierarchy carries `eventId` (dedup) and `seq` (ordering check). `LivePbsProcessor` wraps a `PbsState` + `DynamicSequencingPolicy` and exposes a single `process(event): ProcessResult` function, atomically deduplicating via `ConcurrentHashMap.putIfAbsent`, routing events through an exhaustive `when` expression, buffering bodies when all eligible lanes are full/blocked, and returning typed results. `LiveSnapshot` captures all KPI counters for metrics/query.

**Tech Stack:** Kotlin (JVM 21), JUnit 5 + AssertJ, existing `pbs.*` classes — no new Maven dependencies.

---

## Chunk 1: Event Model + Result Types

### Task 1: Define the PbsEvent sealed interface hierarchy

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/stream/PbsEvent.kt`

---

- [ ] **Step 1: Write the file**

```kotlin
package com.resequencetwin.control.stream

/**
 * Sealed hierarchy of events consumed by [LivePbsProcessor].
 *
 * Every event carries:
 * - [eventId]: globally unique ID used for idempotent dedup (string UUID or any opaque key).
 * - [seq]: monotonic source-sequence number. The processor tracks the last-seen seq
 *   per source to detect out-of-order delivery. The chosen policy is **process-anyway**:
 *   the event is applied regardless of ordering, but the [LiveSnapshot.outOfOrder] counter
 *   is incremented. This keeps the processor append-only and replay-safe.
 *
 * **Rush orders** are modelled as a [BodyArrived] with a very low [BodyArrived.dueDateSeq]
 * (e.g. 0). The [DynamicSequencingPolicy] anti-starvation overdue-override then promotes
 * them to the front of the release queue automatically — no separate event type needed.
 */
sealed interface PbsEvent {
    val eventId: String
    val seq: Long
}

/**
 * A car body arrives from the paint shop and must be assigned to a PBS lane.
 *
 * @property bodyId      unique body identifier (maps to [com.resequencetwin.control.pbs.Body.id])
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
 * Cadence tick: the assembly line requests one body. [LivePbsProcessor] calls
 * [com.resequencetwin.control.pbs.DynamicSequencingPolicy.selectRelease] on non-blocked,
 * non-empty lanes. If nothing is releasable the processor returns [ProcessResult.Idle].
 */
data class ReleaseTick(
    override val eventId: String,
    override val seq: Long,
) : PbsEvent

/**
 * A lane enters a fault state (maintenance / jam). It is excluded from
 * both assignment and release until [LaneUnblocked] is received.
 */
data class LaneBlocked(
    override val eventId: String,
    override val seq: Long,
    val laneId: String,
) : PbsEvent

/**
 * A previously blocked lane becomes available again. On receipt the processor
 * attempts to drain any buffered bodies into the newly available lane.
 */
data class LaneUnblocked(
    override val eventId: String,
    override val seq: Long,
    val laneId: String,
) : PbsEvent
```

- [ ] **Step 2: Verify the file compiles (no test yet)**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q compile`
Expected: BUILD SUCCESS

---

### Task 2: Define the ProcessResult sealed hierarchy

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/stream/ProcessResult.kt`

---

- [ ] **Step 1: Write the file**

```kotlin
package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Body

/**
 * Typed outcome of [LivePbsProcessor.process].
 *
 * The caller can pattern-match exhaustively with `when`.
 */
sealed interface ProcessResult {

    /** Event was a duplicate ([PbsEvent.eventId] already processed). State unchanged. */
    data object Duplicate : ProcessResult

    /** [BodyArrived] failed validation (blank field / negative dueDateSeq). State unchanged. */
    data class Rejected(val reason: String) : ProcessResult

    /**
     * [BodyArrived] was valid but all non-blocked lanes were full at arrival time.
     * The body has been placed in the internal buffer and will be assigned on the
     * next [LaneUnblocked] event or after any [ReleaseTick] frees capacity.
     */
    data class Deferred(val bodyId: String) : ProcessResult

    /** [BodyArrived] body successfully assigned to a lane. */
    data class Assigned(val bodyId: String, val laneId: String) : ProcessResult

    /** [ReleaseTick] produced a release. */
    data class Released(val body: Body, val colourChanged: Boolean) : ProcessResult

    /** [ReleaseTick] fired but no non-blocked, non-empty lane was available. */
    data object Idle : ProcessResult

    /** [LaneBlocked] / [LaneUnblocked] acknowledged. */
    data class LaneStatusChanged(val laneId: String, val blocked: Boolean) : ProcessResult
}
```

- [ ] **Step 2: Compile**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q compile`
Expected: BUILD SUCCESS

---

### Task 3: Define LiveSnapshot

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/stream/LiveSnapshot.kt`

---

- [ ] **Step 1: Write the file**

```kotlin
package com.resequencetwin.control.stream

/**
 * Immutable point-in-time view of the live PBS state machine.
 *
 * Returned by [LivePbsProcessor.snapshot] and safe to read from any thread
 * after `process()` has returned (the processor synchronizes internally).
 *
 * @property laneOccupants   map of laneId → ordered list of bodyIds (front first)
 * @property assemblyOutSize total number of bodies released to assembly so far
 * @property releases        count of [ProcessResult.Released] events produced
 * @property colourChanges   count of consecutive-release colour switches in assemblyOut
 * @property rejected        count of [ProcessResult.Rejected] events
 * @property duplicates      count of [ProcessResult.Duplicate] events
 * @property outOfOrder      count of events whose [PbsEvent.seq] arrived out of order
 * @property buffered        current size of the deferred-body buffer
 * @property blockedLanes    set of lane ids currently in the blocked state
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
```

- [ ] **Step 2: Compile**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q compile`
Expected: BUILD SUCCESS

---

## Chunk 2: LivePbsProcessor Core

### Task 4: Implement LivePbsProcessor

**Files:**
- Create: `control/src/main/kotlin/com/resequencetwin/control/stream/LivePbsProcessor.kt`

The processor must be:
- **Single-consumer by design**: a Kafka listener per partition is single-threaded; `process()` is `@Synchronized` for safety and documents that assumption.
- **Idempotent**: `ConcurrentHashMap.putIfAbsent` is the atomic dedup guard (mirrors `DittoProjector`).
- **Out-of-order policy**: process-anyway + increment counter. Rationale: dropping stale events risks losing real state mutations (e.g. a blocked-lane event arriving late); accepting them is safer and KPI-transparent.

---

- [ ] **Step 1: Write the file**

```kotlin
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
 * Designed for **single-consumer** use (one Kafka partition → one listener thread).
 * [process] is `@Synchronized` as an extra safety belt; a future multi-partition
 * setup would need per-partition processor instances instead.
 *
 * ## Idempotency / replay safety
 * Every [PbsEvent.eventId] is recorded in [processedIds] via `putIfAbsent`.
 * Re-processing the same id returns [ProcessResult.Duplicate] without mutating state.
 * Replaying a full event log yields the same [snapshot] as the original run.
 *
 * ## Out-of-order policy: process-anyway
 * If an event's [PbsEvent.seq] is ≤ the last-seen seq, it is still applied and
 * [outOfOrder] is incremented. Dropping stale events risks losing real mutations
 * (e.g. a [LaneBlocked] arriving late). The counter makes this visible.
 *
 * ## Lane-block degradation
 * Blocked lanes are excluded from assignment and release. Bodies that cannot be
 * assigned (all eligible lanes full) are placed in [deferredBuffer] and retried
 * on [LaneUnblocked] and after each [ReleaseTick] that may free capacity.
 *
 * @param laneConfig list of [Lane] instances that define the PBS topology.
 */
class LivePbsProcessor(laneConfig: List<Lane>) {

    private val state: PbsState = PbsState.withLanes(laneConfig)
    private val policy: DynamicSequencingPolicy = DynamicSequencingPolicy()

    // --- idempotency ---------------------------------------------------------
    /** Atomic dedup guard. Value is a placeholder (1L); only key presence matters. */
    private val processedIds = ConcurrentHashMap<String, Long>()

    // --- fault state ----------------------------------------------------------
    /** Lane ids currently in the blocked state. */
    private val blockedLaneIds: MutableSet<String> = mutableSetOf()

    /** Bodies awaiting assignment because all eligible lanes were full at arrival. */
    private val deferredBuffer: ArrayDeque<Body> = ArrayDeque()

    // --- ordering -------------------------------------------------------------
    /** Last-seen [PbsEvent.seq]; used for out-of-order detection. */
    private var lastSeq: Long = Long.MIN_VALUE

    // --- KPI counters ---------------------------------------------------------
    private var releases: Int = 0
    private var colourChanges: Int = 0
    private var lastReleasedColour: String? = null
    private var rejected: Int = 0
    private var duplicates: Int = 0
    private var outOfOrder: Int = 0

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Process one event and return a typed [ProcessResult].
     *
     * Guaranteed to never throw for well-formed events; malformed [BodyArrived]
     * (validation failures) produce [ProcessResult.Rejected] instead.
     *
     * @param event the event to process
     * @return typed outcome; caller should pattern-match exhaustively
     */
    @Synchronized
    fun process(event: PbsEvent): ProcessResult {
        // --- idempotent dedup ---
        if (processedIds.putIfAbsent(event.eventId, 1L) != null) {
            duplicates++
            return ProcessResult.Duplicate
        }

        // --- out-of-order detection ---
        if (event.seq <= lastSeq) {
            outOfOrder++
            // policy: process anyway (see KDoc)
        } else {
            lastSeq = event.seq
        }

        return when (event) {
            is BodyArrived   -> handleBodyArrived(event)
            is ReleaseTick   -> handleReleaseTick()
            is LaneBlocked   -> handleLaneBlocked(event)
            is LaneUnblocked -> handleLaneUnblocked(event)
        }
    }

    /**
     * Return an immutable snapshot of the current live state.
     * Safe to call after [process] returns; do NOT call from a different thread
     * while [process] may be running (no separate read-lock).
     */
    @Synchronized
    fun snapshot(): LiveSnapshot {
        val laneOccupants = state.laneIds().associateWith { laneId ->
            state.lane(laneId)!!.occupants().map { it.id }
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

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    private fun handleBodyArrived(event: BodyArrived): ProcessResult {
        // --- validation ---
        if (event.bodyId.isBlank()) {
            rejected++
            return ProcessResult.Rejected("bodyId must not be blank")
        }
        if (event.color.isBlank()) {
            rejected++
            return ProcessResult.Rejected("color must not be blank")
        }
        if (event.model.isBlank()) {
            rejected++
            return ProcessResult.Rejected("model must not be blank")
        }
        if (event.dueDateSeq < 0) {
            rejected++
            return ProcessResult.Rejected("dueDateSeq must be >= 0, got ${event.dueDateSeq}")
        }

        val body = Body.incoming(event.bodyId, event.color, event.model, event.options, event.dueDateSeq)
        state.arrive(body)
        return tryAssign(body)
    }

    private fun handleReleaseTick(): ProcessResult {
        val eligibleLaneIds = state.laneIds()
            .filter { it !in blockedLaneIds }
            .filter { state.lane(it)?.isEmpty() == false }

        if (eligibleLaneIds.isEmpty()) return ProcessResult.Idle

        // Build a filtered state view: policy.selectRelease reads from state directly,
        // so we temporarily patch eligibility by asking the policy to choose among
        // non-blocked non-empty lanes. We intercept the selection by delegating to a
        // narrow helper that only considers eligible lane fronts.
        val released = selectAndRelease(eligibleLaneIds) ?: return ProcessResult.Idle

        releases++
        val colourChanged = lastReleasedColour != null && lastReleasedColour != released.color
        if (colourChanged) colourChanges++
        lastReleasedColour = released.color

        // After a release, a lane may have freed a slot — try draining the buffer.
        drainBuffer()

        return ProcessResult.Released(released, colourChanged)
    }

    private fun handleLaneBlocked(event: LaneBlocked): ProcessResult {
        blockedLaneIds.add(event.laneId)
        return ProcessResult.LaneStatusChanged(event.laneId, blocked = true)
    }

    private fun handleLaneUnblocked(event: LaneUnblocked): ProcessResult {
        blockedLaneIds.remove(event.laneId)
        drainBuffer()
        return ProcessResult.LaneStatusChanged(event.laneId, blocked = false)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Try to assign [body] to a non-blocked, non-full lane via the policy.
     * If no lane is available the body is deferred.
     */
    private fun tryAssign(body: Body): ProcessResult {
        val available = state.laneIds().filter { laneId ->
            laneId !in blockedLaneIds && state.lane(laneId)?.isFull() == false
        }
        if (available.isEmpty()) {
            deferredBuffer.addLast(body)
            return ProcessResult.Deferred(body.id)
        }

        // The policy's assignLane reads all lanes from state; blocked/full lanes will
        // still appear, so we use a wrapper that only exposes eligible lanes.
        val filteredState = FilteredPbsState(state, available.toSet())
        val laneId = policy.assignLane(body, filteredState)
        state.assignToLane(body, laneId)
        return ProcessResult.Assigned(body.id, laneId)
    }

    /**
     * Attempt to drain the deferred buffer into now-available lanes.
     * Called after [ReleaseTick] and [LaneUnblocked].
     */
    private fun drainBuffer() {
        val iter = deferredBuffer.iterator()
        while (iter.hasNext()) {
            val body = iter.next()
            val available = state.laneIds().filter { laneId ->
                laneId !in blockedLaneIds && state.lane(laneId)?.isFull() == false
            }
            if (available.isEmpty()) break
            iter.remove()
            val filteredState = FilteredPbsState(state, available.toSet())
            val laneId = policy.assignLane(body, filteredState)
            state.assignToLane(body, laneId)
        }
    }

    /**
     * Release from the non-blocked, non-empty lane chosen by the policy.
     *
     * We need the policy's overdue-override and scoring logic, but it reads ALL
     * lanes from [PbsState]. We wrap the state so only [eligibleLaneIds] are
     * visible to [DynamicSequencingPolicy.selectRelease].
     */
    private fun selectAndRelease(eligibleLaneIds: List<String>): Body? {
        if (eligibleLaneIds.isEmpty()) return null
        val filteredState = FilteredPbsState(state, eligibleLaneIds.toSet())
        return try {
            policy.selectRelease(filteredState)
            // selectRelease calls state.releaseFromLane which mutates the real state
            // via the FilteredPbsState delegation below — so assemblyOut is updated.
        } catch (_: IllegalStateException) {
            null
        }
    }
}

/**
 * A thin delegation wrapper that presents only the [visibleLaneIds] subset of a
 * [PbsState] to a [com.resequencetwin.control.pbs.SequencingPolicy].
 *
 * Reads ([laneIds], [lane], [assemblyOut]) are filtered to visible lanes.
 * Writes ([arrive], [assignToLane], [releaseFromLane]) pass through to the real state
 * so mutations are reflected in the live snapshot.
 *
 * Package-private (no `internal` modifier needed — same package).
 */
internal class FilteredPbsState(
    private val delegate: PbsState,
    private val visibleLaneIds: Set<String>,
) : PbsState(buildFilteredLanes(delegate, visibleLaneIds)) {
    // We cannot extend PbsState without the real lanes, so we use delegation instead.
    // Actually PbsState is not open, so we copy the approach differently.
    // See the companion factory below.
    init {
        throw UnsupportedOperationException("Use FilteredPbsState.wrap()")
    }

    companion object {
        fun buildFilteredLanes(delegate: PbsState, visible: Set<String>): List<com.resequencetwin.control.pbs.Lane> {
            // not used directly — see the real FilteredPbsState impl below
            return emptyList()
        }
    }
}
```

Wait — `PbsState` is not `open` and has a private constructor. We cannot subclass it. We need a different approach for the filtered-state wrapper. Let me revise the design:

**Revised approach**: Instead of subclassing `PbsState`, we will:
1. Teach `DynamicSequencingPolicy.assignLane` / `selectRelease` what lanes to consider by pre-filtering lane IDs ourselves and calling the low-level state methods directly — bypassing the policy's lane iteration, which reads `state.laneIds()`.

Since `DynamicSequencingPolicy` calls `state.laneIds()` and `state.lane(id)`, the cleanest solution without modifying existing classes is to **build a temporary `PbsState` wrapper** using a different approach: we construct a fresh `PbsState` with only the eligible lanes as a read-only view for *scoring/selection*, then invoke the real `releaseFromLane` on the live state.

But the policy's `doRelease` calls `state.releaseFromLane(laneId)` on the state passed to it — we need that to call our real state. Since `PbsState` is concrete and non-open, we need to **duplicate the selection logic** at the processor level.

**Final revised design**: Re-implement lane selection inline in the processor, reusing the scoring logic by calling `policy.score(body, laneId, state)` — but `score` is `internal` to the policy. Instead, we will implement a minimal `selectRelease` loop inside the processor that:
1. Reads eligible lane fronts.
2. Applies the same overdue-override rule (front.dueDateSeq <= assemblyOut.size).
3. Calls the real `state.releaseFromLane(bestLane)` directly.

This avoids subclassing and avoids touching existing files.

- [ ] **Step 1: Write the FINAL LivePbsProcessor.kt**

```kotlin
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
 * processor instance). [process] and [snapshot] are `@Synchronized` so that
 * a monitoring thread can safely call [snapshot] concurrently. A future
 * multi-partition deployment should use one processor per partition.
 *
 * ## Idempotency / replay safety
 * Every [PbsEvent.eventId] is recorded in an internal `ConcurrentHashMap` via
 * `putIfAbsent`. Re-processing the same id returns [ProcessResult.Duplicate]
 * without mutating state. Replaying a full event log in order yields the same
 * [snapshot] as the original run.
 *
 * ## Out-of-order policy: process-anyway
 * If an event's [PbsEvent.seq] is ≤ the last-seen seq, the event is still applied
 * and [LiveSnapshot.outOfOrder] is incremented. Rationale: silently dropping stale
 * events risks losing real state mutations (e.g. a [LaneBlocked] arriving late);
 * processing them and making the anomaly visible in KPIs is safer and more honest.
 *
 * ## Lane-block degradation
 * Blocked lanes are excluded from both assignment and release. Bodies that cannot
 * be assigned (all non-blocked lanes are full) enter [deferredBuffer] and are
 * retried on every [LaneUnblocked] event and after every [ReleaseTick] that
 * produces a release (freeing at least one slot).
 *
 * ## Release selection (inline replica of DynamicSequencingPolicy logic)
 * `DynamicSequencingPolicy.selectRelease` iterates `state.laneIds()` which
 * includes blocked lanes, and `PbsState` is final (cannot be subclassed).
 * We therefore **replicate** the overdue-override + weighted-score selection
 * inline, filtering to eligible lanes, and call `state.releaseFromLane` directly.
 * The policy's `assignLane` is used for arrivals: we pass a `PbsState` whose
 * lane config contains only non-blocked, non-full lanes (constructed on-the-fly
 * from [Lane] instances we keep a separate reference to).
 *
 * @param lanes list of [Lane] instances defining the PBS buffer topology.
 */
class LivePbsProcessor(lanes: List<Lane>) {

    // Keep a reference to the Lane objects so we can build filtered PbsState views.
    private val laneMap: Map<String, Lane> = lanes.associateBy { it.id }

    private val state: PbsState = PbsState.withLanes(lanes)

    /** Used only for assignLane (colour-affinity / least-loaded selection). */
    private val assignPolicy: DynamicSequencingPolicy = DynamicSequencingPolicy()

    // --- idempotency ---------------------------------------------------------
    private val processedIds = ConcurrentHashMap<String, Long>()

    // --- fault state ----------------------------------------------------------
    private val blockedLaneIds: MutableSet<String> = mutableSetOf()
    private val deferredBuffer: ArrayDeque<Body> = ArrayDeque()

    // --- ordering -------------------------------------------------------------
    private var lastSeq: Long = Long.MIN_VALUE

    // --- KPI counters ---------------------------------------------------------
    private var releases: Int = 0
    private var colourChanges: Int = 0
    private var lastReleasedColour: String? = null
    private var rejected: Int = 0
    private var duplicates: Int = 0
    private var outOfOrder: Int = 0

    // --- release scoring state (mirrors DynamicSequencingPolicy internals) ---
    private var lastReleasedColorForScore: String? = null
    private val recentHadOptions = BooleanArray(DynamicSequencingPolicy.LEVELING_WINDOW)
    private var releaseCount: Int = 0

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Process one event and return a typed [ProcessResult].
     *
     * Never throws for well-formed events; validation failures produce [ProcessResult.Rejected].
     */
    @Synchronized
    fun process(event: PbsEvent): ProcessResult {
        if (processedIds.putIfAbsent(event.eventId, 1L) != null) {
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

    /** Immutable snapshot of current live state. Thread-safe when called after [process] returns. */
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

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

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

        val laneId = selectBestLane(eligibleIds) ?: return ProcessResult.Idle
        val released = state.releaseFromLane(laneId)

        releases++
        val colourChanged = lastReleasedColour != null && lastReleasedColour != released.color
        if (colourChanged) colourChanges++
        lastReleasedColour = released.color

        // Update internal release-scoring state (mirrors DynamicSequencingPolicy.doRelease).
        lastReleasedColorForScore = released.color
        recentHadOptions[releaseCount % DynamicSequencingPolicy.LEVELING_WINDOW] = released.options.isNotEmpty()
        releaseCount++

        drainBuffer()
        return ProcessResult.Released(released, colourChanged)
    }

    private fun handleLaneBlocked(event: LaneBlocked): ProcessResult {
        blockedLaneIds += event.laneId
        return ProcessResult.LaneStatusChanged(event.laneId, blocked = true)
    }

    private fun handleLaneUnblocked(event: LaneUnblocked): ProcessResult {
        blockedLaneIds -= event.laneId
        drainBuffer()
        return ProcessResult.LaneStatusChanged(event.laneId, blocked = false)
    }

    // -------------------------------------------------------------------------
    // Lane selection helpers
    // -------------------------------------------------------------------------

    private fun eligibleForAssignment(): List<String> =
        state.laneIds().filter { it !in blockedLaneIds && state.lane(it)?.isFull() == false }

    private fun eligibleForRelease(): List<String> =
        state.laneIds().filter { it !in blockedLaneIds && state.lane(it)?.isEmpty() == false }

    /**
     * Try to assign [body] to a non-blocked, non-full lane.
     * Defers to [deferredBuffer] if no lane is available.
     */
    private fun tryAssign(body: Body): ProcessResult {
        val available = eligibleForAssignment()
        if (available.isEmpty()) {
            deferredBuffer.addLast(body)
            return ProcessResult.Deferred(body.id)
        }
        // Build a temporary PbsState view with only the available lanes so the
        // policy's colour-affinity scan is restricted to eligible lanes.
        val filteredLanes = available.mapNotNull { laneMap[it] }
        val filteredState = PbsState.withLanes(filteredLanes)
        // Populate filtered state with current occupants so colour-affinity works.
        for (laneId in available) {
            val occupants = state.lane(laneId)!!.occupants()
            for (occ in occupants) {
                filteredState.assignToLane(occ, laneId)
            }
        }
        val laneId = assignPolicy.assignLane(body, filteredState)
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
            val filteredLanes = available.mapNotNull { laneMap[it] }
            val filteredState = PbsState.withLanes(filteredLanes)
            for (laneId in available) {
                for (occ in state.lane(laneId)!!.occupants()) {
                    filteredState.assignToLane(occ, laneId)
                }
            }
            val laneId = assignPolicy.assignLane(body, filteredState)
            state.assignToLane(body, laneId)
        }
    }

    /**
     * Select the best non-blocked, non-empty lane to release from.
     *
     * Replicates [DynamicSequencingPolicy.selectRelease] logic restricted to [eligibleIds]:
     * 1. Overdue override: front body with dueDateSeq <= assemblyOut.size wins immediately.
     * 2. Weighted score: W_COLOR * colorBonus + W_OPTION * optionLevelingBonus + W_DUE * dueDateBonus.
     */
    private fun selectBestLane(eligibleIds: List<String>): String? {
        val assemblyOutSize = state.assemblyOut().size
        var overdueLane: String? = null
        var earliestOverdue = Int.MAX_VALUE
        for (laneId in eligibleIds) {
            val front = state.lane(laneId)!!.front()!!
            if (front.dueDateSeq <= assemblyOutSize && front.dueDateSeq < earliestOverdue) {
                earliestOverdue = front.dueDateSeq
                overdueLane = laneId
            }
        }
        if (overdueLane != null) return overdueLane

        var bestLane: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (laneId in eligibleIds) {
            val front = state.lane(laneId)!!.front()!!
            val s = scoreBody(front, assemblyOutSize)
            if (s > bestScore) { bestScore = s; bestLane = laneId }
        }
        return bestLane
    }

    private fun scoreBody(body: Body, assemblyOutSize: Int): Double {
        val colorBonus = if (lastReleasedColorForScore == body.color) 1.0 else 0.0
        val windowSize = minOf(releaseCount, DynamicSequencingPolicy.LEVELING_WINDOW)
        val optCount = (0 until windowSize).count { recentHadOptions[it] }
        val optionBonus = if (body.options.isNotEmpty()) {
            1.0 - optCount.toDouble() / maxOf(1, windowSize)
        } else 1.0
        val lag = body.dueDateSeq - assemblyOutSize
        val dueBonus = when {
            lag <= 0 -> 1.0
            lag <= DynamicSequencingPolicy.DUE_DATE_URGENCY_WINDOW -> 1.0 - lag.toDouble() / DynamicSequencingPolicy.DUE_DATE_URGENCY_WINDOW
            else -> 0.0
        }
        return DynamicSequencingPolicy.W_COLOR * colorBonus +
               DynamicSequencingPolicy.W_OPTION * optionBonus +
               DynamicSequencingPolicy.W_DUE * dueBonus
    }
}
```

**Design note on FilteredPbsState**: The `tryAssign`/`drainBuffer` helper builds a *throwaway* `PbsState` with only the eligible lanes, pre-populated with current occupants (for colour-affinity). The *actual* assignment happens on the real `state`. This is slightly heavier than subclassing but avoids any modification to existing files.

- [ ] **Step 2: Compile**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q compile`
Expected: BUILD SUCCESS

---

## Chunk 3: Tests

### Task 5: BodyArrived basic assignment tests

**Files:**
- Create: `control/src/test/kotlin/com/resequencetwin/control/stream/LivePbsProcessorTest.kt`

---

- [ ] **Step 1: Write basic assignment + sequence-fills-lane tests (RED)**

```kotlin
package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Lane
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LivePbsProcessorTest {

    private fun lanes(vararg capacities: Int) = capacities.mapIndexed { i, c -> Lane("L${i+1}", c) }
    private fun arrived(id: String, seq: Long = 1L, color: String = "RED", model: String = "M1",
                        dueDateSeq: Int = 0, options: Set<String> = emptySet()) =
        BodyArrived(eventId = "e-$id-$seq", seq = seq, bodyId = id,
                    color = color, model = model, options = options, dueDateSeq = dueDateSeq)
    private fun tick(seq: Long) = ReleaseTick(eventId = "tick-$seq", seq = seq)

    // ------------------------------------------------------------------
    // BodyArrived → assignment
    // ------------------------------------------------------------------

    @Test
    fun `BodyArrived assigns body to a lane`() {
        val proc = LivePbsProcessor(lanes(5))
        val result = proc.process(arrived("B1"))
        assertThat(result).isInstanceOf(ProcessResult.Assigned::class.java)
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants["L1"]).containsExactly("B1")
    }

    @Test
    fun `multiple arrivals fill lanes per policy`() {
        val proc = LivePbsProcessor(lanes(2, 2))
        repeat(4) { i -> proc.process(arrived("B${i+1}", seq = i.toLong() + 1)) }
        val snap = proc.snapshot()
        val allOccupants = snap.laneOccupants.values.flatten()
        assertThat(allOccupants).hasSize(4)
        assertThat(snap.laneOccupants["L1"]?.size).isEqualTo(2)
        assertThat(snap.laneOccupants["L2"]?.size).isEqualTo(2)
    }

    // ------------------------------------------------------------------
    // ReleaseTick
    // ------------------------------------------------------------------

    @Test
    fun `ReleaseTick releases one body`() {
        val proc = LivePbsProcessor(lanes(3))
        proc.process(arrived("B1", seq = 1))
        val result = proc.process(tick(2))
        assertThat(result).isInstanceOf(ProcessResult.Released::class.java)
        val snap = proc.snapshot()
        assertThat(snap.releases).isEqualTo(1)
        assertThat(snap.assemblyOutSize).isEqualTo(1)
    }

    @Test
    fun `colourChanges KPI increments on colour switch`() {
        val proc = LivePbsProcessor(lanes(5))
        proc.process(arrived("B1", seq = 1, color = "RED", dueDateSeq = 0))
        proc.process(arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1))
        proc.process(tick(3))  // releases B1 (RED)
        proc.process(tick(4))  // releases B2 (BLUE) → colour change
        assertThat(proc.snapshot().colourChanges).isEqualTo(1)
    }

    @Test
    fun `colourChanges does NOT increment when same colour`() {
        val proc = LivePbsProcessor(lanes(5))
        proc.process(arrived("B1", seq = 1, color = "RED", dueDateSeq = 0))
        proc.process(arrived("B2", seq = 2, color = "RED", dueDateSeq = 1))
        proc.process(tick(3))
        proc.process(tick(4))
        assertThat(proc.snapshot().colourChanges).isEqualTo(0)
    }

    @Test
    fun `ReleaseTick on empty processor returns Idle without crash`() {
        val proc = LivePbsProcessor(lanes(3))
        val result = proc.process(tick(1))
        assertThat(result).isEqualTo(ProcessResult.Idle)
        assertThat(proc.snapshot().releases).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // Idempotency / replay safety
    // ------------------------------------------------------------------

    @Test
    fun `same eventId processed twice returns Duplicate second time`() {
        val proc = LivePbsProcessor(lanes(3))
        val event = arrived("B1", seq = 1)
        proc.process(event)
        val second = proc.process(event)
        assertThat(second).isEqualTo(ProcessResult.Duplicate)
        assertThat(proc.snapshot().duplicates).isEqualTo(1)
        // body appears only once
        assertThat(proc.snapshot().laneOccupants["L1"]).hasSize(1)
    }

    @Test
    fun `replaying full event log twice yields same final snapshot`() {
        val events = listOf(
            arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0),
            arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1),
            tick(3),
            tick(4),
        )

        val proc1 = LivePbsProcessor(lanes(5))
        events.forEach { proc1.process(it) }
        val snap1 = proc1.snapshot()

        // Replay: same events on a fresh processor
        val proc2 = LivePbsProcessor(lanes(5))
        events.forEach { proc2.process(it) }
        // replay again on SAME processor (idempotent)
        events.forEach { proc2.process(it) }
        val snap2 = proc2.snapshot()

        assertThat(snap2.assemblyOutSize).isEqualTo(snap1.assemblyOutSize)
        assertThat(snap2.releases).isEqualTo(snap1.releases)
        assertThat(snap2.colourChanges).isEqualTo(snap1.colourChanges)
        assertThat(snap2.laneOccupants).isEqualTo(snap1.laneOccupants)
    }

    // ------------------------------------------------------------------
    // Lane block degradation
    // ------------------------------------------------------------------

    @Test
    fun `blocked lane receives no assignments`() {
        val proc = LivePbsProcessor(lanes(2, 2))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 1, laneId = "L1"))
        proc.process(arrived("B1", seq = 2))
        proc.process(arrived("B2", seq = 3))
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants["L1"]).isEmpty()
        assertThat(snap.laneOccupants["L2"]).containsExactlyInAnyOrder("B1", "B2")
    }

    @Test
    fun `blocked lane excluded from release`() {
        val proc = LivePbsProcessor(lanes(2, 2))
        proc.process(arrived("B1", seq = 1))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 2, laneId = "L1"))
        // L1 has B1 but is blocked; ReleaseTick should not release from L1
        val result = proc.process(tick(3))
        // If L2 is empty there is nothing to release; Idle is expected here
        assertThat(result).isEqualTo(ProcessResult.Idle)
        // B1 must still be in L1 (not released)
        assertThat(proc.snapshot().laneOccupants["L1"]).containsExactly("B1")
    }

    @Test
    fun `arrivals when ALL lanes blocked are deferred, no crash`() {
        val proc = LivePbsProcessor(lanes(2))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 1, laneId = "L1"))
        val result = proc.process(arrived("B1", seq = 2))
        assertThat(result).isInstanceOf(ProcessResult.Deferred::class.java)
        assertThat(proc.snapshot().buffered).isEqualTo(1)
    }

    @Test
    fun `unblock drains buffered bodies into freed lane`() {
        val proc = LivePbsProcessor(lanes(2))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 1, laneId = "L1"))
        proc.process(arrived("B1", seq = 2))  // deferred
        assertThat(proc.snapshot().buffered).isEqualTo(1)

        proc.process(LaneUnblocked(eventId = "ublk-1", seq = 3, laneId = "L1"))
        val snap = proc.snapshot()
        assertThat(snap.buffered).isEqualTo(0)
        assertThat(snap.laneOccupants["L1"]).containsExactly("B1")
    }

    // ------------------------------------------------------------------
    // Rush order
    // ------------------------------------------------------------------

    @Test
    fun `rush order (dueDateSeq=0) is released promptly by overdue override`() {
        val proc = LivePbsProcessor(lanes(5))
        // Normal body first (high due-date), then rush order
        proc.process(arrived("NORMAL", seq = 1, color = "RED",  dueDateSeq = 100))
        proc.process(arrived("RUSH",   seq = 2, color = "BLUE", dueDateSeq = 0))
        val releaseResult = proc.process(tick(3)) as ProcessResult.Released
        // Rush body has dueDateSeq=0 and assemblyOutSize=0, so 0<=0 → overdue override
        assertThat(releaseResult.body.id).isEqualTo("RUSH")
    }

    // ------------------------------------------------------------------
    // Malformed / rejected events
    // ------------------------------------------------------------------

    @Test
    fun `blank color rejected, counter increments, state unchanged`() {
        val proc = LivePbsProcessor(lanes(3))
        val result = proc.process(arrived("B1", seq = 1, color = ""))
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat(proc.snapshot().rejected).isEqualTo(1)
        assertThat(proc.snapshot().laneOccupants.values.flatten()).isEmpty()
    }

    @Test
    fun `negative dueDateSeq rejected`() {
        val proc = LivePbsProcessor(lanes(3))
        val event = BodyArrived(eventId = "bad-1", seq = 1, bodyId = "BX",
                                color = "RED", model = "M1", dueDateSeq = -1)
        val result = proc.process(event)
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat(proc.snapshot().rejected).isEqualTo(1)
    }

    @Test
    fun `blank bodyId rejected`() {
        val proc = LivePbsProcessor(lanes(3))
        val event = BodyArrived(eventId = "bad-2", seq = 1, bodyId = "  ",
                                color = "RED", model = "M1", dueDateSeq = 0)
        val result = proc.process(event)
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
    }

    // ------------------------------------------------------------------
    // Out-of-order
    // ------------------------------------------------------------------

    @Test
    fun `stale seq increments outOfOrder counter`() {
        val proc = LivePbsProcessor(lanes(3))
        proc.process(arrived("B1", seq = 10))
        proc.process(arrived("B2", seq = 5))  // stale
        assertThat(proc.snapshot().outOfOrder).isEqualTo(1)
    }

    @Test
    fun `stale seq event is still processed (process-anyway policy)`() {
        val proc = LivePbsProcessor(lanes(3))
        proc.process(arrived("B1", seq = 10))
        proc.process(arrived("B2", seq = 5))  // stale but processed
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants.values.flatten()).containsExactlyInAnyOrder("B1", "B2")
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun `same event sequence produces identical final snapshots`() {
        val events = listOf(
            arrived("B1", seq = 1, color = "RED",    dueDateSeq = 0),
            arrived("B2", seq = 2, color = "BLUE",   dueDateSeq = 1),
            arrived("B3", seq = 3, color = "RED",    dueDateSeq = 2),
            tick(4),
            tick(5),
        )
        val proc1 = LivePbsProcessor(lanes(5))
        events.forEach { proc1.process(it) }

        val proc2 = LivePbsProcessor(lanes(5))
        events.forEach { proc2.process(it) }

        assertThat(proc1.snapshot()).isEqualTo(proc2.snapshot())
    }
}
```

- [ ] **Step 2: Run tests — expect COMPILE FAILURE (classes don't exist yet)**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test 2>&1 | tail -30`

- [ ] **Step 3: Create the source files (implement event model, result types, snapshot, processor)**

Files to create in order:
1. `control/src/main/kotlin/com/resequencetwin/control/stream/PbsEvent.kt` — sealed event hierarchy (from Task 1 above)
2. `control/src/main/kotlin/com/resequencetwin/control/stream/ProcessResult.kt` — sealed result hierarchy (from Task 2)
3. `control/src/main/kotlin/com/resequencetwin/control/stream/LiveSnapshot.kt` — data class (from Task 3)
4. `control/src/main/kotlin/com/resequencetwin/control/stream/LivePbsProcessor.kt` — state machine (from Task 4 FINAL version)

- [ ] **Step 4: Run tests until GREEN**

Run: `cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin" && mvn -f control/pom.xml -q test`
Expected: All previous 119 tests + new stream tests pass, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] resequence-twin"
git add control/src/main/kotlin/com/resequencetwin/control/stream/
git add control/src/test/kotlin/com/resequencetwin/control/stream/
git commit -m "feat(stream-1): live event-driven PBS state machine (idempotent, fault-tolerant)"
```

---

## S2 Notes (Kafka Listener Integration)

When the Kafka listener is added (S2), it needs to:

1. **Deserialize JSON → PbsEvent**: The event hierarchy uses Kotlin data classes with default `@JsonProperty` support. Add a Jackson `@JsonTypeInfo`/`@JsonSubTypes` annotation pair (or a custom deserializer) since the type discriminator field (e.g. `"type": "BodyArrived"`) must select the correct subtype.

2. **Call**: `processor.process(event)` — single call, returns `ProcessResult`.

3. **Threading**: One `LivePbsProcessor` per Kafka partition. The `@KafkaListener(topicPartitions = ...)` assignment maps 1:1 with processor instances. Do NOT share one processor across partitions without external synchronization.

4. **Replay safety**: When Kafka consumer is reset to offset 0 (for a full replay), no additional logic is needed — the processor's `processedIds` set guarantees idempotency within a session. For cross-session replay durability, persist `processedIds` to Redis or a state store (out of scope for this PoC).

5. **Event JSON shape** (example for `BodyArrived`):
   ```json
   {
     "type": "BodyArrived",
     "eventId": "550e8400-e29b-41d4-a716-446655440000",
     "seq": 42,
     "bodyId": "BODY-00042",
     "color": "SILVER",
     "model": "IONIQ6",
     "options": ["SUNROOF", "HUD"],
     "dueDateSeq": 38
   }
   ```
