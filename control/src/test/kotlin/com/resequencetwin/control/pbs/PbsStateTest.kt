package com.resequencetwin.control.pbs

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.NoSuchElementException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class PbsStateTest {

    @Test
    @DisplayName("Body.incoming: fields round-trip correctly")
    fun bodyIncomingFieldsRoundTrip() {
        val b = Body.incoming("VIN001", "RED", "Fiesta", setOf("SUNROOF", "LEATHER"), 42)

        assertThat(b.id).isEqualTo("VIN001")
        assertThat(b.color).isEqualTo("RED")
        assertThat(b.model).isEqualTo("Fiesta")
        assertThat(b.options).containsExactlyInAnyOrder("SUNROOF", "LEATHER")
        assertThat(b.dueDateSeq).isEqualTo(42)
        assertThat(b.currentLane).isNull()
        assertThat(b.lanePos).isEqualTo(-1)
        assertThat(b.isAssigned()).isFalse()
    }

    @Test
    @DisplayName("Body.withLaneAssignment: returns new instance with updated lane fields")
    fun bodyWithLaneAssignment() {
        val original = Body.incoming("VIN002", "BLUE", "Focus", emptySet(), 7)
        val assigned = original.withLaneAssignment("L1", 0)

        assertThat(original.currentLane).isNull()
        assertThat(original.lanePos).isEqualTo(-1)

        assertThat(assigned.id).isEqualTo("VIN002")
        assertThat(assigned.currentLane).isEqualTo("L1")
        assertThat(assigned.lanePos).isEqualTo(0)
        assertThat(assigned.isAssigned()).isTrue()
    }

    @Test
    @DisplayName("Body: options set is immutable (defensive copy)")
    fun bodyOptionsAreImmutable() {
        val mutableOptions = HashSet(setOf("OPT_A"))
        val b = Body.incoming("V3", "GREEN", "Puma", mutableOptions, 1)
        mutableOptions.add("OPT_B")

        assertThat(b.options).containsExactly("OPT_A")
        @Suppress("UNCHECKED_CAST")
        assertThatThrownBy { (b.options as MutableCollection<String>).add("OPT_C") }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    @DisplayName("Lane: assign appends to rear; occupants() is front-first")
    fun laneAssignAppendsToRear() {
        val lane = Lane("L1", 4)

        val b1 = Body.incoming("V1", "RED",  "Fiesta", emptySet(), 1)
        val b2 = Body.incoming("V2", "RED",  "Fiesta", emptySet(), 2)
        val b3 = Body.incoming("V3", "BLUE", "Focus",  emptySet(), 3)

        lane.assign(b1)
        lane.assign(b2)
        lane.assign(b3)

        assertThat(lane.size()).isEqualTo(3)
        val occupants = lane.occupants()
        assertThat(occupants).extracting<String> { it.id }.containsExactly("V1", "V2", "V3")
        assertThat(occupants[0].lanePos).isEqualTo(0)
        assertThat(occupants[1].lanePos).isEqualTo(1)
        assertThat(occupants[2].lanePos).isEqualTo(2)
    }

    @Test
    @DisplayName("Lane: release pops front (FIFO order)")
    fun laneReleasePopsFromFront() {
        val lane = Lane("L1", 4)
        val b1 = Body.incoming("V1", "RED",  "Fiesta", emptySet(), 1)
        val b2 = Body.incoming("V2", "RED",  "Fiesta", emptySet(), 2)
        val b3 = Body.incoming("V3", "BLUE", "Focus",  emptySet(), 3)
        lane.assign(b1)
        lane.assign(b2)
        lane.assign(b3)

        val released1 = lane.release()
        val released2 = lane.release()
        val released3 = lane.release()

        assertThat(released1.id).isEqualTo("V1")
        assertThat(released2.id).isEqualTo("V2")
        assertThat(released3.id).isEqualTo("V3")
        assertThat(lane.isEmpty()).isTrue()
    }

    @Test
    @DisplayName("Lane: assign to full lane throws LaneFullException")
    fun laneFullThrows() {
        val lane = Lane("L1", 2)
        lane.assign(Body.incoming("V1", "RED", "Fiesta", emptySet(), 1))
        lane.assign(Body.incoming("V2", "RED", "Fiesta", emptySet(), 2))

        assertThat(lane.isFull()).isTrue()
        assertThatThrownBy { lane.assign(Body.incoming("V3", "BLUE", "Focus", emptySet(), 3)) }
            .isInstanceOf(Lane.LaneFullException::class.java)
            .hasMessageContaining("L1")
    }

    @Test
    @DisplayName("Lane: release from empty lane throws NoSuchElementException")
    fun laneEmptyReleaseThrows() {
        val lane = Lane("L2", 3)
        assertThatThrownBy { lane.release() }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    @DisplayName("PbsState: assign N bodies across two lanes → per-lane FIFO order correct")
    fun pbsStateAssignAcrossLanes() {
        val state = PbsState.withLanes(listOf(Lane("L1", 4), Lane("L2", 4)))

        val v1 = Body.incoming("V1", "RED",  "Fiesta", emptySet(), 1)
        val v2 = Body.incoming("V2", "BLUE", "Focus",  emptySet(), 2)
        val v3 = Body.incoming("V3", "RED",  "Fiesta", emptySet(), 3)
        val v4 = Body.incoming("V4", "BLUE", "Focus",  emptySet(), 4)

        state.assignToLane(v1, "L1")
        state.assignToLane(v2, "L2")
        state.assignToLane(v3, "L1")
        state.assignToLane(v4, "L2")

        val l1 = state.lane("L1")!!.occupants()
        val l2 = state.lane("L2")!!.occupants()

        assertThat(l1).extracting<String> { it.id }.containsExactly("V1", "V3")
        assertThat(l2).extracting<String> { it.id }.containsExactly("V2", "V4")
    }

    @Test
    @DisplayName("PbsState: releaseFromLane preserves FIFO and appends to assemblyOut")
    fun pbsStateReleaseIsLifoFreeAndRecorded() {
        val state = PbsState.withLanes(listOf(Lane("L1", 4)))

        val v1 = Body.incoming("V1", "RED",  "Fiesta", emptySet(), 1)
        val v2 = Body.incoming("V2", "RED",  "Fiesta", emptySet(), 2)
        val v3 = Body.incoming("V3", "BLUE", "Focus",  emptySet(), 3)

        state.assignToLane(v1, "L1")
        state.assignToLane(v2, "L1")
        state.assignToLane(v3, "L1")

        val r1 = state.releaseFromLane("L1")
        val r2 = state.releaseFromLane("L1")

        assertThat(r1.id).isEqualTo("V1")
        assertThat(r2.id).isEqualTo("V2")

        assertThat(state.assemblyOut()).extracting<String> { it.id }.containsExactly("V1", "V2")

        assertThat(state.lane("L1")!!.size()).isEqualTo(1)
        assertThat(state.lane("L1")!!.front()!!.id).isEqualTo("V3")
    }

    @Test
    @DisplayName("PbsState: full-lane exception propagated from assignToLane")
    fun pbsStateLaneFullPropagates() {
        val state = PbsState.withLanes(listOf(Lane("L1", 2)))

        state.assignToLane(Body.incoming("V1", "RED", "Fiesta", emptySet(), 1), "L1")
        state.assignToLane(Body.incoming("V2", "RED", "Fiesta", emptySet(), 2), "L1")

        assertThatThrownBy {
            state.assignToLane(Body.incoming("V3", "BLUE", "Focus", emptySet(), 3), "L1")
        }.isInstanceOf(Lane.LaneFullException::class.java)
    }

    @Test
    @DisplayName("PbsState: arrive records in paintStream; assignToLane does not auto-arrive")
    fun pbsStatePaintStreamTracking() {
        val state = PbsState.withLanes(listOf(Lane("L1", 4)))

        val b = Body.incoming("V1", "RED", "Fiesta", emptySet(), 10)
        state.arrive(b)

        assertThat(state.paintStream()).hasSize(1)
        assertThat(state.paintStream()[0].id).isEqualTo("V1")
        assertThat(state.lane("L1")!!.isEmpty()).isTrue()
    }

    @Test
    @DisplayName("PbsState: unknown lane id throws IllegalArgumentException")
    fun pbsStateUnknownLaneThrows() {
        val state = PbsState.withLanes(listOf(Lane("L1", 4)))
        val b = Body.incoming("V1", "RED", "Fiesta", emptySet(), 1)

        assertThatThrownBy { state.assignToLane(b, "NOPE") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("NOPE")
    }
}
