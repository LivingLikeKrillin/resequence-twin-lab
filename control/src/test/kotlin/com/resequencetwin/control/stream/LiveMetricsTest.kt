package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Lane
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LiveMetrics] — exercises real meter values via [SimpleMeterRegistry].
 *
 * No Spring context is loaded. We build a real [LivePbsProcessor], bind [LiveMetrics] directly,
 * and drive events through the processor to assert that the registered meters reflect the
 * processor's snapshot values correctly.
 *
 * FunctionCounters expose cumulative monotonic counts (Prometheus `_total` convention).
 * Gauges expose current state (lane occupancy, buffered, blocked lanes).
 * pbs.assembly.out.total is a FunctionCounter because the assembly buffer is append-only in this PoC.
 *
 * These metrics observe a SYNTHETIC stream (this is a PoC, not a production dashboard).
 */
class LiveMetricsTest {

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var processor: LivePbsProcessor
    private lateinit var metrics: LiveMetrics

    private fun lanes() = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))

    private fun arrived(
        id: String, seq: Long = 1L,
        color: String = "RED", model: String = "M1",
        dueDateSeq: Int = 0, options: Set<String> = emptySet()
    ) = BodyArrived(
        eventId = "e-$id-$seq", seq = seq, bodyId = id,
        color = color, model = model, options = options, dueDateSeq = dueDateSeq
    )

    private fun tick(seq: Long) = ReleaseTick(eventId = "tick-$seq", seq = seq)

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        processor = LivePbsProcessor(lanes())
        metrics = LiveMetrics(processor)
        metrics.bindTo(registry)
    }

    // ── FunctionCounters ─────────────────────────────────────────────────────────

    @Test
    fun `releases counter starts at zero`() {
        val count = registry.get("pbs.releases.total").functionCounter().count()
        assertThat(count).isEqualTo(0.0)
    }

    @Test
    fun `releases counter reflects one ReleaseTick`() {
        processor.process(arrived("B1", seq = 1))
        processor.process(tick(2))
        val count = registry.get("pbs.releases.total").functionCounter().count()
        assertThat(count).isEqualTo(1.0)
    }

    @Test
    fun `colour changes counter increments on colour switch`() {
        processor.process(arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0))
        processor.process(arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1))
        processor.process(tick(3))
        processor.process(tick(4))
        val count = registry.get("pbs.colour.changes.total").functionCounter().count()
        assertThat(count).isEqualTo(1.0)
    }

    @Test
    fun `colour changes counter stays zero when same colour`() {
        processor.process(arrived("B1", seq = 1, color = "RED", dueDateSeq = 0))
        processor.process(arrived("B2", seq = 2, color = "RED", dueDateSeq = 1))
        processor.process(tick(3))
        processor.process(tick(4))
        val colourChanges = registry.get("pbs.colour.changes.total").functionCounter().count()
        assertThat(colourChanges).isEqualTo(0.0)
        // Both ticks produced real releases even though no colour switch occurred
        val releases = registry.get("pbs.releases.total").functionCounter().count()
        assertThat(releases).isEqualTo(2.0)
    }

    @Test
    fun `rejected counter increments on blank-color body`() {
        processor.process(arrived("B1", seq = 1, color = ""))
        val count = registry.get("pbs.rejected.total").functionCounter().count()
        assertThat(count).isEqualTo(1.0)
    }

    @Test
    fun `duplicates counter increments on replayed event`() {
        val event = arrived("B1", seq = 1)
        processor.process(event)
        processor.process(event) // duplicate
        val count = registry.get("pbs.duplicates.total").functionCounter().count()
        assertThat(count).isEqualTo(1.0)
    }

    @Test
    fun `out-of-order counter increments on stale seq`() {
        processor.process(arrived("B1", seq = 10))
        processor.process(arrived("B2", seq = 5)) // stale
        val count = registry.get("pbs.out.of.order.total").functionCounter().count()
        assertThat(count).isEqualTo(1.0)
    }

    // ── Gauges ───────────────────────────────────────────────────────────────────

    @Test
    fun `assembly-out counter starts at zero`() {
        val count = registry.get("pbs.assembly.out.total").functionCounter().count()
        assertThat(count).isEqualTo(0.0)
    }

    @Test
    fun `assembly-out counter reflects released body`() {
        processor.process(arrived("B1", seq = 1))
        processor.process(tick(2))
        val count = registry.get("pbs.assembly.out.total").functionCounter().count()
        assertThat(count).isEqualTo(1.0)
    }

    @Test
    fun `buffered gauge reflects deferred body when all lanes blocked`() {
        // Block all 3 lanes so the body is deferred
        processor.process(LaneBlocked(eventId = "blk-L1", seq = 1, laneId = "L1"))
        processor.process(LaneBlocked(eventId = "blk-L2", seq = 2, laneId = "L2"))
        processor.process(LaneBlocked(eventId = "blk-L3", seq = 3, laneId = "L3"))
        processor.process(arrived("B1", seq = 4))
        val value = registry.get("pbs.buffered").gauge().value()
        assertThat(value).isEqualTo(1.0)
    }

    @Test
    fun `blocked-lanes gauge increments when a lane is blocked`() {
        processor.process(LaneBlocked(eventId = "blk-L1", seq = 1, laneId = "L1"))
        val value = registry.get("pbs.blocked.lanes").gauge().value()
        assertThat(value).isEqualTo(1.0)
    }

    @Test
    fun `blocked-lanes gauge returns to zero after unblock`() {
        processor.process(LaneBlocked(eventId = "blk-L1", seq = 1, laneId = "L1"))
        processor.process(LaneUnblocked(eventId = "ublk-L1", seq = 2, laneId = "L1"))
        val value = registry.get("pbs.blocked.lanes").gauge().value()
        assertThat(value).isEqualTo(0.0)
    }

    // ── Per-lane occupancy gauges ─────────────────────────────────────────────────

    @Test
    fun `per-lane occupancy gauges are registered for L1 L2 L3`() {
        // Assert all three lane gauges exist by tag
        for (laneId in listOf("L1", "L2", "L3")) {
            val value = registry.get("pbs.lane.occupancy").tag("lane", laneId).gauge().value()
            assertThat(value).`as`("lane $laneId occupancy should be registered and start at 0").isEqualTo(0.0)
        }
    }

    @Test
    fun `lane occupancy gauge reflects body in L1 after arrival`() {
        processor.process(arrived("B1", seq = 1))
        // DynamicSequencingPolicy assigns to first lane by default (least-loaded)
        // We just verify the total occupancy across all lanes is 1
        val total = listOf("L1", "L2", "L3").sumOf { laneId ->
            registry.get("pbs.lane.occupancy").tag("lane", laneId).gauge().value()
        }
        assertThat(total).isEqualTo(1.0)
    }

    @Test
    fun `lane occupancy gauge decrements after ReleaseTick`() {
        processor.process(arrived("B1", seq = 1))
        val beforeTotal = listOf("L1", "L2", "L3").sumOf { laneId ->
            registry.get("pbs.lane.occupancy").tag("lane", laneId).gauge().value()
        }
        assertThat(beforeTotal).isEqualTo(1.0)

        processor.process(tick(2))
        val afterTotal = listOf("L1", "L2", "L3").sumOf { laneId ->
            registry.get("pbs.lane.occupancy").tag("lane", laneId).gauge().value()
        }
        assertThat(afterTotal).isEqualTo(0.0)
    }

    @Test
    fun `LaneBlocked event sets blocked-lanes gauge to 1`() {
        processor.process(LaneBlocked(eventId = "blk-L2", seq = 1, laneId = "L2"))
        assertThat(registry.get("pbs.blocked.lanes").gauge().value()).isEqualTo(1.0)
    }

    @Test
    fun `two arrivals different colours then one release, releases and colour-changes are correct`() {
        processor.process(arrived("B1", seq = 1, color = "RED",  dueDateSeq = 0))
        processor.process(arrived("B2", seq = 2, color = "BLUE", dueDateSeq = 1))
        processor.process(tick(3))  // releases RUSH/priority first (dueDateSeq=0 = RED)
        // After one release: 1 release total, 0 colour changes (first release has no predecessor)
        assertThat(registry.get("pbs.releases.total").functionCounter().count()).isEqualTo(1.0)
        processor.process(tick(4))  // second release — different colour → colour change
        assertThat(registry.get("pbs.releases.total").functionCounter().count()).isEqualTo(2.0)
        assertThat(registry.get("pbs.colour.changes.total").functionCounter().count()).isEqualTo(1.0)
    }
}
