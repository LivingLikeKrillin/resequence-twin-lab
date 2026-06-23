package com.resequencetwin.control.bench

import com.fasterxml.jackson.annotation.JsonProperty
import com.resequencetwin.control.pbs.Body
import kotlin.math.abs

/**
 * Accumulates PBS-native KPI measurements across a simulation run.
 *
 * Five automotive-authentic KPIs (matching Ford Saarlouis reference metrics):
 * 1. **colorChanges** — count of consecutive-release color switches in assemblyOut.
 *    Lower = better (paint-batch cohesion preserved through PBS buffer).
 * 2. **avgBatchLength** — mean run length of same-color consecutive releases.
 *    Higher = better (longer color runs reduce paint-shop setup cost recurrence).
 * 3. **dueDateDeviation** — mean |releasePosition − dueDateSeq| across all released
 *    bodies. Lower = better (JIS sequence preserved through PBS buffer).
 * 4. **throughput** — released bodies / simulation steps. Higher = better.
 * 5. **laneUtilization** — mean (occupancy / capacity) fraction across lanes
 *    sampled each step. Higher = better utilization of buffer capacity.
 *
 * **Honesty:** only relative deltas (dynamic vs static) are externally published
 * (spec §4/§9). Ford Saarlouis numbers are a published reference, NOT this PoC's output.
 *
 * Determinism: no `Date.now()` or `Math.random()` usage.
 */
class PbsKpiCollector {

    /** Ordered list of released bodies (assemblyOut sequence). */
    private val releasedSequence: MutableList<Body> = ArrayList()

    /** Number of simulation steps elapsed. */
    private var steps: Int = 0

    /** Running sum of (occupancy / capacity) per step across all lanes. */
    private var utilizationSum: Double = 0.0

    /** Number of (lane × step) utilization samples recorded. */
    private var utilizationSamples: Int = 0

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Record a body released to assembly this step.
     *
     * @param body the released body (in release order)
     */
    fun recordRelease(body: Body) {
        releasedSequence.add(body)
    }

    /**
     * Record that one simulation step has elapsed.
     */
    fun recordStep() {
        steps++
    }

    /**
     * Record a utilization sample for one lane at the current step.
     *
     * @param occupancy current number of bodies in the lane
     * @param capacity  lane's maximum capacity
     */
    fun recordLaneUtilization(occupancy: Int, capacity: Int) {
        if (capacity > 0) {
            utilizationSum += occupancy.toDouble() / capacity
            utilizationSamples++
        }
    }

    // -------------------------------------------------------------------------
    // KPI computations
    // -------------------------------------------------------------------------

    /**
     * Count of consecutive-release color switches in assemblyOut.
     *
     * A color change is counted whenever two consecutive released bodies have
     * different colors. With N releases there are at most N−1 transitions.
     *
     * @return number of color changes (lower = better)
     */
    fun colorChanges(): Int =
        releasedSequence.zipWithNext().count { (a, b) -> a.color != b.color }

    /**
     * Mean run length of same-color consecutive releases.
     *
     * Computed as `totalReleases / (colorChanges + 1)`.
     * A perfectly batched stream with all same color gives `totalReleases / 1`.
     *
     * @return mean batch length (higher = better)
     */
    fun avgBatchLength(): Double {
        if (releasedSequence.isEmpty()) return 0.0
        val runs = colorChanges() + 1 // number of color runs = transitions + 1
        return releasedSequence.size.toDouble() / runs
    }

    /**
     * Mean |releasePosition − dueDateSeq| across all released bodies.
     *
     * Release position is 0-indexed: first body released = position 0.
     * `dueDateSeq` is the JIS target assembly sequence number.
     * Lower deviation = JIS order better preserved through the PBS buffer.
     *
     * @return mean due-date deviation (lower = better)
     */
    fun dueDateDeviation(): Double {
        if (releasedSequence.isEmpty()) return 0.0
        return releasedSequence.indices.sumOf { i -> abs(i - releasedSequence[i].dueDateSeq).toDouble() } / releasedSequence.size
    }

    /**
     * Released bodies / simulation steps.
     *
     * @return throughput (higher = better)
     */
    fun throughput(): Double =
        if (steps == 0) 0.0 else releasedSequence.size.toDouble() / steps

    /**
     * Mean lane utilization fraction across all recorded lane snapshots.
     *
     * @return utilization fraction in [0, 1] (higher = better buffer utilization)
     */
    fun laneUtilization(): Double =
        if (utilizationSamples == 0) 0.0 else utilizationSum / utilizationSamples

    /** Total number of bodies released so far. */
    fun releasedCount(): Int = releasedSequence.size

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of all five PBS KPIs at this point in the run.
     *
     * @param colorChanges      count of consecutive color switches
     * @param avgBatchLength    mean same-color run length
     * @param dueDateDeviation  mean |releasePos − dueDateSeq|
     * @param throughput        released / steps
     * @param laneUtilization   mean lane occupancy fraction
     */
    data class Snapshot(
        @field:JsonProperty("colorChanges")   val colorChanges: Int,
        @field:JsonProperty("avgBatchLength") val avgBatchLength: Double,
        @field:JsonProperty("dueDateDeviation") val dueDateDeviation: Double,
        @field:JsonProperty("throughput")     val throughput: Double,
        @field:JsonProperty("laneUtilization") val laneUtilization: Double
    )

    /** Take a snapshot of all current KPIs. */
    fun snapshot(): Snapshot = Snapshot(
        colorChanges(),
        avgBatchLength(),
        dueDateDeviation(),
        throughput(),
        laneUtilization()
    )
}
