package com.resequencetwin.control.pbs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class DynamicSequencingPolicyTest {

    private lateinit var policy: DynamicSequencingPolicy

    @BeforeEach
    fun setUp() {
        policy = DynamicSequencingPolicy()
    }

    @Test
    @DisplayName("extendsColorBatchOnRelease: dynamic prefers same-colour front when due-date window allows")
    fun extendsColorBatchOnRelease() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        for (i in 0 until 3) {
            val prior = Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i)
            state.assignToLane(prior, "L1")
            policy.selectRelease(state)
        }

        val redBody  = incoming("RED-10",  "RED",  10)
        val blueBody = incoming("BLUE-8",  "BLUE",  8)
        state.assignToLane(redBody,  "L1")
        state.assignToLane(blueBody, "L2")

        val released = policy.selectRelease(state)
        assertThat(released.color).isEqualTo("RED")
        assertThat(released.id).isEqualTo("RED-10")

        val staticPolicy = StaticSequencingPolicy()
        val staticState = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        staticState.assignToLane(incoming("RED-10s",  "RED",  10), "L1")
        staticState.assignToLane(incoming("BLUE-8s",  "BLUE",  8), "L2")
        val staticReleased = staticPolicy.selectRelease(staticState)
        assertThat(staticReleased.color).isEqualTo("BLUE")
    }

    @Test
    @DisplayName("respectsDueDateWindow: overdue body is released before colour-batch extension")
    fun respectsDueDateWindow() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        for (i in 0 until 5) {
            val prior = Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1)
            state.assignToLane(prior, "L1")
            policy.selectRelease(state)
        }

        val overdueBody = incoming("OVERDUE", "GREEN", 2)
        val farRedBody  = incoming("FAR-RED", "RED",   20)

        state.assignToLane(overdueBody, "L1")
        state.assignToLane(farRedBody,  "L2")

        val released = policy.selectRelease(state)
        assertThat(released.id).isEqualTo("OVERDUE")
        assertThat(released.color).isEqualTo("GREEN")
    }

    @Test
    @DisplayName("urgencyWindow: body in DUE_DATE_URGENCY_WINDOW gets bonus even across colour change")
    fun urgencyWindowBonusOverridesColorTie() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        val urgentBody = incoming("URGENT", "BLUE",  2)
        val farBody    = incoming("FAR",    "RED",  20)
        state.assignToLane(urgentBody, "L1")
        state.assignToLane(farBody,    "L2")

        val released = policy.selectRelease(state)
        assertThat(released.id).isEqualTo("URGENT")
    }

    @Test
    @DisplayName("optionLeveling: option body penalised when recent window is saturated")
    fun optionLevelingAvoidsClustering() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        for (i in 0 until DynamicSequencingPolicy.LEVELING_WINDOW) {
            val optBody = Body.incoming("OPT-$i", "RED", "Focus", setOf("SUNROOF"), i + 1)
            state.assignToLane(optBody, "L1")
            policy.selectRelease(state)
        }

        val optionBody   = Body.incoming("BLUE-OPT",  "BLUE", "Focus", setOf("SUNROOF"), 10)
        val noOptionBody = Body.incoming("RED-PLAIN",  "RED",  "Focus", emptySet(),       8)
        state.assignToLane(optionBody,   "L1")
        state.assignToLane(noOptionBody, "L2")

        val released = policy.selectRelease(state)
        assertThat(released.id).isEqualTo("RED-PLAIN")
    }

    @Test
    @DisplayName("assignLane: prefers lane with existing same-colour bodies (colour affinity)")
    fun assignLaneColorAffinity() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        val redSeed = incoming("SEED", "RED", 1)
        state.assignToLane(redSeed, "L1")

        val newRed = incoming("NEW-RED", "RED", 2)
        val chosen = policy.assignLane(newRed, state)
        assertThat(chosen).isEqualTo("L1")

        val newBlue = incoming("NEW-BLUE", "BLUE", 3)
        val chosenBlue = policy.assignLane(newBlue, state)
        assertThat(chosenBlue).isEqualTo("L2")
    }

    @Test
    @DisplayName("assignLane: falls back to least-loaded lane when no colour-matching lane exists")
    fun assignLaneFallsBackToLeastLoaded() {
        val state = PbsState.withLanes(listOf(
            Lane("L1", 8),
            Lane("L2", 8),
            Lane("L3", 8)
        ))
        state.assignToLane(incoming("A", "RED", 1), "L1")
        state.assignToLane(incoming("B", "RED", 2), "L1")
        state.assignToLane(incoming("C", "RED", 3), "L2")

        val chosen = policy.assignLane(incoming("BLUE1", "BLUE", 4), state)
        assertThat(chosen).isEqualTo("L3")
    }

    @Test
    @DisplayName("assignLane: throws when all lanes full")
    fun assignLaneAllFullThrows() {
        val tiny = PbsState.withLanes(listOf(Lane("L1", 1)))
        tiny.assignToLane(incoming("V1", "RED", 1), "L1")

        assertThatThrownBy { policy.assignLane(incoming("V2", "RED", 2), tiny) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("full")
    }

    @Test
    @DisplayName("DynamicSequencingPolicy implements SequencingPolicy")
    fun implementsInterface() {
        assertThat(policy).isInstanceOf(SequencingPolicy::class.java)
    }

    private fun incoming(id: String, color: String, due: Int): Body =
        Body.incoming(id, color, "Focus", emptySet(), due)
}
