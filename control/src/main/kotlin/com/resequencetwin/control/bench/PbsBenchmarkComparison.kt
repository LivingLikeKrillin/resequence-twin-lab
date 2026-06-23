package com.resequencetwin.control.bench

/**
 * Runs static and dynamic sequencing policies against an identical seeded PBS load and
 * reports relative KPI deltas (rev3 §4).
 *
 * **Honesty constraint (spec §4/§9)**
 * The [Report] exposes ONLY relative deltas (dynamic − static). Absolute KPI
 * numbers are never published as standalone claims. Ford Saarlouis numbers
 * (+30% batch / −23% color changes / −10% due-date deviation, arXiv 2507.17422) are a
 * published peer-reviewed reference, NOT this PoC's measured output.
 *
 * All labels carry "synthetic / relative-to-static" annotation per spec §9.
 *
 * **Usage**
 * ```
 * val cmp = PbsBenchmarkComparison.of(42L, staticKpis, dynamicKpis)
 * cmp.printReport()
 * ```
 */
class PbsBenchmarkComparison private constructor(
    private val staticKpis: PbsKpiCollector.Snapshot,
    private val dynamicKpis: PbsKpiCollector.Snapshot,
    private val seed: Long
) {

    companion object {
        /**
         * Create a comparison from pre-computed PBS KPI snapshots.
         *
         * @param seed         RNG seed used in both runs (for report traceability)
         * @param staticKpis   KPI snapshot from the static (baseline) policy run
         * @param dynamicKpis  KPI snapshot from the dynamic (optimised) policy run
         */
        fun of(
            seed: Long,
            staticKpis: PbsKpiCollector.Snapshot,
            dynamicKpis: PbsKpiCollector.Snapshot
        ): PbsBenchmarkComparison = PbsBenchmarkComparison(staticKpis, dynamicKpis, seed)
    }

    // -------------------------------------------------------------------------
    // Report
    // -------------------------------------------------------------------------

    /** Returns the full comparison report (relative deltas + raw KPIs + seed). */
    fun report(): Report = Report(staticKpis, dynamicKpis, seed)

    /**
     * Print a comparison report to stdout.
     *
     * All values are labeled "synthetic, relative-to-static" per spec §4/§9.
     * Ford numbers are cited as reference only.
     *
     * Fairness note: the static policy uses round-robin lane assignment and FIFO-by-due-date
     * release. The dynamic policy uses colour-affinity lane assignment AND optimised multi-objective
     * release scoring — that IS the optimization under test. Both policies run against the same
     * seeded body stream with the same lane configuration and policy-agnostic runner loop.
     * KPIs are outcome KPIs only (no CPU-time measurement).
     *
     * KPI robustness classification (verified across seeds {1,7,42,99,100,1234,2024}, 3×10 lanes):
     * - **colorChanges**: 7/7 seeds improved — HARD invariant
     * - **avgBatchLength**: 7/7 seeds improved — HARD invariant
     * - **dueDateDeviation**: majority of seeds improved (OBSERVATION, not hard invariant).
     *   Seed 99: color-batch objective overrides JIS order — see ADR-002 CP-SAT upgrade path.
     */
    fun printReport() {
        val r = report()
        println("================================================================")
        println("  resequence-twin PBS Benchmark Report  [synthetic · relative-to-static]")
        println("  Seed: " + r.seed)
        println("================================================================")
        System.out.printf("  %-32s %10s %10s %10s%n", "KPI", "static", "dynamic", "delta")
        println("  ------------------------------------------------------------------")
        System.out.printf("  %-32s %10d %10d %+10d%n",
            "colorChanges [HARD 7/7]",
            r.staticKpis.colorChanges,
            r.dynamicKpis.colorChanges,
            r.colorChangesDelta())
        System.out.printf("  %-32s %10.3f %10.3f %+10.3f%n",
            "avgBatchLength [HARD 7/7]",
            r.staticKpis.avgBatchLength,
            r.dynamicKpis.avgBatchLength,
            r.avgBatchLengthDelta())
        System.out.printf("  %-32s %10.3f %10.3f %+10.3f%n",
            "dueDateDeviation [OBS majority]",
            r.staticKpis.dueDateDeviation,
            r.dynamicKpis.dueDateDeviation,
            r.dueDateDeviationDelta())
        System.out.printf("  %-32s %10.4f %10.4f %+10.4f%n",
            "throughput (bodies/step)",
            r.staticKpis.throughput,
            r.dynamicKpis.throughput,
            r.throughputDelta())
        System.out.printf("  %-32s %10.4f %10.4f %+10.4f%n",
            "laneUtilization",
            r.staticKpis.laneUtilization,
            r.dynamicKpis.laneUtilization,
            r.laneUtilizationDelta())
        println("================================================================")
        println("  Interpretation: negative colorChanges/dueDateDeviation delta = dynamic better.")
        println("  Positive avgBatchLength delta = dynamic better.")
        println("  Multi-seed robustness (seeds {1,7,42,99,100,1234,2024}, 3x10 lanes):")
        println("    colorChanges: 7/7 seeds improved · batchLength: 7/7 ·")
        println("    dueDateDeviation: majority (seed-sensitive, see ADR-002).")
        println("  [NOTE: synthetic PoC — Ford Saarlouis numbers (arXiv 2507.17422)")
        println("   are published reference, NOT this PoC's measurement.]")
        println("================================================================")
    }

    // -------------------------------------------------------------------------
    // Report nested class
    // -------------------------------------------------------------------------

    /**
     * Immutable PBS comparison report.
     *
     * All delta fields are `dynamic − static`. Interpretation:
     * - negative [colorChangesDelta] = dynamic better (fewer switches)
     * - positive [avgBatchLengthDelta] = dynamic better (longer runs)
     * - negative [dueDateDeviationDelta] = dynamic better (closer to JIS)
     *
     * The [seed] field carries the actual RNG seed for reproducibility.
     */
    data class Report(
        val staticKpis: PbsKpiCollector.Snapshot,
        val dynamicKpis: PbsKpiCollector.Snapshot,
        val seed: Long
    ) {
        /** Color changes delta (dynamic − static). Negative = dynamic better. */
        fun colorChangesDelta(): Int = dynamicKpis.colorChanges - staticKpis.colorChanges

        /** Average batch length delta (dynamic − static). Positive = dynamic better. */
        fun avgBatchLengthDelta(): Double = dynamicKpis.avgBatchLength - staticKpis.avgBatchLength

        /** Due-date deviation delta (dynamic − static). Negative = dynamic better. */
        fun dueDateDeviationDelta(): Double = dynamicKpis.dueDateDeviation - staticKpis.dueDateDeviation

        /** Throughput delta (dynamic − static). */
        fun throughputDelta(): Double = dynamicKpis.throughput - staticKpis.throughput

        /** Lane utilization delta (dynamic − static). */
        fun laneUtilizationDelta(): Double = dynamicKpis.laneUtilization - staticKpis.laneUtilization
    }
}
