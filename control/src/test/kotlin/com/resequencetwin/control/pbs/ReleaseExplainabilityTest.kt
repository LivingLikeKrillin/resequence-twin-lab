package com.resequencetwin.control.pbs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within

class ReleaseExplainabilityTest {

    private lateinit var policy: DynamicSequencingPolicy

    @BeforeEach
    fun setUp() {
        policy = DynamicSequencingPolicy()
    }

    @Test
    @DisplayName("explainRelease: overdue front yields OVERDUE_OVERRIDE reason and overdue candidate")
    fun explainsOverdueOverride() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        for (i in 0 until 5) {
            state.assignToLane(Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1), "L1")
            policy.selectRelease(state)
        }

        state.assignToLane(incoming("OVERDUE", "GREEN", 2), "L1")
        state.assignToLane(incoming("FAR-RED", "RED", 20), "L2")

        val explanation = policy.explainRelease(state)

        assertThat(explanation.reason).isEqualTo(ReleaseReason.OVERDUE_OVERRIDE)
        assertThat(explanation.chosenBodyId).isEqualTo("OVERDUE")
        assertThat(explanation.chosenLaneId).isEqualTo("L1")
        assertThat(explanation.candidates).hasSize(2)

        val overdueCandidate = explanation.candidates.stream()
            .filter { c -> c.bodyId == "OVERDUE" }.findFirst().orElseThrow()
        assertThat(overdueCandidate.overdue).isTrue()

        val farCandidate = explanation.candidates.stream()
            .filter { c -> c.bodyId == "FAR-RED" }.findFirst().orElseThrow()
        assertThat(farCandidate.overdue).isFalse()
    }

    @Test
    @DisplayName("explainRelease: no overdue front yields HIGHEST_SCORE reason and top weighted total")
    fun explainsHighestScore() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        for (i in 0 until 3) {
            state.assignToLane(Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1), "L1")
            policy.selectRelease(state)
        }

        state.assignToLane(incoming("RED-10", "RED", 10), "L1")
        state.assignToLane(incoming("BLUE-8", "BLUE", 8), "L2")

        val explanation = policy.explainRelease(state)

        assertThat(explanation.reason).isEqualTo(ReleaseReason.HIGHEST_SCORE)
        assertThat(explanation.chosenBodyId).isEqualTo("RED-10")
        assertThat(explanation.chosenLaneId).isEqualTo("L1")

        val chosen = explanation.candidates.stream()
            .filter { c -> c.bodyId == "RED-10" }.findFirst().orElseThrow()
        val maxTotal = explanation.candidates.stream()
            .mapToDouble { c -> c.weightedTotal }.max().orElseThrow()
        assertThat(chosen.weightedTotal).isEqualTo(maxTotal)
    }

    @Test
    @DisplayName("explainRelease: candidate component bonuses match the scoring formula")
    fun candidateBreakdownMatchesFormula() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        for (i in 0 until 3) {
            state.assignToLane(Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1), "L1")
            policy.selectRelease(state)
        }

        state.assignToLane(Body.incoming("L1-BODY", "RED", "Focus", setOf("SUNROOF"), 10), "L1")
        state.assignToLane(Body.incoming("L2-BODY", "BLUE", "Focus", emptySet(), 30), "L2")

        val explanation = policy.explainRelease(state)

        val c1 = explanation.candidates.stream()
            .filter { c -> c.bodyId == "L1-BODY" }.findFirst().orElseThrow()
        assertThat(c1.color).isEqualTo("RED")
        assertThat(c1.hasOptions).isTrue()
        assertThat(c1.colorBonus).isEqualTo(1.0)
        assertThat(c1.optionLevelingBonus).isEqualTo(1.0)
        assertThat(c1.dueDateBonus).isCloseTo(1.0 - 7.0 / 20.0, within(1e-9))
        assertThat(c1.weightedTotal).isCloseTo(7.95, within(1e-9))
        assertThat(c1.overdue).isFalse()

        val c2 = explanation.candidates.stream()
            .filter { c -> c.bodyId == "L2-BODY" }.findFirst().orElseThrow()
        assertThat(c2.color).isEqualTo("BLUE")
        assertThat(c2.hasOptions).isFalse()
        assertThat(c2.colorBonus).isEqualTo(0.0)
        assertThat(c2.optionLevelingBonus).isEqualTo(1.0)
        assertThat(c2.dueDateBonus).isEqualTo(0.0)
        assertThat(c2.weightedTotal).isCloseTo(2.0, within(1e-9))
    }

    @Test
    @DisplayName("explainRelease: does not mutate PbsState — subsequent selectRelease matches control")
    fun explainReleaseDoesNotMutateState() {
        val expState = scenarioState()
        val expPolicy = primedPolicy(expState)
        state2Candidates(expState)

        expPolicy.explainRelease(expState)
        expPolicy.explainRelease(expState)
        val expReleased = expPolicy.selectRelease(expState)

        val ctrlState = scenarioState()
        val ctrlPolicy = primedPolicy(ctrlState)
        state2Candidates(ctrlState)
        val ctrlReleased = ctrlPolicy.selectRelease(ctrlState)

        assertThat(expReleased.id).isEqualTo(ctrlReleased.id)
        assertThat(expState.assemblyOut().size).isEqualTo(ctrlState.assemblyOut().size)
    }

    @Test
    @DisplayName("explainRelease: does not mutate policy internal state — repeated calls identical")
    fun explainReleaseDoesNotMutatePolicyState() {
        val state = scenarioState()
        primedPolicyInto(policy, state)
        state2Candidates(state)

        val first = policy.explainRelease(state)
        val second = policy.explainRelease(state)

        assertThat(second.chosenBodyId).isEqualTo(first.chosenBodyId)
        assertThat(second.reason).isEqualTo(first.reason)
        assertThat(second.candidates).hasSameSizeAs(first.candidates)
        for (i in first.candidates.indices) {
            val a = first.candidates[i]
            val b = second.candidates[i]
            assertThat(b.weightedTotal).isCloseTo(a.weightedTotal, within(1e-12))
            assertThat(b.colorBonus).isEqualTo(a.colorBonus)
            assertThat(b.optionLevelingBonus).isEqualTo(a.optionLevelingBonus)
            assertThat(b.dueDateBonus).isEqualTo(a.dueDateBonus)
        }
    }

    @Test
    @DisplayName("explainRelease OVERDUE_OVERRIDE: chosen body id matches body actually released by selectRelease")
    fun overdueOverrideChosenBodyMatchesSelectRelease() {
        val explainState = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        val explainPolicy = DynamicSequencingPolicy()
        for (i in 0 until 5) {
            explainState.assignToLane(Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1), "L1")
            explainPolicy.selectRelease(explainState)
        }

        val selectState = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        val selectPolicy = DynamicSequencingPolicy()
        for (i in 0 until 5) {
            selectState.assignToLane(Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1), "L1")
            selectPolicy.selectRelease(selectState)
        }

        explainState.assignToLane(incoming("OVERDUE-BODY", "GREEN", 2), "L1")
        explainState.assignToLane(incoming("FAR-BODY", "RED", 20), "L2")
        selectState.assignToLane(incoming("OVERDUE-BODY", "GREEN", 2), "L1")
        selectState.assignToLane(incoming("FAR-BODY", "RED", 20), "L2")

        val explanation = explainPolicy.explainRelease(explainState)
        val actualReleased = selectPolicy.selectRelease(selectState)

        assertThat(explanation.reason).isEqualTo(ReleaseReason.OVERDUE_OVERRIDE)
        assertThat(explanation.chosenBodyId).isEqualTo(actualReleased.id)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun scenarioState(): PbsState =
        PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

    private fun primedPolicy(state: PbsState): DynamicSequencingPolicy {
        val p = DynamicSequencingPolicy()
        primedPolicyInto(p, state)
        return p
    }

    private fun primedPolicyInto(p: DynamicSequencingPolicy, state: PbsState) {
        for (i in 0 until 3) {
            state.assignToLane(Body.incoming("PRIOR-$i", "RED", "Focus", emptySet(), i + 1), "L1")
            p.selectRelease(state)
        }
    }

    private fun state2Candidates(state: PbsState) {
        state.assignToLane(incoming("RED-10", "RED", 10), "L1")
        state.assignToLane(incoming("BLUE-8", "BLUE", 8), "L2")
    }

    private fun incoming(id: String, color: String, due: Int): Body =
        Body.incoming(id, color, "Focus", emptySet(), due)
}
