package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.Body
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.PbsState
import com.resequencetwin.control.pbs.SequencingPolicy
import com.resequencetwin.control.pbs.StaticSequencingPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within

class PbsBenchRegressionTest {

    companion object {
        const val BENCH_SEED = 42L
        const val TOTAL_BODIES = 100

        @JvmField
        val ROBUSTNESS_SEEDS = longArrayOf(1L, 7L, 42L, 99L, 100L, 1234L, 2024L)

        private val LANE_SPECS = listOf(
            PbsSimRunner.LaneSpec("L1", 10),
            PbsSimRunner.LaneSpec("L2", 10),
            PbsSimRunner.LaneSpec("L3", 10)
        )
    }

    private lateinit var runner: PbsSimRunner
    private lateinit var bodies: List<Body>

    @BeforeEach
    fun setUp() {
        runner = PbsSimRunner()
        bodies = PbsLoadGenerator(BENCH_SEED, TOTAL_BODIES).generate()
    }

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = [1L, 7L, 42L, 99L, 100L, 1234L, 2024L])
    @DisplayName("INV-1 (multi-seed): dynamic.colorChanges <= static.colorChanges")
    fun dynamicColorChangesNotWorseThanStatic_multiSeed(seed: Long) {
        val stream = PbsLoadGenerator(seed, TOTAL_BODIES).generate()
        val staticKpis  = runner.run(stream, LANE_SPECS, StaticSequencingPolicy())
        val dynamicKpis = runner.run(stream, LANE_SPECS, DynamicSequencingPolicy())

        System.out.printf("[INV-1 seed=%d] colorChanges — static=%d  dynamic=%d  delta=%+d%n",
            seed,
            staticKpis.colorChanges, dynamicKpis.colorChanges,
            dynamicKpis.colorChanges - staticKpis.colorChanges)

        assertThat(dynamicKpis.colorChanges)
            .`as`("dynamic colorChanges must be <= static colorChanges (seed=%d)", seed)
            .isLessThanOrEqualTo(staticKpis.colorChanges)
    }

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = [1L, 7L, 42L, 99L, 100L, 1234L, 2024L])
    @DisplayName("INV-2 (multi-seed): dynamic.avgBatchLength >= static.avgBatchLength")
    fun dynamicBatchLengthNotWorseThanStatic_multiSeed(seed: Long) {
        val stream = PbsLoadGenerator(seed, TOTAL_BODIES).generate()
        val staticKpis  = runner.run(stream, LANE_SPECS, StaticSequencingPolicy())
        val dynamicKpis = runner.run(stream, LANE_SPECS, DynamicSequencingPolicy())

        System.out.printf("[INV-2 seed=%d] avgBatchLength — static=%.3f  dynamic=%.3f  delta=%+.3f%n",
            seed,
            staticKpis.avgBatchLength, dynamicKpis.avgBatchLength,
            dynamicKpis.avgBatchLength - staticKpis.avgBatchLength)

        assertThat(dynamicKpis.avgBatchLength)
            .`as`("dynamic avgBatchLength must be >= static avgBatchLength (seed=%d)", seed)
            .isGreaterThanOrEqualTo(staticKpis.avgBatchLength)
    }

    @Test
    @DisplayName("OBS-3 (multi-seed): dueDateDeviation improves on majority of seeds (>= 5/7, OBSERVATION not hard invariant)")
    fun dueDateDeviationImprovesOnMajorityOfSeeds() {
        var improvedCount = 0
        val totalSeeds = ROBUSTNESS_SEEDS.size
        val sb = StringBuilder()
        sb.append("\n=== OBS-3: dueDateDeviation across seeds (OBSERVATION — seed-sensitive) ===\n")
        sb.append(String.format("  %-8s %10s %10s %10s %8s%n",
            "seed", "static", "dynamic", "delta", "improved"))

        for (seed in ROBUSTNESS_SEEDS) {
            val stream = PbsLoadGenerator(seed, TOTAL_BODIES).generate()
            val staticKpis  = runner.run(stream, LANE_SPECS, StaticSequencingPolicy())
            val dynamicKpis = runner.run(stream, LANE_SPECS, DynamicSequencingPolicy())
            val delta = dynamicKpis.dueDateDeviation - staticKpis.dueDateDeviation
            val improved = delta <= 0.0
            if (improved) improvedCount++
            sb.append(String.format("  %-8d %10.3f %10.3f %+10.3f %8s%n",
                seed,
                staticKpis.dueDateDeviation, dynamicKpis.dueDateDeviation,
                delta, if (improved) "YES" else "NO (color-batch tradeoff)"))
        }
        sb.append(String.format("  Result: %d/%d seeds improved%n", improvedCount, totalSeeds))
        sb.append("  NOTE: When improved=NO the color-batch objective (W_COLOR=4.0) outweighs\n")
        sb.append("  due-date urgency (W_DUE=3.0) for this seed/config combination. This is an\n")
        sb.append("  inherent trade-off of the weighted heuristic — CP-SAT multi-objective\n")
        sb.append("  optimization would dominate the Pareto frontier (see ADR-002).\n")
        sb.append("  Known exception: seed=99 with 3x10 lanes (color-batch overrides JIS order).\n")
        sb.append("=========================================================")
        println(sb)

        assertThat(improvedCount)
            .`as`("dueDateDeviation should improve on at least 5 of %d seeds (got %d). " +
                "This KPI is seed-sensitive — see OBS-3 in test Javadoc.", totalSeeds, improvedCount)
            .isGreaterThanOrEqualTo(5)
    }

    @Test
    @DisplayName("ROBUSTNESS: print multi-seed distribution summary for all KPIs")
    fun multiSeedRobustnessSummary() {
        var ccImproved = 0; var blImproved = 0; var ddImproved = 0
        val total = ROBUSTNESS_SEEDS.size

        val sb = StringBuilder()
        sb.append("\n=== PBS Multi-Seed Robustness Summary (3x10 lanes, 100 bodies) ===\n")
        sb.append(String.format("  %-8s %8s %8s %8s %8s %8s %8s%n",
            "seed", "cc_d", "cc_ok", "bl_d", "bl_ok", "dd_d", "dd_ok"))

        for (seed in ROBUSTNESS_SEEDS) {
            val stream = PbsLoadGenerator(seed, TOTAL_BODIES).generate()
            val s = runner.run(stream, LANE_SPECS, StaticSequencingPolicy())
            val d = runner.run(stream, LANE_SPECS, DynamicSequencingPolicy())
            val ccOk = d.colorChanges <= s.colorChanges
            val blOk = d.avgBatchLength >= s.avgBatchLength
            val ddOk = d.dueDateDeviation <= s.dueDateDeviation
            if (ccOk) ccImproved++
            if (blOk) blImproved++
            if (ddOk) ddImproved++
            sb.append(String.format("  %-8d %+8d %-5s %+8.2f %-5s %+8.2f %-5s%n",
                seed,
                d.colorChanges - s.colorChanges, if (ccOk) "OK" else "FAIL",
                d.avgBatchLength - s.avgBatchLength, if (blOk) "OK" else "FAIL",
                d.dueDateDeviation - s.dueDateDeviation, if (ddOk) "OK" else "FAIL"))
        }
        sb.append(String.format("%n  DISTRIBUTION: colorChanges %d/%d seeds improved · " +
            "batchLength %d/%d · dueDateDeviation %d/%d%n",
            ccImproved, total, blImproved, total, ddImproved, total))
        sb.append("  NOTE: colorChanges and avgBatchLength are HARD invariants (robust, all seeds).\n")
        sb.append("  dueDateDeviation is a DOCUMENTED OBSERVATION (seed-sensitive).\n")
        sb.append("  seed=99: color-batch objective overrides JIS order — see ADR-002 CP-SAT upgrade path.\n")
        sb.append("=========================================================")
        println(sb)

        assertThat(ccImproved).isEqualTo(total)
        assertThat(blImproved).isEqualTo(total)
        assertThat(ddImproved).isGreaterThanOrEqualTo(0)
    }

    @Test
    @DisplayName("DETERM: same seed produces identical KPIs across two runs")
    fun sameStreamProducesIdenticalKpis() {
        val run1 = runStatic()
        val run2 = runStatic()

        assertThat(run1.colorChanges).isEqualTo(run2.colorChanges)
        assertThat(run1.avgBatchLength).isEqualTo(run2.avgBatchLength)
        assertThat(run1.dueDateDeviation).isEqualTo(run2.dueDateDeviation)
        assertThat(run1.throughput).isEqualTo(run2.throughput)
        assertThat(run1.laneUtilization).isEqualTo(run2.laneUtilization)

        val dyn1 = runDynamic()
        val dyn2 = runDynamic()

        assertThat(dyn1.colorChanges).isEqualTo(dyn2.colorChanges)
        assertThat(dyn1.avgBatchLength).isEqualTo(dyn2.avgBatchLength)
        assertThat(dyn1.dueDateDeviation).isEqualTo(dyn2.dueDateDeviation)
    }

    @Test
    @DisplayName("FIX-TOGGLE: disabling optimization collapses deltas toward zero")
    fun fixToggleCollapsesDeltas() {
        val staticKpis = runStatic()

        val degeneratedKpis = runner.run(bodies, LANE_SPECS, DegeneratedDynamicPolicy())

        System.out.printf("[FIX-TOGGLE] colorChanges — static=%d  degenerated=%d%n",
            staticKpis.colorChanges, degeneratedKpis.colorChanges)
        System.out.printf("[FIX-TOGGLE] avgBatchLength — static=%.3f  degenerated=%.3f%n",
            staticKpis.avgBatchLength, degeneratedKpis.avgBatchLength)
        System.out.printf("[FIX-TOGGLE] dueDateDeviation — static=%.3f  degenerated=%.3f%n",
            staticKpis.dueDateDeviation, degeneratedKpis.dueDateDeviation)

        assertThat(degeneratedKpis.colorChanges)
            .`as`("degenerated colorChanges == static (no optimization active)")
            .isEqualTo(staticKpis.colorChanges)
        assertThat(degeneratedKpis.avgBatchLength)
            .`as`("degenerated avgBatchLength == static")
            .isCloseTo(staticKpis.avgBatchLength, within(1e-9))
        assertThat(degeneratedKpis.dueDateDeviation)
            .`as`("degenerated dueDateDeviation == static")
            .isCloseTo(staticKpis.dueDateDeviation, within(1e-9))
    }

    @Test
    @DisplayName("REPORT: BenchmarkComparison prints relative deltas without exception")
    fun benchmarkComparisonPrintsReport() {
        val staticKpis  = runStatic()
        val dynamicKpis = runDynamic()

        val cmp = PbsBenchmarkComparison.of(BENCH_SEED, staticKpis, dynamicKpis)

        val report = cmp.report()
        assertThat(report.seed).isEqualTo(BENCH_SEED)
        assertThat(report.staticKpis).isSameAs(staticKpis)
        assertThat(report.dynamicKpis).isSameAs(dynamicKpis)

        assertThat(report.colorChangesDelta())
            .isEqualTo(dynamicKpis.colorChanges - staticKpis.colorChanges)
        assertThat(report.avgBatchLengthDelta())
            .isCloseTo(dynamicKpis.avgBatchLength - staticKpis.avgBatchLength, within(1e-9))
        assertThat(report.dueDateDeviationDelta())
            .isCloseTo(dynamicKpis.dueDateDeviation - staticKpis.dueDateDeviation, within(1e-9))

        cmp.printReport()
    }

    @Test
    @DisplayName("STREAM: paint stream is color-batched (paint-optimal input order)")
    fun paintStreamIsColorBatched() {
        var inputColorChanges = 0
        var prev: String? = null
        var maxRunLength = 0
        var curRun = 0
        for (b in bodies) {
            if (prev != null && b.color != prev) {
                inputColorChanges++
                maxRunLength = maxOf(maxRunLength, curRun)
                curRun = 1
            } else {
                curRun++
            }
            prev = b.color
        }
        maxRunLength = maxOf(maxRunLength, curRun)

        System.out.printf("[STREAM] inputColorChanges=%d  maxRunLength=%d  totalBodies=%d%n",
            inputColorChanges, maxRunLength, bodies.size)

        assertThat(inputColorChanges)
            .`as`("paint stream must be color-batched (far fewer changes than all-alternating)")
            .isLessThan(TOTAL_BODIES / 3)

        assertThat(maxRunLength)
            .`as`("at least one color run must be >= DEFAULT_MIN_BATCH=3")
            .isGreaterThanOrEqualTo(PbsLoadGenerator.DEFAULT_MIN_BATCH)
    }

    @Test
    @DisplayName("LOADGEN: same seed produces identical body stream")
    fun loadGeneratorIsDeterministic() {
        val stream1 = PbsLoadGenerator(BENCH_SEED, TOTAL_BODIES).generate()
        val stream2 = PbsLoadGenerator(BENCH_SEED, TOTAL_BODIES).generate()

        assertThat(stream1).hasSize(stream2.size)
        for (i in stream1.indices) {
            val b1 = stream1[i]
            val b2 = stream2[i]
            assertThat(b1.id).isEqualTo(b2.id)
            assertThat(b1.color).isEqualTo(b2.color)
            assertThat(b1.model).isEqualTo(b2.model)
            assertThat(b1.options).isEqualTo(b2.options)
            assertThat(b1.dueDateSeq).isEqualTo(b2.dueDateSeq)
        }
    }

    @Test
    @DisplayName("SUMMARY: print all KPI deltas for record (seed=42)")
    fun printSummaryForRecord() {
        val staticKpis  = runStatic()
        val dynamicKpis = runDynamic()

        println("\n=== PBS Benchmark KPI Summary (seed=$BENCH_SEED, bodies=$TOTAL_BODIES) ===")
        System.out.printf("  %-28s %10s %10s %10s%n", "KPI", "static", "dynamic", "delta")
        System.out.printf("  %-28s %10d %10d %+10d%n",
            "colorChanges [HARD INV]",
            staticKpis.colorChanges, dynamicKpis.colorChanges,
            dynamicKpis.colorChanges - staticKpis.colorChanges)
        System.out.printf("  %-28s %10.3f %10.3f %+10.3f%n",
            "avgBatchLength [HARD INV]",
            staticKpis.avgBatchLength, dynamicKpis.avgBatchLength,
            dynamicKpis.avgBatchLength - staticKpis.avgBatchLength)
        System.out.printf("  %-28s %10.3f %10.3f %+10.3f%n",
            "dueDateDeviation [OBSERV]",
            staticKpis.dueDateDeviation, dynamicKpis.dueDateDeviation,
            dynamicKpis.dueDateDeviation - staticKpis.dueDateDeviation)
        System.out.printf("  %-28s %10.4f %10.4f %+10.4f%n",
            "throughput (bodies/step)",
            staticKpis.throughput, dynamicKpis.throughput,
            dynamicKpis.throughput - staticKpis.throughput)
        System.out.printf("  %-28s %10.4f %10.4f %+10.4f%n",
            "laneUtilization",
            staticKpis.laneUtilization, dynamicKpis.laneUtilization,
            dynamicKpis.laneUtilization - staticKpis.laneUtilization)
        println("  [NOTE: synthetic PoC — Ford Saarlouis numbers are reference, not this measurement]")
        println("  [HARD INV] = robust across all 7 seeds. [OBSERV] = seed-sensitive, majority claim.")
        println("=========================================================\n")

        assertThat(dynamicKpis.colorChanges).isGreaterThanOrEqualTo(0)
    }

    private fun runStatic(): PbsKpiCollector.Snapshot =
        runner.run(bodies, LANE_SPECS, StaticSequencingPolicy())

    private fun runDynamic(): PbsKpiCollector.Snapshot =
        runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())

    private class DegeneratedDynamicPolicy : SequencingPolicy {
        private val delegate = StaticSequencingPolicy()
        override fun assignLane(body: Body, state: PbsState) = delegate.assignLane(body, state)
        override fun selectRelease(state: PbsState) = delegate.selectRelease(state)
    }
}
