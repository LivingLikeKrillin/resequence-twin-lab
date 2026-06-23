package com.resequencetwin.control.pbs

class StaticSequencingPolicy : SequencingPolicy {

    private var rrCursor = 0

    override fun assignLane(body: Body, state: PbsState): String {
        val laneIds = state.laneIds()
        val n = laneIds.size
        if (n == 0) throw IllegalStateException("PBS state has no lanes")
        for (attempt in 0 until n) {
            val idx = (rrCursor + attempt) % n
            val laneId = laneIds[idx]
            val lane = state.lane(laneId)!!
            if (!lane.isFull()) {
                rrCursor = (idx + 1) % n
                return laneId
            }
        }
        throw IllegalStateException("All lanes full — cannot assign body ${body.id}")
    }

    override fun selectRelease(state: PbsState): Body {
        var bestLane: String? = null
        var bestDue = Int.MAX_VALUE
        for (laneId in state.laneIds()) {
            val lane = state.lane(laneId)!!
            val front = lane.front() ?: continue
            if (front.dueDateSeq < bestDue) {
                bestDue = front.dueDateSeq
                bestLane = laneId
            }
        }
        if (bestLane == null) throw IllegalStateException("All lanes empty — nothing to release")
        return state.releaseFromLane(bestLane)
    }
}
