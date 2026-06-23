package com.resequencetwin.control.drift

/**
 * The twin's expected lane topology baseline, captured once from the live config source at startup.
 *
 * Real-deployment note (documented, NOT built): in a production AAS deployment this would map to an
 * Asset Administration Shell submodel describing nominal station/lane configuration.
 *
 * @property lanes laneId -> capacity
 * @property expectedBlocked lane ids expected to be blocked in the nominal baseline (usually empty)
 */
data class ExpectedConfig(
    val lanes: Map<String, Int>,
    val expectedBlocked: Set<String>,
)

/**
 * What the live config source currently reports. Same structural shape as [ExpectedConfig]; the
 * blocked set is named [blocked] (not "expected") because it is an *observation*, not a nominal value.
 *
 * @property lanes laneId -> capacity as currently reported
 * @property blocked lane ids currently reported blocked
 */
data class ObservedConfig(
    val lanes: Map<String, Int>,
    val blocked: Set<String>,
)

/**
 * Observed KPI from the live telemetry source. An **exact mirror** of the comparable cumulative
 * fields on [com.resequencetwin.control.stream.LiveSnapshot] (`releases`, `colourChanges`, `assemblyOutSize`)
 * so the twin-predicted and observed sides are dimensionally identical. There is intentionally **no
 * throughput field** — a rate is derived inside [ResidualDriftDetector] as a per-tick delta.
 *
 * All three are cumulative, monotonically non-decreasing counts.
 */
data class ObservedKpi(
    val releases: Int,
    val colourChanges: Int,
    val assemblyOut: Int,
)

/** Kinds of structural config drift the [ConfigDriftDetector] can report. Open for future kinds. */
enum class DriftKind {
    /** A lane present in both baseline and observed has a different capacity. */
    CAPACITY_CHANGED,
    /** A lane is blocked in the observed config but not in the baseline's expectedBlocked set. */
    UNEXPECTED_BLOCK,
    /** A lane present in the baseline is absent from the observed config (e.g. station disabled). */
    EXPECTED_LANE_MISSING,
}

/**
 * The advisory twin-side change that would re-align the twin to observed reality.
 * **Advisory only — never auto-applied** (no write-back). [action] is a machine tag, [description]
 * is human-readable.
 */
data class ReconciliationProposal(
    val action: String,
    val description: String,
)

/**
 * One structural config-drift finding. [expected] / [observed] are rendered as strings for a uniform
 * advisory surface regardless of the underlying type (capacity int vs blocked flag).
 */
data class ConfigDriftFinding(
    val laneId: String,
    val kind: DriftKind,
    val expected: String,
    val observed: String,
    val proposal: ReconciliationProposal,
)

/**
 * One behavioral-drift finding for a single metric on a single tick.
 *
 * @property predicted current twin-predicted cumulative value
 * @property observed current observed cumulative value
 * @property residual (predictedDelta - observedDelta) for this tick
 * @property ewma exponentially-weighted moving average of the residual
 * @property breached true when |ewma| exceeds the configured threshold
 */
data class BehavioralDriftFinding(
    val metric: String,
    val predicted: Double,
    val observed: Double,
    val residual: Double,
    val ewma: Double,
    val breached: Boolean,
)
