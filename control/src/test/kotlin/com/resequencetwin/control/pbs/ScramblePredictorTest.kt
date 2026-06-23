package com.resequencetwin.control.pbs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within

class ScramblePredictorTest {

    private lateinit var predictor: ScramblePredictor
    private lateinit var policy: DynamicSequencingPolicy

    @BeforeEach
    fun setUp() {
        predictor = ScramblePredictor()
        policy = DynamicSequencingPolicy()
    }

    @Test
    @DisplayName("forecast: same-colour fronts, no overdue, balanced occupancy → LOW")
    fun lowRiskStateIsLow() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        state.assignToLane(incoming("A1", "RED", 50), "L1")
        state.assignToLane(incoming("A2", "RED", 51), "L1")
        state.assignToLane(incoming("B1", "RED", 52), "L2")
        state.assignToLane(incoming("B2", "RED", 53), "L2")

        val forecast = predictor.forecast(state, policy)

        assertThat(forecast.level).isEqualTo(ScrambleLevel.LOW)
        assertThat(forecast.riskScore).isLessThan(ScrambleLevel.MEDIUM_THRESHOLD)
        assertThat(forecast.riskScore).isCloseTo(0.225, within(1e-9))
    }

    @Test
    @DisplayName("forecast: distinct-colour fronts, all overdue, imbalanced occupancy → HIGH")
    fun highRiskStateIsHigh() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        state.assignToLane(incoming("R0", "RED", 0), "L1")
        state.assignToLane(incoming("B0", "BLUE", 0), "L2")
        for (i in 1 until 5) {
            state.assignToLane(incoming("B$i", "BLUE", 0), "L2")
        }

        val forecast = predictor.forecast(state, policy)

        assertThat(forecast.level).isEqualTo(ScrambleLevel.HIGH)
        assertThat(forecast.riskScore).isGreaterThanOrEqualTo(0.65)
    }

    @Test
    @DisplayName("forecast: making a front overdue does not decrease risk score")
    fun overdueAdditionIsMonotonic() {
        val baseline = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        baseline.assignToLane(incoming("A", "RED", 50), "L1")
        baseline.assignToLane(incoming("B", "RED", 51), "L2")
        val baseRisk = predictor.forecast(baseline, policy).riskScore

        val worse = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        worse.assignToLane(incoming("A", "RED", 0), "L1")
        worse.assignToLane(incoming("B", "RED", 51), "L2")
        val worseRisk = predictor.forecast(worse, policy).riskScore

        assertThat(worseRisk).isGreaterThanOrEqualTo(baseRisk)
    }

    @Test
    @DisplayName("forecast: overdue-dominant state → topContributor OVERDUE_RATIO with JIS hint")
    fun overdueDominantTopContributor() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        state.assignToLane(incoming("A", "RED", 0), "L1")
        state.assignToLane(incoming("B", "RED", 0), "L2")

        val forecast = predictor.forecast(state, policy)

        assertThat(forecast.topContributor.name).isEqualTo("OVERDUE_RATIO")
        assertThat(forecast.stabilizationHint).containsIgnoringCase("overdue")
        assertThat(forecast.stabilizationHint).containsIgnoringCase("JIS")
    }

    @Test
    @DisplayName("forecast: fragmentation-dominant state → topContributor COLOR_FRAGMENTATION with colour hint")
    fun colorFragmentationDominantTopContributor() {
        val state = PbsState.withLanes(listOf(
            Lane("L1", 8), Lane("L2", 8), Lane("L3", 8)))
        state.assignToLane(incoming("A", "RED", 50), "L1")
        state.assignToLane(incoming("B", "BLUE", 51), "L2")
        state.assignToLane(incoming("C", "GREEN", 52), "L3")

        val forecast = predictor.forecast(state, policy)

        assertThat(forecast.topContributor.name).isEqualTo("COLOR_FRAGMENTATION")
        assertThat(forecast.stabilizationHint).containsIgnoringCase("color")
    }

    @Test
    @DisplayName("forecast: same state twice → identical forecast")
    fun deterministicForecast() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        state.assignToLane(incoming("A", "RED", 0), "L1")
        state.assignToLane(incoming("B", "BLUE", 12), "L2")
        state.assignToLane(incoming("C", "BLUE", 13), "L2")

        val f1 = predictor.forecast(state, policy)
        val f2 = predictor.forecast(state, policy)

        assertThat(f2.riskScore).isEqualTo(f1.riskScore)
        assertThat(f2.level).isEqualTo(f1.level)
        assertThat(f2.stabilizationHint).isEqualTo(f1.stabilizationHint)
        assertThat(f2.topContributor.name).isEqualTo(f1.topContributor.name)
    }

    @Test
    @DisplayName("forecast: overdue-dominant state → drivingLaneId+drivingBodyId populated on OVERDUE_RATIO, null elsewhere")
    fun structuredDrivingFieldsPopulatedForOverdueDominant() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        state.assignToLane(incoming("A1", "RED", 0), "L1")
        state.assignToLane(incoming("B1", "RED", 0), "L2")

        val forecast = predictor.forecast(state, policy)

        assertThat(forecast.topContributor.name).isEqualTo("OVERDUE_RATIO")

        val overdueFactor = forecast.factors.stream()
            .filter { f -> f.name == "OVERDUE_RATIO" }.findFirst().orElseThrow()
        assertThat(overdueFactor.drivingLaneId).isEqualTo("L1")
        assertThat(overdueFactor.drivingBodyId).isEqualTo("A1")

        val fragFactor = forecast.factors.stream()
            .filter { f -> f.name == "COLOR_FRAGMENTATION" }.findFirst().orElseThrow()
        assertThat(fragFactor.drivingLaneId).isNull()
        assertThat(fragFactor.drivingBodyId).isNull()

        val imbalanceFactor = forecast.factors.stream()
            .filter { f -> f.name == "LANE_IMBALANCE" }.findFirst().orElseThrow()
        assertThat(imbalanceFactor.drivingLaneId).isNull()
        assertThat(imbalanceFactor.drivingBodyId).isNull()
    }

    @Test
    @DisplayName("forecast: no overdue fronts → drivingLaneId and drivingBodyId null on OVERDUE_RATIO")
    fun structuredDrivingFieldsNullWhenNoOverdue() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))
        state.assignToLane(incoming("A1", "RED", 50), "L1")
        state.assignToLane(incoming("B1", "RED", 51), "L2")

        val forecast = predictor.forecast(state, policy)

        val overdueFactor = forecast.factors.stream()
            .filter { f -> f.name == "OVERDUE_RATIO" }.findFirst().orElseThrow()
        assertThat(overdueFactor.drivingLaneId).isNull()
        assertThat(overdueFactor.drivingBodyId).isNull()
    }

    @Test
    @DisplayName("forecast: all lanes empty → riskScore 0, level LOW")
    fun emptyLanesAreLowRisk() {
        val state = PbsState.withLanes(listOf(Lane("L1", 8), Lane("L2", 8)))

        val forecast = predictor.forecast(state, policy)

        assertThat(forecast.riskScore).isCloseTo(0.0, within(1e-9))
        assertThat(forecast.level).isEqualTo(ScrambleLevel.LOW)
    }

    private fun incoming(id: String, color: String, due: Int): Body =
        Body.incoming(id, color, "Focus", emptySet(), due)
}
