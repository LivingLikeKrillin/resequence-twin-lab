package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.StaticSequencingPolicy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("explore")
@Disabled("Exploration sweep — run manually: mvn test -Dgroups=explore -Dtest=PbsConfigExploreTest")
class PbsConfigExploreTest {

    @Test
    fun exploreConfigurations() {
        val seeds       = longArrayOf(42L, 99L, 1234L)
        val bodyCounts  = intArrayOf(100, 200)
        val laneConfigs = arrayOf(intArrayOf(4, 8), intArrayOf(5, 5), intArrayOf(6, 4), intArrayOf(3, 10), intArrayOf(6, 8))

        val runner = PbsSimRunner()

        for (seed in seeds) {
            for (bodies in bodyCounts) {
                for (lc in laneConfigs) {
                    val lanes    = lc[0]
                    val capacity = lc[1]

                    val specs = buildSpecs(lanes, capacity)
                    val stream = PbsLoadGenerator(seed, bodies).generate()

                    val s = runner.run(stream, specs, StaticSequencingPolicy())
                    val d = runner.run(stream, specs, DynamicSequencingPolicy())

                    val ccDelta = d.colorChanges    - s.colorChanges
                    val blDelta = d.avgBatchLength  - s.avgBatchLength
                    val ddDelta = d.dueDateDeviation - s.dueDateDeviation

                    val ccOk = ccDelta <= 0
                    val blOk = blDelta >= 0
                    val ddOk = ddDelta <= 0
                    val result = if (ccOk && blOk && ddOk) "ALL_PASS"
                        else String.format("cc=%s,bl=%s,dd=%s",
                            if (ccOk) "OK" else "FAIL",
                            if (blOk) "OK" else "FAIL",
                            if (ddOk) "OK" else "FAIL")

                    System.out.printf("seed=%-8d bodies=%-5d lanes=%dx%-3d| cc=%+3d bl=%+5.2f dd=%+5.2f | %s%n",
                        seed, bodies, lanes, capacity,
                        ccDelta, blDelta, ddDelta, result)
                }
            }
        }
    }

    @Test
    @Disabled("Extended sweep — run manually when verifying the full 7-seed distribution")
    fun exploreFullSeedSet() {
        val seeds     = PbsBenchRegressionTest.ROBUSTNESS_SEEDS
        val bodies    = 100

        val runner = PbsSimRunner()

        println("\n=== Full 7-seed robustness sweep (3x10 lanes, 100 bodies) ===")
        System.out.printf("  %-8s %8s %8s %8s %8s %8s %8s%n",
            "seed", "cc_d", "cc_ok", "bl_d", "bl_ok", "dd_d", "dd_ok")

        var ccTotal = 0; var blTotal = 0; var ddTotal = 0
        for (seed in seeds) {
            val specs = buildSpecs(3, 10)
            val stream = PbsLoadGenerator(seed, bodies).generate()
            val s = runner.run(stream, specs, StaticSequencingPolicy())
            val d = runner.run(stream, specs, DynamicSequencingPolicy())

            val ccOk = d.colorChanges <= s.colorChanges
            val blOk = d.avgBatchLength >= s.avgBatchLength
            val ddOk = d.dueDateDeviation <= s.dueDateDeviation
            if (ccOk) ccTotal++
            if (blOk) blTotal++
            if (ddOk) ddTotal++

            System.out.printf("  %-8d %+8d %-5s %+8.2f %-5s %+8.2f %-5s%n",
                seed,
                d.colorChanges - s.colorChanges, if (ccOk) "OK" else "FAIL",
                d.avgBatchLength - s.avgBatchLength, if (blOk) "OK" else "FAIL",
                d.dueDateDeviation - s.dueDateDeviation, if (ddOk) "OK" else "FAIL")
        }
        System.out.printf("%n  DISTRIBUTION: colorChanges %d/%d · batchLength %d/%d · dueDateDeviation %d/%d%n",
            ccTotal, seeds.size, blTotal, seeds.size, ddTotal, seeds.size)
    }

    private fun buildSpecs(lanes: Int, capacity: Int): List<PbsSimRunner.LaneSpec> {
        return (1..lanes).map { i -> PbsSimRunner.LaneSpec("L$i", capacity) }
    }
}
