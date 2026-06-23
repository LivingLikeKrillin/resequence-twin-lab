package com.resequencetwin.control.drift

import kotlin.math.abs

/**
 * Stateful-but-deterministic behavioral-drift detector for a **single** metric. One instance per
 * compared metric (the twin-predicted vs observed cumulative value of e.g. releases).
 *
 * Each [update] receives the *current cumulative* predicted & observed values. The detector tracks
 * the previous values to compute the per-tick **delta (rate)** of each side, then the residual
 * between the two deltas, so a cumulative-vs-cumulative comparison is always dimensionally consistent.
 * It maintains an EWMA of the residual and flags [BehavioralDriftFinding.breached] when |EWMA|
 * exceeds [threshold].
 *
 * Not thread-safe; called only from the single [DriftMonitor] scheduler thread.
 *
 * @param alpha EWMA smoothing factor in (0,1]; higher = more reactive.
 * @param threshold absolute EWMA breach limit (single value per this MVP; per-metric tuning is future work).
 */
class ResidualDriftDetector(
    private val metric: String,
    private val alpha: Double,
    private val threshold: Double,
) {
    private var prevPredicted: Double? = null
    private var prevObserved: Double? = null
    private var ewma: Double = 0.0

    fun update(predicted: Double, observed: Double): BehavioralDriftFinding {
        val prevP = prevPredicted
        val prevO = prevObserved
        prevPredicted = predicted
        prevObserved = observed

        if (prevP == null || prevO == null) {
            // First observation: no delta computable yet. Seed and report no drift.
            ewma = 0.0
            return BehavioralDriftFinding(metric, predicted, observed, residual = 0.0, ewma = 0.0, breached = false)
        }

        val residual = (predicted - prevP) - (observed - prevO)
        ewma = alpha * residual + (1 - alpha) * ewma
        return BehavioralDriftFinding(
            metric = metric, predicted = predicted, observed = observed,
            residual = residual, ewma = ewma, breached = abs(ewma) > threshold,
        )
    }
}
