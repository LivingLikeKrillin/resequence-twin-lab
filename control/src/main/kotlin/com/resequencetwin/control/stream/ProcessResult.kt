package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Body

/**
 * Typed outcome of [LivePbsProcessor.process].
 * Caller can pattern-match exhaustively with `when`.
 */
sealed interface ProcessResult {
    /** Event was a duplicate (eventId already processed). State unchanged. */
    data object Duplicate : ProcessResult

    /** [BodyArrived] failed validation. State unchanged. */
    data class Rejected(val reason: String) : ProcessResult

    /**
     * [BodyArrived] was valid but all non-blocked lanes were full.
     * Body placed in internal buffer; retried on LaneUnblocked or after ReleaseTick.
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
