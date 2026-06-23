package com.resequencetwin.control.drift

/**
 * Pure, stateless structural diff between an [ExpectedConfig] baseline and a current [ObservedConfig].
 *
 * Emits one [ConfigDriftFinding] per divergence with an advisory [ReconciliationProposal]. Output is
 * deterministically ordered (by laneId, then a fixed kind order) so reports and tests are stable.
 *
 * **Advisory only** — proposals describe the twin-side change that would re-align to observed reality;
 * nothing is applied automatically and there is no write-back.
 */
class ConfigDriftDetector {

    fun detect(expected: ExpectedConfig, observed: ObservedConfig): List<ConfigDriftFinding> {
        val findings = mutableListOf<ConfigDriftFinding>()

        for ((laneId, expectedCap) in expected.lanes) {
            val observedCap = observed.lanes[laneId]
            if (observedCap == null) {
                findings += ConfigDriftFinding(
                    laneId = laneId, kind = DriftKind.EXPECTED_LANE_MISSING,
                    expected = "present(cap=$expectedCap)", observed = "absent",
                    proposal = ReconciliationProposal(
                        action = "REVIEW_LANE_REMOVAL",
                        description = "Lane $laneId in baseline is absent from observed config; " +
                            "review whether the twin topology should drop $laneId (advisory).",
                    ),
                )
            } else if (observedCap != expectedCap) {
                findings += ConfigDriftFinding(
                    laneId = laneId, kind = DriftKind.CAPACITY_CHANGED,
                    expected = expectedCap.toString(), observed = observedCap.toString(),
                    proposal = ReconciliationProposal(
                        action = "ADJUST_CAPACITY",
                        description = "Twin lane $laneId capacity $expectedCap -> $observedCap (advisory).",
                    ),
                )
            }
        }

        for (laneId in observed.blocked) {
            if (laneId !in expected.expectedBlocked) {
                findings += ConfigDriftFinding(
                    laneId = laneId, kind = DriftKind.UNEXPECTED_BLOCK,
                    expected = "unblocked", observed = "blocked",
                    proposal = ReconciliationProposal(
                        action = "REVIEW_BLOCK",
                        description = "Lane $laneId is blocked in observed config but not in baseline; " +
                            "review the blockage cause (advisory).",
                    ),
                )
            }
        }

        return findings.sortedWith(compareBy({ it.laneId }, { it.kind.ordinal }))
    }
}
