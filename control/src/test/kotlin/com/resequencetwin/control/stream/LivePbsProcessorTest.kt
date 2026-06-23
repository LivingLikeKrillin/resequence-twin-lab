package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Lane
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LivePbsProcessorTest {

    private fun lanes(vararg capacities: Int) = capacities.mapIndexed { i, c -> Lane("L${i+1}", c) }
    private fun arrived(id: String, seq: Long = 1L, color: String = "RED", model: String = "M1",
                        dueDateSeq: Int = 0, options: Set<String> = emptySet()) =
        BodyArrived(eventId = "e-$id-$seq", seq = seq, bodyId = id,
                    color = color, model = model, options = options, dueDateSeq = dueDateSeq)
    private fun tick(seq: Long) = ReleaseTick(eventId = "tick-$seq", seq = seq)

    // BodyArrived → assignment
    @Test
    fun `BodyArrived assigns body to a lane`() {
        val proc = LivePbsProcessor(lanes(5))
        val result = proc.process(arrived("B1"))
        assertThat(result).isInstanceOf(ProcessResult.Assigned::class.java)
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants["L1"]).containsExactly("B1")
    }

    @Test
    fun `multiple arrivals fill lanes per policy`() {
        val proc = LivePbsProcessor(lanes(2, 2))
        repeat(4) { i -> proc.process(arrived("B${i+1}", seq = i.toLong() + 1)) }
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants.values.flatten()).hasSize(4)
        assertThat(snap.laneOccupants["L1"]?.size).isEqualTo(2)
        assertThat(snap.laneOccupants["L2"]?.size).isEqualTo(2)
    }

    // ReleaseTick
    @Test
    fun `ReleaseTick releases one body`() {
        val proc = LivePbsProcessor(lanes(3))
        proc.process(arrived("B1", seq = 1))
        val result = proc.process(tick(2))
        assertThat(result).isInstanceOf(ProcessResult.Released::class.java)
        val snap = proc.snapshot()
        assertThat(snap.releases).isEqualTo(1)
        assertThat(snap.assemblyOutSize).isEqualTo(1)
    }

    @Test
    fun `colourChanges KPI increments on colour switch`() {
        val proc = LivePbsProcessor(lanes(5))
        proc.process(arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0))
        proc.process(arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1))
        proc.process(tick(3))
        proc.process(tick(4))
        assertThat(proc.snapshot().colourChanges).isEqualTo(1)
    }

    @Test
    fun `colourChanges does NOT increment when same colour`() {
        val proc = LivePbsProcessor(lanes(5))
        proc.process(arrived("B1", seq = 1, color = "RED", dueDateSeq = 0))
        proc.process(arrived("B2", seq = 2, color = "RED", dueDateSeq = 1))
        proc.process(tick(3))
        proc.process(tick(4))
        assertThat(proc.snapshot().colourChanges).isEqualTo(0)
    }

    @Test
    fun `ReleaseTick on empty processor returns Idle without crash`() {
        val proc = LivePbsProcessor(lanes(3))
        val result = proc.process(tick(1))
        assertThat(result).isEqualTo(ProcessResult.Idle)
        assertThat(proc.snapshot().releases).isEqualTo(0)
    }

    // Idempotency / replay safety
    @Test
    fun `same eventId processed twice returns Duplicate second time`() {
        val proc = LivePbsProcessor(lanes(3))
        val event = arrived("B1", seq = 1)
        proc.process(event)
        val second = proc.process(event)
        assertThat(second).isEqualTo(ProcessResult.Duplicate)
        assertThat(proc.snapshot().duplicates).isEqualTo(1)
        assertThat(proc.snapshot().laneOccupants["L1"]).hasSize(1)
    }

    @Test
    fun `same processor replay is idempotent`() {
        val events = listOf(
            arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0),
            arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1),
            tick(3),
            tick(4),
        )
        val proc = LivePbsProcessor(lanes(5))
        events.forEach { proc.process(it) }
        val snap1 = proc.snapshot()
        // replay the same events — idempotency must keep business state identical
        // (duplicates counter will increment, which is correct bookkeeping, not a state change)
        events.forEach { proc.process(it) }
        val snap2 = proc.snapshot()
        assertThat(snap2.laneOccupants).isEqualTo(snap1.laneOccupants)
        assertThat(snap2.assemblyOutSize).isEqualTo(snap1.assemblyOutSize)
        assertThat(snap2.releases).isEqualTo(snap1.releases)
        assertThat(snap2.colourChanges).isEqualTo(snap1.colourChanges)
        assertThat(snap2.buffered).isEqualTo(snap1.buffered)
        assertThat(snap2.blockedLanes).isEqualTo(snap1.blockedLanes)
        assertThat(snap2.duplicates).isEqualTo(snap1.duplicates + events.size)
    }

    @Test
    fun `fresh processor built from same event log reaches identical final snapshot`() {
        val events = listOf(
            arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0),
            arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1),
            tick(3),
            tick(4),
        )
        val proc1 = LivePbsProcessor(lanes(5))
        events.forEach { proc1.process(it) }

        // Fresh instance, same log — replay safety proof
        val proc2 = LivePbsProcessor(lanes(5))
        events.forEach { proc2.process(it) }

        assertThat(proc2.snapshot()).isEqualTo(proc1.snapshot())
    }

    // Lane block degradation
    @Test
    fun `blocked lane receives no assignments`() {
        val proc = LivePbsProcessor(lanes(2, 2))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 1, laneId = "L1"))
        proc.process(arrived("B1", seq = 2))
        proc.process(arrived("B2", seq = 3))
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants["L1"]).isEmpty()
        assertThat(snap.laneOccupants["L2"]).containsExactlyInAnyOrder("B1", "B2")
    }

    @Test
    fun `blocked lane excluded from release`() {
        val proc = LivePbsProcessor(lanes(2, 2))
        proc.process(arrived("B1", seq = 1))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 2, laneId = "L1"))
        val result = proc.process(tick(3))
        assertThat(result).isEqualTo(ProcessResult.Idle)
        assertThat(proc.snapshot().laneOccupants["L1"]).containsExactly("B1")
    }

    @Test
    fun `arrivals when ALL lanes blocked are deferred, no crash`() {
        val proc = LivePbsProcessor(lanes(2))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 1, laneId = "L1"))
        val result = proc.process(arrived("B1", seq = 2))
        assertThat(result).isInstanceOf(ProcessResult.Deferred::class.java)
        assertThat(proc.snapshot().buffered).isEqualTo(1)
    }

    @Test
    fun `unblock drains buffered bodies into freed lane`() {
        val proc = LivePbsProcessor(lanes(2))
        proc.process(LaneBlocked(eventId = "blk-1", seq = 1, laneId = "L1"))
        proc.process(arrived("B1", seq = 2))
        assertThat(proc.snapshot().buffered).isEqualTo(1)

        proc.process(LaneUnblocked(eventId = "ublk-1", seq = 3, laneId = "L1"))
        val snap = proc.snapshot()
        assertThat(snap.buffered).isEqualTo(0)
        assertThat(snap.laneOccupants["L1"]).containsExactly("B1")
    }

    // Rush order
    @Test
    fun `rush order (dueDateSeq=0) is released promptly by overdue override`() {
        // Two lanes so NORMAL (RED) lands in L1 and RUSH (BLUE) lands in L2 via colour-affinity
        // fallback to least-loaded. With separate fronts the overdue override (dueDateSeq=0 <= 0)
        // picks RUSH even though NORMAL arrived first.
        val proc = LivePbsProcessor(lanes(5, 5))
        proc.process(arrived("NORMAL", seq = 1, color = "RED",  dueDateSeq = 100))
        proc.process(arrived("RUSH",   seq = 2, color = "BLUE", dueDateSeq = 0))
        val releaseResult = proc.process(tick(3)) as ProcessResult.Released
        assertThat(releaseResult.body.id).isEqualTo("RUSH")
    }

    // Malformed / rejected events
    @Test
    fun `blank color rejected, counter increments, state unchanged`() {
        val proc = LivePbsProcessor(lanes(3))
        val result = proc.process(arrived("B1", seq = 1, color = ""))
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat((result as ProcessResult.Rejected).reason).containsIgnoringCase("color")
        assertThat(proc.snapshot().rejected).isEqualTo(1)
        assertThat(proc.snapshot().laneOccupants.values.flatten()).isEmpty()
    }

    @Test
    fun `negative dueDateSeq rejected`() {
        val proc = LivePbsProcessor(lanes(3))
        val event = BodyArrived(eventId = "bad-1", seq = 1, bodyId = "BX",
                                color = "RED", model = "M1", dueDateSeq = -1)
        val result = proc.process(event)
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat((result as ProcessResult.Rejected).reason).containsIgnoringCase("dueDateSeq")
        assertThat(proc.snapshot().rejected).isEqualTo(1)
    }

    @Test
    fun `blank bodyId rejected`() {
        val proc = LivePbsProcessor(lanes(3))
        val event = BodyArrived(eventId = "bad-2", seq = 1, bodyId = "  ",
                                color = "RED", model = "M1", dueDateSeq = 0)
        val result = proc.process(event)
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat((result as ProcessResult.Rejected).reason).containsIgnoringCase("bodyId")
    }

    // Out-of-order
    @Test
    fun `stale seq increments outOfOrder counter`() {
        val proc = LivePbsProcessor(lanes(3))
        proc.process(arrived("B1", seq = 10))
        proc.process(arrived("B2", seq = 5))
        assertThat(proc.snapshot().outOfOrder).isEqualTo(1)
    }

    @Test
    fun `stale seq event is still processed (process-anyway policy)`() {
        val proc = LivePbsProcessor(lanes(3))
        proc.process(arrived("B1", seq = 10))
        proc.process(arrived("B2", seq = 5))
        val snap = proc.snapshot()
        assertThat(snap.laneOccupants.values.flatten()).containsExactlyInAnyOrder("B1", "B2")
    }

    // Determinism
    @Test
    fun `same event sequence produces identical final snapshots`() {
        val events = listOf(
            arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0),
            arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1),
            arrived("B3", seq = 3, color = "RED",  dueDateSeq = 2),
            tick(4),
            tick(5),
        )
        val proc1 = LivePbsProcessor(lanes(5))
        events.forEach { proc1.process(it) }

        val proc2 = LivePbsProcessor(lanes(5))
        events.forEach { proc2.process(it) }

        assertThat(proc1.snapshot()).isEqualTo(proc2.snapshot())
    }

    // Fault completeness: unknown-lane guard
    @Test
    fun `LaneBlocked on unknown lane returns Rejected and increments rejected counter`() {
        val proc = LivePbsProcessor(lanes(2))
        val result = proc.process(LaneBlocked(eventId = "blk-x", seq = 1, laneId = "GHOST"))
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat((result as ProcessResult.Rejected).reason).containsIgnoringCase("unknown lane")
        assertThat(proc.snapshot().rejected).isEqualTo(1)
        // Must not add GHOST to blocked set
        assertThat(proc.snapshot().blockedLanes).doesNotContain("GHOST")
    }

    @Test
    fun `LaneUnblocked on unknown lane returns Rejected and increments rejected counter`() {
        val proc = LivePbsProcessor(lanes(2))
        val result = proc.process(LaneUnblocked(eventId = "ublk-x", seq = 1, laneId = "GHOST"))
        assertThat(result).isInstanceOf(ProcessResult.Rejected::class.java)
        assertThat((result as ProcessResult.Rejected).reason).containsIgnoringCase("unknown lane")
        assertThat(proc.snapshot().rejected).isEqualTo(1)
    }

    @Test
    fun `partial-capacity drain — 3 deferred bodies, lane with capacity 2, exactly 2 drain and 1 remains buffered`() {
        // L1 capacity=2, L2 capacity=5. Block L1 so all bodies go to L2 or defer.
        // Block both lanes so arrivals are deferred. Then unblock L1 (cap=2) → drain exactly 2.
        val proc = LivePbsProcessor(lanes(2, 5))
        proc.process(LaneBlocked(eventId = "blk-L1", seq = 1, laneId = "L1"))
        proc.process(LaneBlocked(eventId = "blk-L2", seq = 2, laneId = "L2"))
        // Arrive 3 bodies — all deferred (both lanes blocked)
        proc.process(arrived("D1", seq = 3))
        proc.process(arrived("D2", seq = 4))
        proc.process(arrived("D3", seq = 5))
        assertThat(proc.snapshot().buffered).isEqualTo(3)
        // Unblock L1 (capacity 2) — only 2 of 3 deferred bodies can drain
        proc.process(LaneUnblocked(eventId = "ublk-L1", seq = 6, laneId = "L1"))
        val snap = proc.snapshot()
        assertThat(snap.buffered).isEqualTo(1)
        assertThat(snap.laneOccupants["L1"]?.size).isEqualTo(2)
    }
}
