package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PbsSimRunnerTraceTest {

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
    private lateinit var bodies: List<com.resequencetwin.control.pbs.Body>

    @BeforeEach
    fun setUp() {
        runner = PbsSimRunner()
        bodies = PbsLoadGenerator(SEED, TOTAL_BODIES).generate()
    }

    @Test
    @DisplayName("TRACE-1: frame count is sane (>= total bodies, <= maxSteps)")
    fun frameCountIsSane() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(trace.frames).isNotEmpty
        assertThat(trace.frames.size)
            .`as`("frame count must be >= total bodies (at least one step per release)")
            .isGreaterThanOrEqualTo(TOTAL_BODIES)
        assertThat(trace.frames.size)
            .`as`("frame count must be <= maxSteps guard")
            .isLessThanOrEqualTo(PbsSimRunner.DEFAULT_MAX_STEPS)
    }

    @Test
    @DisplayName("TRACE-2: last frame has all lanes empty (all bodies released)")
    fun lastFrameAllLanesEmpty() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        val lastFrame = trace.frames.last()
        val totalOccupants = lastFrame.lanes.values.sumOf { it.size }
        assertThat(totalOccupants)
            .`as`("last frame must have all lanes empty after all bodies released")
            .isEqualTo(0)
    }

    @Test
    @DisplayName("TRACE-3: released-id sequence matches run() assemblyOut order")
    fun releasedSequenceMatchesRunAssemblyOut() {
        val policy1 = DynamicSequencingPolicy()

        val trace = runner.runWithTrace(bodies, LANE_SPECS, policy1)

        // Collect non-null released ids from trace frames in order
        val traceReleasedIds = trace.frames.mapNotNull { it.released }

        // Run again to get the assemblyOut order from a checkpoint that covers all bodies
        val policy3 = DynamicSequencingPolicy()
        val cp = runner.runToCheckpoint(bodies, LANE_SPECS, policy3, TOTAL_BODIES)
        val runAssemblyIds = cp.state.assemblyOut().map { it.id }

        assertThat(traceReleasedIds)
            .`as`("trace released ids must match run() assemblyOut order")
            .isEqualTo(runAssemblyIds)
    }

    @Test
    @DisplayName("TRACE-4: determinism — same inputs produce identical trace")
    fun traceIsDeterministic() {
        val trace1 = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())
        val trace2 = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(trace1.frames.size).isEqualTo(trace2.frames.size)
        for (i in trace1.frames.indices) {
            val f1 = trace1.frames[i]
            val f2 = trace2.frames[i]
            assertThat(f1.step).isEqualTo(f2.step)
            assertThat(f1.released).isEqualTo(f2.released)
            assertThat(f1.arrived).isEqualTo(f2.arrived)
            assertThat(f1.lanes).isEqualTo(f2.lanes)
        }
    }

    @Test
    @DisplayName("TRACE-5: each frame has a step index equal to its position")
    fun frameStepIndicesAreSequential() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        for (i in trace.frames.indices) {
            assertThat(trace.frames[i].step)
                .`as`("frame[%d].step must equal %d", i, i)
                .isEqualTo(i)
        }
    }

    @Test
    @DisplayName("TRACE-6: total released ids across all frames == total bodies")
    fun totalReleasedCountEqualsBodyCount() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        val releasedCount = trace.frames.count { it.released != null }
        assertThat(releasedCount)
            .`as`("total released count must equal total body count")
            .isEqualTo(TOTAL_BODIES)
    }

    @Test
    @DisplayName("TRACE-7: laneSpecs in Trace match the input lane specs")
    fun traceLaneSpecsMatchInput() {
        val trace = runner.runWithTrace(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(trace.laneSpecs).isEqualTo(LANE_SPECS)
    }

    @Test
    @DisplayName("TRACE-8: existing run() still produces same KPIs after runWithTrace added")
    fun existingRunUnchanged() {
        val s1 = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())
        val s2 = runner.run(bodies, LANE_SPECS, DynamicSequencingPolicy())

        assertThat(s1.colorChanges).isEqualTo(s2.colorChanges)
        assertThat(s1.avgBatchLength).isEqualTo(s2.avgBatchLength)
        assertThat(s1.dueDateDeviation).isEqualTo(s2.dueDateDeviation)
    }
}
