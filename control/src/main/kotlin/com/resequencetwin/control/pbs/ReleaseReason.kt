package com.resequencetwin.control.pbs

/**
 * Why [DynamicSequencingPolicy] chose a particular body for release (rev3 R4a).
 *
 * This enum makes the policy's release decision *auditable*: every release can be
 * attributed to exactly one of the two decision branches in
 * [DynamicSequencingPolicy.selectRelease].
 */
enum class ReleaseReason {

    /**
     * The anti-starvation hard override fired: at least one lane-front body was overdue
     * (`dueDateSeq <= assemblyOut().size()`), so the overdue body with the smallest
     * `dueDateSeq` was released regardless of its multi-objective score.
     */
    OVERDUE_OVERRIDE,

    /**
     * No lane-front body was overdue, so the release was the candidate with the highest
     * multi-objective weighted score.
     */
    HIGHEST_SCORE
}
