package com.resequencetwin.control.bench

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * On-demand fixture generator (not a behavioural assertion): writes committed instance fixtures for the
 * `solver/` oracle. Re-run after any change to the heuristic or the instance config, then commit the
 * regenerated JSON. Determinism is asserted by [OptimalityInstanceExporterTest].
 */
class OptimalityFixtureExportTest {

    // cap=1 (total buffer 3 « N=12): a deliberately tight buffer. The colour-vs-due-date tension —
    // not raw capacity — is what makes the due-date-constrained optimum non-trivial here (the oracle's
    // due-date hard constraint forces reordering that breaks colour batches). See research/optimality-gap.md.
    private val lanes = listOf(
        PbsSimRunner.LaneSpec("L1", 1), PbsSimRunner.LaneSpec("L2", 1), PbsSimRunner.LaneSpec("L3", 1)
    )
    private val seeds = listOf(1L, 7L, 42L, 99L, 2024L)
    private val bodies = 12

    @Test
    fun `generate fixtures into solver fixtures`() {
        val outDir = Paths.get("..", "solver", "fixtures").toAbsolutePath().normalize()
        Files.createDirectories(outDir)
        for (seed in seeds) {
            val json = OptimalityInstanceExporter.exportInstanceJson(seed, bodies, lanes)
            Files.writeString(outDir.resolve("seed-$seed.json"), json)
        }
    }
}
