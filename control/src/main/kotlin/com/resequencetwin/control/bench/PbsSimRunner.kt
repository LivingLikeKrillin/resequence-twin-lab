package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.Body
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.pbs.PbsState
import com.resequencetwin.control.pbs.SequencingPolicy

/**
 * Policy-agnostic PBS simulation runner (rev3 R3).
 *
 * Drives a seeded body stream through a [SequencingPolicy] over a [PbsState],
 * collecting PBS-native KPIs via [PbsKpiCollector].
 *
 * **Simulation loop**
 * Per step:
 * 1. **Arrivals phase**: assign ALL pending arrivals that fit in available lane
 *    capacity (greedy fill: keep assigning until lanes are full or no arrivals remain).
 * 2. **Release phase**: release one body per step via
 *    [SequencingPolicy.selectRelease] if any lane is non-empty.
 * 3. **Utilization snapshot**: record lane occupancy / capacity.
 * 4. **Step tick**: increment step counter.
 *
 * **Determinism**: No `Date.now()` or `Math.random()` usage.
 */
class PbsSimRunner(private val maxSteps: Int) {

    constructor() : this(DEFAULT_MAX_STEPS)

    init {
        require(maxSteps > 0) { "maxSteps must be > 0" }
    }

    companion object {
        /** Guard against infinite loops if a policy repeatedly cannot assign or release. */
        const val DEFAULT_MAX_STEPS: Int = 100_000

        /**
         * Arrivals phase: greedily fill all available lane capacity from the body stream.
         *
         * @param bodies     full body list
         * @param laneSpecs  lane specs (for free-slot check)
         * @param state      current PBS state (mutated)
         * @param policy     policy for lane assignment
         * @param arrivalIdx current index into bodies (next unassigned body)
         * @return updated arrivalIdx after greedy fill
         */
        private fun stepArrivals(
            bodies: List<Body>,
            laneSpecs: List<LaneSpec>,
            state: PbsState,
            policy: SequencingPolicy,
            arrivalIdx: Int
        ): Int {
            var idx = arrivalIdx
            var arrivedAny = true
            while (arrivedAny && idx < bodies.size) {
                arrivedAny = false
                val next = bodies[idx]
                val anyFree = laneSpecs.any { s ->
                    state.lane(s.id)?.let { !it.isFull() } ?: false
                }
                if (anyFree) {
                    val laneId = policy.assignLane(next, state)
                    state.assignToLane(next, laneId)
                    idx++
                    arrivedAny = true
                }
            }
            return idx
        }

        /**
         * Release phase: release one body via the policy if any lane is non-empty.
         *
         * @param laneSpecs lane specs (for non-empty check)
         * @param state     current PBS state (mutated)
         * @param policy    policy for release selection
         * @param kpi       KPI collector (mutated: recordRelease called if body released)
         */
        private fun stepRelease(
            laneSpecs: List<LaneSpec>,
            state: PbsState,
            policy: SequencingPolicy,
            kpi: PbsKpiCollector
        ): Body? {
            val anyBody = laneSpecs.any { s ->
                state.lane(s.id)?.let { !it.isEmpty() } ?: false
            }
            return if (anyBody) {
                val released = policy.selectRelease(state)
                kpi.recordRelease(released)
                released
            } else null
        }

        /**
         * Utilization snapshot: record per-lane occupancy/capacity for this step.
         *
         * @param laneSpecs lane specs (for capacity)
         * @param state     current PBS state (read-only)
         * @param kpi       KPI collector (mutated)
         */
        private fun stepUtilization(
            laneSpecs: List<LaneSpec>,
            state: PbsState,
            kpi: PbsKpiCollector
        ) {
            for (spec in laneSpecs) {
                val occ = state.lane(spec.id)?.size() ?: 0
                kpi.recordLaneUtilization(occ, spec.capacity)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Run
    // -------------------------------------------------------------------------

    /**
     * Run the full benchmark for one policy over the given body stream.
     *
     * A fresh [PbsState] is constructed from the provided lane specs.
     * The body stream is consumed in order.
     *
     * @param bodies     the seeded paint-stream (color-batched input)
     * @param laneSpecs  lane specifications: [laneId, capacity] pairs
     * @param policy     the policy to drive (fresh instance, not shared)
     * @return KPI snapshot after all bodies released
     */
    fun run(
        bodies: List<Body>,
        laneSpecs: List<LaneSpec>,
        policy: SequencingPolicy
    ): PbsKpiCollector.Snapshot {
        val lanes = laneSpecs.map { s -> Lane(s.id, s.capacity) }
        val state = PbsState.withLanes(lanes)
        val kpi = PbsKpiCollector()

        var arrivalIdx = 0
        var steps = 0

        while (kpi.releasedCount() < bodies.size && steps < maxSteps) {
            arrivalIdx = stepArrivals(bodies, laneSpecs, state, policy, arrivalIdx)
            stepRelease(laneSpecs, state, policy, kpi)
            stepUtilization(laneSpecs, state, kpi)
            kpi.recordStep()
            steps++
        }

        return kpi.snapshot()
    }

    // -------------------------------------------------------------------------
    // Checkpoint run (rev3 R4b)
    // -------------------------------------------------------------------------

    /**
     * Drive the simulation loop up to [maxReleases] releases, then stop and return
     * the live mid-run state for advisory analysis (explain/predict endpoints).
     *
     * The loop order is *identical* to [run] — `stepArrivals → stepRelease →
     * stepUtilization → tick` — so the checkpoint state is guaranteed by
     * construction to be exactly what [run] would have produced after the same number
     * of releases for the same inputs.
     *
     * When `maxReleases == 0` the loop body never executes: no arrivals, no releases,
     * all lanes empty — consistent with `run()`'s state before its first iteration.
     *
     * Stops early (gracefully) if the body stream is exhausted before `maxReleases`.
     *
     * @param bodies       the seeded paint-stream (color-batched input)
     * @param laneSpecs    lane specifications: [laneId, capacity] pairs
     * @param policy       the dynamic policy instance to drive
     * @param maxReleases  maximum number of releases to perform before stopping; must be >= 0
     * @return a [Checkpoint] holding the live [PbsState], the policy instance, and the actual releases done
     * @throws IllegalArgumentException if [maxReleases] is negative
     */
    fun runToCheckpoint(
        bodies: List<Body>,
        laneSpecs: List<LaneSpec>,
        policy: DynamicSequencingPolicy,
        maxReleases: Int
    ): Checkpoint {
        require(maxReleases >= 0) { "maxReleases must be >= 0" }

        val lanes = laneSpecs.map { s -> Lane(s.id, s.capacity) }
        val state = PbsState.withLanes(lanes)
        val kpi = PbsKpiCollector()

        var arrivalIdx = 0
        var steps = 0

        // Loop order is IDENTICAL to run(): arrivals → release → utilization → tick.
        // The only addition is the `kpi.releasedCount() < maxReleases` cap.
        while (kpi.releasedCount() < maxReleases
               && kpi.releasedCount() < bodies.size
               && steps < maxSteps) {
            arrivalIdx = stepArrivals(bodies, laneSpecs, state, policy, arrivalIdx)
            stepRelease(laneSpecs, state, policy, kpi)
            stepUtilization(laneSpecs, state, kpi)
            kpi.recordStep()
            steps++
        }

        return Checkpoint(state, policy, kpi.releasedCount())
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Immutable checkpoint produced by [runToCheckpoint].
     *
     * @param state        live PBS buffer state at the checkpoint moment
     * @param policy       the [DynamicSequencingPolicy] instance that drove the run
     * @param releasesDone actual number of releases performed
     */
    data class Checkpoint(
        val state: PbsState,
        val policy: DynamicSequencingPolicy,
        val releasesDone: Int
    )

    /**
     * Lane id + capacity pair used to configure the simulation.
     *
     * @param id       lane identifier (must be unique)
     * @param capacity FIFO depth (max concurrent bodies)
     */
    data class LaneSpec(
        val id: String,
        val capacity: Int
    ) {
        init {
            require(id.isNotBlank()) { "LaneSpec id must not be blank" }
            require(capacity > 0) { "LaneSpec capacity must be > 0" }
        }
    }

    // -------------------------------------------------------------------------
    // Trace run (viz-1)
    // -------------------------------------------------------------------------

    /**
     * Drive the same step loop as [run] but record a [Frame] after each step,
     * returning a [Trace] of the full simulation.
     *
     * Loop order is IDENTICAL to [run]: stepArrivals → stepRelease →
     * stepUtilization → tick.  The private step helpers are reused so this
     * method cannot drift from [run].
     *
     * @param bodies     the seeded paint-stream
     * @param laneSpecs  lane specifications
     * @param policy     the policy to drive
     * @return [Trace] holding all frames (one per simulation step) and the lane specs
     */
    fun runWithTrace(
        bodies: List<Body>,
        laneSpecs: List<LaneSpec>,
        policy: SequencingPolicy
    ): Trace {
        val lanes = laneSpecs.map { s -> Lane(s.id, s.capacity) }
        val state = PbsState.withLanes(lanes)
        val kpi = PbsKpiCollector()

        var arrivalIdx = 0
        var steps = 0
        val frames = mutableListOf<Frame>()

        while (kpi.releasedCount() < bodies.size && steps < maxSteps) {
            // Snapshot occupant ids before arrivals phase (ordered list for set-subtraction)
            val beforeArrivalsIds = laneSpecs.flatMap { s ->
                state.lane(s.id)?.occupants()?.map { it.id } ?: emptyList()
            }.toSet()

            arrivalIdx = stepArrivals(bodies, laneSpecs, state, policy, arrivalIdx)

            // Arrived = new ids in any lane that were not there before arrivals
            val afterArrivalsIds = laneSpecs.flatMap { s ->
                state.lane(s.id)?.occupants()?.map { it.id } ?: emptyList()
            }
            val arrivedIds = afterArrivalsIds.filter { it !in beforeArrivalsIds }

            val releasedBody: Body? = stepRelease(laneSpecs, state, policy, kpi)
            val releasedId: String? = releasedBody?.id

            stepUtilization(laneSpecs, state, kpi)
            kpi.recordStep()

            // Build lane-occupants map (ordered front-first) after release
            val laneOccupants = laneSpecs.associate { s ->
                s.id to (state.lane(s.id)?.occupants()?.map { it.id } ?: emptyList())
            }

            frames += Frame(
                step = steps,
                lanes = laneOccupants,
                released = releasedId,
                arrived = arrivedIds
            )
            steps++
        }

        return Trace(frames = frames.toList(), laneSpecs = laneSpecs)
    }

    // -------------------------------------------------------------------------
    // Trace nested types
    // -------------------------------------------------------------------------

    /**
     * A per-step snapshot of the PBS simulation state.
     *
     * @param step     0-indexed step counter
     * @param lanes    map of laneId → front-first list of occupant body ids after this step
     * @param released body id released to assembly this step; null if no release occurred
     * @param arrived  body ids assigned to lanes during arrivals phase this step
     */
    data class Frame(
        val step: Int,
        val lanes: Map<String, List<String>>,
        val released: String?,
        val arrived: List<String>
    )

    /**
     * Full simulation trace returned by [runWithTrace].
     *
     * @param frames    ordered list of per-step frames (index == frame.step)
     * @param laneSpecs lane specifications used for this run
     */
    data class Trace(
        val frames: List<Frame>,
        val laneSpecs: List<LaneSpec>
    )
}
