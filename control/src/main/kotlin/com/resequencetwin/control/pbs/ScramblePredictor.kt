package com.resequencetwin.control.pbs

import java.util.Locale
import kotlin.math.sqrt

/**
 * Forecasts PBS scramble risk and identifies the dominant contributing factor.
 *
 * Risk score is a weighted sum of three normalized signals:
 * - Color fragmentation ([W_FRAGMENTATION]): distinct front colors / non-empty lanes
 * - Overdue ratio ([W_OVERDUE]): fronts past their JIS due date / non-empty lanes
 * - Lane imbalance ([W_IMBALANCE]): coefficient-of-variation of lane occupancies
 *
 * The top contributor drives the stabilization hint text in [ScrambleForecast].
 */
class ScramblePredictor {

    companion object {
        const val COLOR_FRAGMENTATION: String = "COLOR_FRAGMENTATION"
        const val OVERDUE_RATIO: String = "OVERDUE_RATIO"
        const val LANE_IMBALANCE: String = "LANE_IMBALANCE"
        const val W_FRAGMENTATION: Double = 0.45
        const val W_OVERDUE: Double = 0.35
        const val W_IMBALANCE: Double = 0.20

        private fun colorFragmentation(fronts: List<Body>, nonEmptyLanes: Int): Double {
            if (nonEmptyLanes == 0) return 0.0
            val distinctColors = fronts.map { it.color }.toSet()
            return distinctColors.size.toDouble() / nonEmptyLanes
        }

        private fun overdueRatio(fronts: List<Body>, nonEmptyLanes: Int, assemblyOutSize: Int): Double {
            if (nonEmptyLanes == 0) return 0.0
            val overdue = fronts.count { it.dueDateSeq <= assemblyOutSize }
            return overdue.toDouble() / nonEmptyLanes
        }

        private fun laneImbalance(occupancies: List<Int>): Double {
            if (occupancies.isEmpty()) return 0.0
            val mean = occupancies.sum().toDouble() / occupancies.size
            if (mean == 0.0) return 0.0
            val variance = occupancies.sumOf { o -> val d = o - mean; d * d } / occupancies.size
            val cv = sqrt(variance) / mean
            return minOf(1.0, maxOf(0.0, cv))
        }

        private fun topContributor(factors: List<ContributingFactor>): ContributingFactor =
            factors.maxBy { it.weightedContribution }

        private fun distinctFrontColors(state: PbsState): Int =
            state.laneIds().mapNotNull { state.lane(it)?.front()?.color }.toSet().size

        private fun lanesWithFront(state: PbsState): Int {
            return state.laneIds().count { laneId ->
                state.lane(laneId)!!.front() != null
            }
        }

        private fun earliestOverdueFront(state: PbsState, assemblyOutSize: Int): Body? {
            var earliest: Body? = null
            for (laneId in state.laneIds()) {
                val front = state.lane(laneId)!!.front() ?: continue
                if (front.dueDateSeq <= assemblyOutSize &&
                    (earliest == null || front.dueDateSeq < earliest.dueDateSeq)) {
                    earliest = front
                }
            }
            return earliest
        }
    }

    fun forecast(state: PbsState, policy: DynamicSequencingPolicy): ScrambleForecast {
        val laneIds = state.laneIds()
        val assemblyOutSize = state.assemblyOut().size
        val fronts = mutableListOf<Body>()
        val occupancies = mutableListOf<Int>()
        for (laneId in laneIds) {
            val lane = state.lane(laneId)!!
            occupancies.add(lane.size())
            val front = lane.front()
            if (front != null) fronts.add(front)
        }
        val nonEmptyLanes = fronts.size
        val colorFragmentation = colorFragmentation(fronts, nonEmptyLanes)
        val overdueRatio = overdueRatio(fronts, nonEmptyLanes, assemblyOutSize)
        val laneImbalance = laneImbalance(occupancies)
        val earliestOverdue = earliestOverdueFront(state, assemblyOutSize)
        val fragFactor = ContributingFactor(
            COLOR_FRAGMENTATION, colorFragmentation, W_FRAGMENTATION * colorFragmentation
        )
        val overdueFactor = ContributingFactor(
            OVERDUE_RATIO, overdueRatio, W_OVERDUE * overdueRatio,
            earliestOverdue?.currentLane,
            earliestOverdue?.id
        )
        val imbalanceFactor = ContributingFactor(
            LANE_IMBALANCE, laneImbalance, W_IMBALANCE * laneImbalance
        )
        val factors = listOf(fragFactor, overdueFactor, imbalanceFactor)
        val riskScore = fragFactor.weightedContribution + overdueFactor.weightedContribution + imbalanceFactor.weightedContribution
        val topContributor = topContributor(factors)
        val hint = stabilizationHint(topContributor, state, earliestOverdue)
        return ScrambleForecast(riskScore, ScrambleLevel.fromRiskScore(riskScore), factors, topContributor, hint)
    }

    private fun stabilizationHint(top: ContributingFactor, state: PbsState, earliestOverdue: Body?): String {
        return when (top.name) {
            COLOR_FRAGMENTATION -> {
                val distinctColors = distinctFrontColors(state)
                val lanesWithFront = lanesWithFront(state)
                String.format(
                    Locale.ROOT,
                    "Buffer fronts span %d distinct colors across %d lanes — " +
                        "consider color-affinity assignment to rebuild a single-color run",
                    distinctColors, lanesWithFront
                )
            }
            OVERDUE_RATIO -> {
                if (earliestOverdue == null)
                    throw AssertionError("invariant: OVERDUE_RATIO dominant implies at least one overdue front")
                String.format(
                    Locale.ROOT,
                    "Lane %s front is overdue (dueDateSeq=%d) — release it next to protect JIS order",
                    earliestOverdue.currentLane, earliestOverdue.dueDateSeq
                )
            }
            LANE_IMBALANCE -> {
                String.format(
                    Locale.ROOT,
                    "Lane occupancies are imbalanced (CV=%.2f) — " +
                        "route incoming bodies to least-loaded lanes to restore batching headroom",
                    top.value
                )
            }
            else -> "No dominant scramble driver — buffer is currently well-balanced"
        }
    }
}
