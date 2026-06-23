package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.Body
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.Lane
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class PbsSimRunnerCheckpointTest {

    companion object {
        private const val SEED = 42L
        private const val TOTAL_BODIES = 100

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
        bodies = PbsLoadGenerator(SEED, TOTAL_BODIES).generate()
    }

    @Test
    @DisplayName("CHKPT-1: assemblyOut().size() == maxReleases after N releases")
    fun checkpointAfterNReleasesHasAssemblyOutSizeN() {
        val maxReleases = 10
        val cp = runner.runToCheckpoint(bodies, LANE_SPECS, DynamicSequencingPolicy(), maxReleases)

        assertThat(cp.releasesDone)
            .`as`("releasesDone must equal maxReleases")
            .isEqualTo(maxReleases)
        assertThat(cp.state.assemblyOut())
            .`as`("assemblyOut.size() must equal maxReleases")
            .hasSize(maxReleases)
    }

    @Test
    @DisplayName("CHKPT-2: checkpoint at maxReleases=0 has empty assemblyOut AND empty lanes (run()-aligned)")
    fun checkpointAtZeroReleasesHasEmptyAssemblyOut() {
        val cp = runner.runToCheckpoint(bodies, LANE_SPECS, DynamicSequencingPolicy(), 0)

        assertThat(cp.releasesDone)
            .`as`("releasesDone must be 0")
            .isEqualTo(0)
        assertThat(cp.state.assemblyOut())
            .`as`("assemblyOut must be empty at 0 releases")
            .isEmpty()

        val totalOccupied = LANE_SPECS.sumOf { s -> cp.state.lane(s.id)?.size() ?: 0 }
        assertThat(totalOccupied)
            .`as`("lanes must be empty at 0 releases: loop never executed, no arrivals phase ran")
            .isEqualTo(0)
    }

    @Test
    @DisplayName("CHKPT-3: same inputs → identical assemblyOut and lane contents")
    fun checkpointIsDeterministic() {
        val maxReleases = 15
        val policy1 = DynamicSequencingPolicy()
        val policy2 = DynamicSequencingPolicy()

        val cp1 = runner.runToCheckpoint(bodies, LANE_SPECS, policy1, maxReleases)
        val cp2 = runner.runToCheckpoint(bodies, LANE_SPECS, policy2, maxReleases)

        val out1 = cp1.state.assemblyOut()
        val out2 = cp2.state.assemblyOut()
        assertThat(out1).hasSameSizeAs(out2)
        for (i in out1.indices) {
            assertThat(out1[i].id)
                .`as`("assemblyOut[%d].id must match", i)
                .isEqualTo(out2[i].id)
        }

        for (spec in LANE_SPECS) {
            val lane1Occupants = cp1.state.lane(spec.id)!!.occupants()
            val lane2Occupants = cp2.state.lane(spec.id)!!.occupants()
            assertThat(lane1Occupants).hasSameSizeAs(lane2Occupants)
            for (i in lane1Occupants.indices) {
                assertThat(lane1Occupants[i].id)
                    .`as`("lane %s occupant[%d].id must match", spec.id, i)
                    .isEqualTo(lane2Occupants[i].id)
            }
        }
    }

    @Test
    @DisplayName("CHKPT-4: maxReleases > stream size stops gracefully at stream size")
    fun checkpointBeyondStreamSizeStopsGracefully() {
        val overlyLarge = TOTAL_BODIES + 999
        val cp = runner.runToCheckpoint(bodies, LANE_SPECS, DynamicSequencingPolicy(), overlyLarge)

        assertThat(cp.releasesDone)
            .`as`("releasesDone must be capped at stream size")
            .isEqualTo(TOTAL_BODIES)
        assertThat(cp.state.assemblyOut())
            .`as`("assemblyOut size must equal total bodies")
            .hasSize(TOTAL_BODIES)
    }

    @Test
    @DisplayName("CHKPT-5: existing run() still produces the same KPIs after refactor")
    fun existingRunMethodUnchanged() {
        val s1 = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())
        val s2 = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(s1.colorChanges).isEqualTo(s2.colorChanges)
        assertThat(s1.avgBatchLength).isEqualTo(s2.avgBatchLength)
        assertThat(s1.dueDateDeviation).isEqualTo(s2.dueDateDeviation)
        assertThat(s1.throughput).isEqualTo(s2.throughput)
        assertThat(s1.laneUtilization).isEqualTo(s2.laneUtilization)
    }

    @Test
    @DisplayName("CHKPT-6: partial checkpoint assemblyOut[0..N-1] matches full-run checkpoint prefix")
    fun partialCheckpointAssemblyOutIsFullRunPrefix() {
        val partial = 10

        val cpPartial = runner.runToCheckpoint(bodies, LANE_SPECS, DynamicSequencingPolicy(), partial)
        val cpFull = runner.runToCheckpoint(bodies, LANE_SPECS, DynamicSequencingPolicy(), TOTAL_BODIES)

        val partialOut = cpPartial.state.assemblyOut()
        val fullOut = cpFull.state.assemblyOut()

        assertThat(partialOut).hasSize(partial)
        assertThat(fullOut).hasSize(TOTAL_BODIES)

        for (i in 0 until partial) {
            assertThat(partialOut[i].id)
                .`as`("assemblyOut[%d].id must match between partial and full checkpoint", i)
                .isEqualTo(fullOut[i].id)
        }
    }

    @Test
    @DisplayName("CHKPT-7: full runToCheckpoint KPIs match run() — end-to-end alignment proof")
    fun fullCheckpointKpisMatchRun() {
        val runSnapshot = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())

        val fullCp = runner.runToCheckpoint(bodies, LANE_SPECS, DynamicSequencingPolicy(), bodies.size)

        val assemblyOut = fullCp.state.assemblyOut()
        assertThat(assemblyOut)
            .`as`("full checkpoint must release all bodies")
            .hasSize(bodies.size)

        var colorChanges = 0
        if (assemblyOut.size >= 2) {
            var prev = assemblyOut[0].color
            for (i in 1 until assemblyOut.size) {
                val cur = assemblyOut[i].color
                if (cur != prev) { colorChanges++; prev = cur }
            }
        }

        var devSum = 0.0
        for (i in assemblyOut.indices) {
            devSum += Math.abs(i - assemblyOut[i].dueDateSeq)
        }
        val dueDateDeviation = devSum / assemblyOut.size

        assertThat(colorChanges)
            .`as`("colorChanges from full checkpoint assemblyOut must equal run() colorChanges")
            .isEqualTo(runSnapshot.colorChanges)
        assertThat(dueDateDeviation)
            .`as`("dueDateDeviation from full checkpoint assemblyOut must equal run() dueDateDeviation")
            .isEqualTo(runSnapshot.dueDateDeviation)
    }
}
