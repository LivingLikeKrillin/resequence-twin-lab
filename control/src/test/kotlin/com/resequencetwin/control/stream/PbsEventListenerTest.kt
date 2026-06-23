package com.resequencetwin.control.stream

import com.resequencetwin.control.pbs.Lane
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the topology parser and the listener-to-processor bridge.
 * No Spring context, no Kafka broker required.
 */
class PbsEventListenerTest {

    // ── Topology parser ──────────────────────────────────────────────────────

    @Test
    fun `canonical topology parses to 3 lanes with correct ids and capacities`() {
        val lanes = parseLaneTopology("L1:10,L2:10,L3:10")
        assertThat(lanes).hasSize(3)
        assertThat(lanes.map { it.id }).containsExactly("L1", "L2", "L3")
        assertThat(lanes.map { it.capacity }).containsExactly(10, 10, 10)
    }

    @Test
    fun `parser accepts heterogeneous capacities`() {
        val lanes = parseLaneTopology("A:5,B:20,C:1")
        assertThat(lanes).hasSize(3)
        assertThat(lanes.map { it.id }).containsExactly("A", "B", "C")
        assertThat(lanes.map { it.capacity }).containsExactly(5, 20, 1)
    }

    @Test
    fun `single lane parses correctly`() {
        val lanes = parseLaneTopology("ONLY:7")
        assertThat(lanes).hasSize(1)
        assertThat(lanes[0].id).isEqualTo("ONLY")
        assertThat(lanes[0].capacity).isEqualTo(7)
    }

    @Test
    fun `zero capacity throws with clear message`() {
        assertThatThrownBy { parseLaneTopology("L1:0") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("capacity")
    }

    @Test
    fun `negative capacity throws`() {
        assertThatThrownBy { parseLaneTopology("L1:-5") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("capacity")
    }

    @Test
    fun `missing colon separator throws`() {
        assertThatThrownBy { parseLaneTopology("L1") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-numeric capacity throws`() {
        assertThatThrownBy { parseLaneTopology("L1:abc") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `empty string throws`() {
        assertThatThrownBy { parseLaneTopology("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("empty")
    }

    @Test
    fun `blank string throws`() {
        assertThatThrownBy { parseLaneTopology("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("empty")
    }

    @Test
    fun `blank lane id token throws with clear message`() {
        // Token ":10" has an empty id part — should fail before reaching Lane.init
        assertThatThrownBy { parseLaneTopology(":10") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blank lane id")
    }

    // ── Listener bridges to processor ────────────────────────────────────────

    private fun canonicalProcessor() = LivePbsProcessor(
        listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))
    )

    @Test
    fun `onEvent BodyArrived assigns body and snapshot reflects it`() {
        val processor = canonicalProcessor()
        val listener = PbsEventListener(processor)

        listener.onEvent(
            BodyArrived(
                eventId = "e-B1-1",
                seq = 1L,
                bodyId = "B1",
                color = "RED",
                model = "M1",
            )
        )

        val snap = processor.snapshot()
        assertThat(snap.laneOccupants.values.flatten()).containsExactly("B1")
    }

    @Test
    fun `onEvent ReleaseTick after BodyArrived advances releases counter`() {
        val processor = canonicalProcessor()
        val listener = PbsEventListener(processor)

        listener.onEvent(
            BodyArrived(eventId = "e-B1-1", seq = 1L, bodyId = "B1", color = "RED", model = "M1")
        )
        listener.onEvent(ReleaseTick(eventId = "tick-2", seq = 2L))

        val snap = processor.snapshot()
        assertThat(snap.releases).isEqualTo(1)
        assertThat(snap.assemblyOutSize).isEqualTo(1)
        assertThat(snap.laneOccupants.values.flatten()).isEmpty()
    }

    @Test
    fun `onEvent returns without throwing on Idle tick`() {
        val processor = canonicalProcessor()
        val listener = PbsEventListener(processor)

        // No bodies — tick produces Idle; listener must not throw
        listener.onEvent(ReleaseTick(eventId = "tick-1", seq = 1L))

        assertThat(processor.snapshot().releases).isEqualTo(0)
    }

    @Test
    fun `onEvent multiple bodies fill lanes then release drains one`() {
        val processor = canonicalProcessor()
        val listener = PbsEventListener(processor)

        repeat(5) { i ->
            listener.onEvent(
                BodyArrived(
                    eventId = "e-B${i + 1}",
                    seq = (i + 1).toLong(),
                    bodyId = "B${i + 1}",
                    color = "RED",
                    model = "M1",
                )
            )
        }
        listener.onEvent(ReleaseTick(eventId = "tick-6", seq = 6L))

        val snap = processor.snapshot()
        assertThat(snap.releases).isEqualTo(1)
        assertThat(snap.laneOccupants.values.flatten()).hasSize(4)
    }

    @Test
    fun `listener shares same processor instance — snapshot reflects all calls`() {
        val processor = canonicalProcessor()
        val listener = PbsEventListener(processor)

        listener.onEvent(
            BodyArrived(eventId = "e-X-1", seq = 1L, bodyId = "X", color = "BLUE", model = "SUV")
        )
        listener.onEvent(
            BodyArrived(eventId = "e-Y-2", seq = 2L, bodyId = "Y", color = "RED", model = "M2")
        )

        // Both bodies must be visible via the same processor reference
        assertThat(processor.snapshot().laneOccupants.values.flatten())
            .containsExactlyInAnyOrder("X", "Y")
    }
}
