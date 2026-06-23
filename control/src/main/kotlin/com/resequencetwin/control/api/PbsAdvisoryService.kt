package com.resequencetwin.control.api

import com.resequencetwin.control.bench.PbsBenchmarkComparison
import com.resequencetwin.control.bench.PbsKpiCollector
import com.resequencetwin.control.bench.PbsLoadGenerator
import com.resequencetwin.control.bench.PbsSimRunner
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.StaticSequencingPolicy
import com.resequencetwin.control.pbs.ScramblePredictor
import org.springframework.stereotype.Service

/**
 * Benchmark wiring service for the PBS advisory REST surface (rev3 R4b).
 */
@Service
class PbsAdvisoryService {

    companion object {
        /** Default RNG seed. */
        const val DEFAULT_SEED: Long = 42L

        /** Default total bodies. */
        const val DEFAULT_BODIES: Int = 100

        /** Default afterReleases for checkpoint endpoints. */
        const val DEFAULT_AFTER_RELEASES: Int = 15

        /** 3 lanes × capacity 10 — canonical benchmark config. */
        private val DEFAULT_LANE_SPECS: List<PbsSimRunner.LaneSpec> = listOf(
            PbsSimRunner.LaneSpec("L1", 10),
            PbsSimRunner.LaneSpec("L2", 10),
            PbsSimRunner.LaneSpec("L3", 10)
        )
    }

    private val runner = PbsSimRunner()
    private val predictor = ScramblePredictor()

    // -------------------------------------------------------------------------
    // KPI endpoint logic
    // -------------------------------------------------------------------------

    fun kpi(seed: Long, bodies: Int): KpiResponse {
        val stream = PbsLoadGenerator(seed, bodies).generate()
        val staticKpis: PbsKpiCollector.Snapshot = runner.run(stream, DEFAULT_LANE_SPECS, StaticSequencingPolicy())
        val dynamicKpis: PbsKpiCollector.Snapshot = runner.run(stream, DEFAULT_LANE_SPECS, DynamicSequencingPolicy())
        val report = PbsBenchmarkComparison.of(seed, staticKpis, dynamicKpis).report()
        return KpiResponse.from(report)
    }

    // -------------------------------------------------------------------------
    // Explain-release endpoint logic
    // -------------------------------------------------------------------------

    fun explainRelease(seed: Long, bodies: Int, afterReleases: Int): ExplainReleaseResponse {
        val stream = PbsLoadGenerator(seed, bodies).generate()
        val cp = runner.runToCheckpoint(stream, DEFAULT_LANE_SPECS, DynamicSequencingPolicy(), afterReleases)
        val explanation = cp.policy.explainRelease(cp.state)
        return ExplainReleaseResponse.of(seed, afterReleases, cp.state.assemblyOut().size, explanation)
    }

    // -------------------------------------------------------------------------
    // Predict-scramble endpoint logic
    // -------------------------------------------------------------------------

    fun predictScramble(seed: Long, bodies: Int, afterReleases: Int): PredictScrambleResponse {
        val stream = PbsLoadGenerator(seed, bodies).generate()
        val cp = runner.runToCheckpoint(stream, DEFAULT_LANE_SPECS, DynamicSequencingPolicy(), afterReleases)
        val forecast = predictor.forecast(cp.state, cp.policy)
        return PredictScrambleResponse.of(seed, afterReleases, cp.state.assemblyOut().size, forecast)
    }
}
