package com.resequencetwin.control.pbs

/**
 * Color-affinity, multi-objective sequencing policy for the PBS buffer.
 *
 * **Lane assignment**: routes incoming bodies to the non-full lane that already
 * contains bodies of the same color (color-affinity), falling back to the
 * least-loaded non-full lane.
 *
 * **Release selection**: releases the front body of the lane with the earliest
 * overdue body first; otherwise selects by weighted score combining color
 * continuity ([W_COLOR]), option leveling ([W_OPTION]), and due-date urgency ([W_DUE]).
 */
class DynamicSequencingPolicy : SequencingPolicy {

    companion object {
        const val W_COLOR: Double = 4.0
        const val W_OPTION: Double = 2.0
        const val W_DUE: Double = 3.0
        const val LEVELING_WINDOW: Int = 4
        const val DUE_DATE_URGENCY_WINDOW: Int = 20
    }

    private var lastReleasedColor: String? = null
    private val recentHadOptions = BooleanArray(LEVELING_WINDOW)
    private var releaseCount = 0

    override fun assignLane(body: Body, state: PbsState): String =
        assignLane(body, state, state.laneIds().toSet())

    /**
     * Assigns [body] to an eligible, non-full lane within [eligibleLaneIds].
     * Lanes present in [state] but absent from [eligibleLaneIds] are skipped.
     *
     * @throws IllegalStateException if all eligible lanes are full or no eligible lane exists.
     */
    fun assignLane(body: Body, state: PbsState, eligibleLaneIds: Set<String>): String {
        val laneIds = state.laneIds()
        if (laneIds.isEmpty()) throw IllegalStateException("PBS state has no lanes")
        var colorMatchLane: String? = null
        var colorMatchLoad = Int.MAX_VALUE
        var leastLoadedLane: String? = null
        var leastLoad = Int.MAX_VALUE
        for (laneId in laneIds) {
            if (laneId !in eligibleLaneIds) continue
            val lane = state.lane(laneId)!!
            if (lane.isFull()) continue
            if (lane.size() < leastLoad) {
                leastLoad = lane.size()
                leastLoadedLane = laneId
            }
            val hasColor = lane.occupants().any { b -> b.color == body.color }
            if (hasColor && lane.size() < colorMatchLoad) {
                colorMatchLoad = lane.size()
                colorMatchLane = laneId
            }
        }
        val chosen = colorMatchLane ?: leastLoadedLane
            ?: throw IllegalStateException("All lanes full — cannot assign body ${body.id}")
        return chosen
    }

    override fun selectRelease(state: PbsState): Body =
        selectRelease(state, state.laneIds().toSet())

    /**
     * Selects and releases the front body from the highest-priority eligible lane
     * within [eligibleLaneIds]. Updates internal colour/option/due-date tracking via [doRelease].
     * Lanes present in [state] but absent from [eligibleLaneIds] are not considered.
     *
     * @throws IllegalStateException if all eligible lanes are empty.
     */
    fun selectRelease(state: PbsState, eligibleLaneIds: Set<String>): Body {
        val laneIds = state.laneIds()
        val assemblyOutSize = state.assemblyOut().size
        var overdueLane: String? = null
        var earliestOverdue = Int.MAX_VALUE
        for (laneId in laneIds) {
            if (laneId !in eligibleLaneIds) continue
            val lane = state.lane(laneId)!!
            val front = lane.front() ?: continue
            if (front.dueDateSeq <= assemblyOutSize && front.dueDateSeq < earliestOverdue) {
                earliestOverdue = front.dueDateSeq
                overdueLane = laneId
            }
        }
        if (overdueLane != null) return doRelease(state, overdueLane)
        var bestLane: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (laneId in laneIds) {
            if (laneId !in eligibleLaneIds) continue
            val lane = state.lane(laneId)!!
            val front = lane.front() ?: continue
            val s = score(front, laneId, state)
            if (s > bestScore) {
                bestScore = s
                bestLane = laneId
            }
        }
        if (bestLane == null) throw IllegalStateException("All lanes empty — nothing to release")
        return doRelease(state, bestLane)
    }

    internal fun score(body: Body, laneId: String, state: PbsState): Double {
        val c = scoreComponents(body, state)
        return W_COLOR * c.colorBonus + W_OPTION * c.optionLevelingBonus + W_DUE * c.dueDateBonus
    }

    private fun scoreComponents(body: Body, state: PbsState): ScoreComponents {
        val assemblyOutSize = state.assemblyOut().size
        val colorBonus = if (lastReleasedColor != null && lastReleasedColor == body.color) 1.0 else 0.0
        var optionCount = 0
        val windowSize = minOf(releaseCount, LEVELING_WINDOW)
        for (i in 0 until windowSize) {
            if (recentHadOptions[i]) optionCount++
        }
        val bodyHasOptions = body.options.isNotEmpty()
        val optionLevelingBonus: Double = if (bodyHasOptions) {
            1.0 - (optionCount.toDouble() / maxOf(1, windowSize))
        } else {
            1.0
        }
        val lag = body.dueDateSeq - assemblyOutSize
        val dueDateBonus = when {
            lag <= 0 -> 1.0
            lag <= DUE_DATE_URGENCY_WINDOW -> 1.0 - lag.toDouble() / DUE_DATE_URGENCY_WINDOW
            else -> 0.0
        }
        return ScoreComponents(colorBonus, optionLevelingBonus, dueDateBonus)
    }

    private data class ScoreComponents(
        val colorBonus: Double,
        val optionLevelingBonus: Double,
        val dueDateBonus: Double
    )

    fun explainRelease(state: PbsState): ReleaseExplanation {
        val laneIds = state.laneIds()
        val assemblyOutSize = state.assemblyOut().size
        val candidates = mutableListOf<CandidateScore>()
        var overdueLane: String? = null
        var overdueBodyId: String? = null
        var earliestOverdue = Int.MAX_VALUE
        var bestLane: String? = null
        var bestBodyId: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (laneId in laneIds) {
            val lane = state.lane(laneId)!!
            val front = lane.front() ?: continue
            val c = scoreComponents(front, state)
            val weighted = W_COLOR * c.colorBonus + W_OPTION * c.optionLevelingBonus + W_DUE * c.dueDateBonus
            val overdue = front.dueDateSeq <= assemblyOutSize
            candidates.add(CandidateScore(
                laneId, front.id, front.color, front.options.isNotEmpty(),
                c.colorBonus, c.optionLevelingBonus, c.dueDateBonus, weighted, overdue
            ))
            if (overdue && front.dueDateSeq < earliestOverdue) {
                earliestOverdue = front.dueDateSeq
                overdueLane = laneId
                overdueBodyId = front.id
            }
            if (weighted > bestScore) {
                bestScore = weighted
                bestLane = laneId
                bestBodyId = front.id
            }
        }
        if (bestLane == null) throw IllegalStateException("All lanes empty — nothing to release")
        val immutableCandidates = candidates.toList() // defensive copy (mirrors Java List.copyOf)
        if (overdueLane != null)
            return ReleaseExplanation(overdueLane, overdueBodyId!!, ReleaseReason.OVERDUE_OVERRIDE, immutableCandidates)
        return ReleaseExplanation(bestLane, bestBodyId!!, ReleaseReason.HIGHEST_SCORE, immutableCandidates)
    }

    private fun doRelease(state: PbsState, laneId: String): Body {
        val released = state.releaseFromLane(laneId)
        lastReleasedColor = released.color
        recentHadOptions[releaseCount % LEVELING_WINDOW] = released.options.isNotEmpty()
        releaseCount++
        return released
    }
}
