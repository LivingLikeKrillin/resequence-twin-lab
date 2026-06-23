package com.resequencetwin.control.pbs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class StaticSequencingPolicyTest {

    private lateinit var policy: StaticSequencingPolicy
    private lateinit var state: PbsState

    @BeforeEach
    fun setUp() {
        policy = StaticSequencingPolicy()
        state = PbsState.withLanes(listOf(
            Lane("L1", 8),
            Lane("L2", 8),
            Lane("L3", 8)
        ))
    }

    @Test
    @DisplayName("assignLane: distributes bodies across lanes in strict round-robin order")
    fun assignLaneRoundRobin() {
        val b1 = incoming("V1", "RED",   1)
        val b2 = incoming("V2", "BLUE",  2)
        val b3 = incoming("V3", "GREEN", 3)
        val b4 = incoming("V4", "RED",   4)
        val b5 = incoming("V5", "BLUE",  5)
        val b6 = incoming("V6", "GREEN", 6)

        assertThat(policy.assignLane(b1, state)).isEqualTo("L1")
        state.assignToLane(b1, "L1")

        assertThat(policy.assignLane(b2, state)).isEqualTo("L2")
        state.assignToLane(b2, "L2")

        assertThat(policy.assignLane(b3, state)).isEqualTo("L3")
        state.assignToLane(b3, "L3")

        assertThat(policy.assignLane(b4, state)).isEqualTo("L1")
        state.assignToLane(b4, "L1")

        assertThat(policy.assignLane(b5, state)).isEqualTo("L2")
        state.assignToLane(b5, "L2")

        assertThat(policy.assignLane(b6, state)).isEqualTo("L3")
        state.assignToLane(b6, "L3")
    }

    @Test
    @DisplayName("assignLane: skips full lanes in round-robin")
    fun assignLaneSkipsFullLanes() {
        val twoLane = PbsState.withLanes(listOf(
            Lane("A", 1),
            Lane("B", 4)
        ))
        val p = StaticSequencingPolicy()

        val b1 = incoming("V1", "RED", 1)
        val b2 = incoming("V2", "RED", 2)
        val b3 = incoming("V3", "RED", 3)

        assertThat(p.assignLane(b1, twoLane)).isEqualTo("A")
        twoLane.assignToLane(b1, "A")

        assertThat(p.assignLane(b2, twoLane)).isEqualTo("B")
        twoLane.assignToLane(b2, "B")

        assertThat(p.assignLane(b3, twoLane)).isEqualTo("B")
    }

    @Test
    @DisplayName("assignLane: throws when all lanes are full")
    fun assignLaneAllFullThrows() {
        val tiny = PbsState.withLanes(listOf(Lane("L1", 1)))
        val p = StaticSequencingPolicy()

        tinyAssign(tiny, "L1", incoming("V1", "RED", 1))
        assertThatThrownBy { p.assignLane(incoming("V2", "BLUE", 2), tiny) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("full")
    }

    @Test
    @DisplayName("selectRelease: returns front body from lane with lowest dueDateSeq")
    fun selectReleasePicksLowestDueDate() {
        state.assignToLane(incoming("V5",  "RED",   5), "L1")
        state.assignToLane(incoming("V2",  "BLUE",  2), "L2")
        state.assignToLane(incoming("V8",  "GREEN", 8), "L3")

        val released = policy.selectRelease(state)
        assertThat(released.id).isEqualTo("V2")
    }

    @Test
    @DisplayName("selectRelease: deterministic sequence matches expected order across multiple releases")
    fun selectReleaseDeterministicSequence() {
        state.assignToLane(incoming("A1", "RED",   1), "L1")
        state.assignToLane(incoming("A4", "RED",   4), "L1")
        state.assignToLane(incoming("B3", "BLUE",  3), "L2")
        state.assignToLane(incoming("B6", "BLUE",  6), "L2")
        state.assignToLane(incoming("C2", "GREEN", 2), "L3")
        state.assignToLane(incoming("C5", "GREEN", 5), "L3")

        assertThat(policy.selectRelease(state).id).isEqualTo("A1")
        assertThat(policy.selectRelease(state).id).isEqualTo("C2")
        assertThat(policy.selectRelease(state).id).isEqualTo("B3")
        assertThat(policy.selectRelease(state).id).isEqualTo("A4")
        assertThat(policy.selectRelease(state).id).isEqualTo("C5")
        assertThat(policy.selectRelease(state).id).isEqualTo("B6")

        assertThat(state.assemblyOut()).extracting<String> { it.id }
            .containsExactly("A1", "C2", "B3", "A4", "C5", "B6")
    }

    @Test
    @DisplayName("selectRelease: tie in dueDateSeq broken by lane insertion order")
    fun selectReleaseTieBrokenByLaneOrder() {
        state.assignToLane(incoming("First",  "RED",  5), "L1")
        state.assignToLane(incoming("Second", "BLUE", 5), "L2")

        val released = policy.selectRelease(state)
        assertThat(released.id).isEqualTo("First")
    }

    @Test
    @DisplayName("selectRelease: throws when all lanes are empty")
    fun selectReleaseEmptyThrows() {
        assertThatThrownBy { policy.selectRelease(state) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("empty")
    }

    @Test
    @DisplayName("StaticSequencingPolicy implements SequencingPolicy")
    fun implementsInterface() {
        assertThat(policy).isInstanceOf(SequencingPolicy::class.java)
    }

    private fun incoming(id: String, color: String, due: Int): Body =
        Body.incoming(id, color, "Fiesta", emptySet(), due)

    private fun tinyAssign(s: PbsState, lane: String, b: Body) {
        s.assignToLane(b, lane)
    }
}
