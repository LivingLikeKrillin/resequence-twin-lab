package com.resequencetwin.control.pbs

/**
 * Strategy interface for PBS sequencing decisions.
 *
 * Implementations decide:
 * 1. Which lane an incoming [Body] should be assigned to ([assignLane]).
 * 2. Which body (from which lane front) should be released next to the assembly line ([selectRelease]).
 */
interface SequencingPolicy {
    /**
     * Assigns the given [body] to one of the lanes in [state], returning the lane ID chosen.
     */
    fun assignLane(body: Body, state: PbsState): String

    /**
     * Selects and releases the next body from [state] to the assembly output stream.
     */
    fun selectRelease(state: PbsState): Body
}
