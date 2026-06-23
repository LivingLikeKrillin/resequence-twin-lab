package com.resequencetwin.control.pbs

/**
 * Coarse risk band for a [ScrambleForecast] (rev3 R4a).
 *
 * Bands partition the continuous `riskScore` in [0,1] into three operator-facing levels:
 * - [LOW]    — `riskScore < 0.35`
 * - [MEDIUM] — `0.35 <= riskScore < 0.65`
 * - [HIGH]   — `riskScore >= 0.65`
 *
 * The thresholds are transparent heuristic cut-points, not learned. Use
 * [fromRiskScore] to classify a score consistently.
 */
enum class ScrambleLevel {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        /** Lower bound (inclusive) of the MEDIUM band. */
        const val MEDIUM_THRESHOLD: Double = 0.35

        /** Lower bound (inclusive) of the HIGH band. */
        const val HIGH_THRESHOLD: Double = 0.65

        /**
         * Classify a risk score into its band.
         *
         * @param riskScore risk score in [0,1]
         * @return the matching level
         */
        fun fromRiskScore(riskScore: Double): ScrambleLevel = when {
            riskScore >= HIGH_THRESHOLD   -> HIGH
            riskScore >= MEDIUM_THRESHOLD -> MEDIUM
            else                          -> LOW
        }
    }
}
