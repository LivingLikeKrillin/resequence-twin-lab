package com.resequencetwin.control.api

import com.resequencetwin.control.bench.PbsLoadGenerator
import com.resequencetwin.control.bench.PbsSimRunner
import com.resequencetwin.control.pbs.DynamicSequencingPolicy
import com.resequencetwin.control.pbs.StaticSequencingPolicy
import org.springframework.stereotype.Service

/**
 * Service backing GET /api/trajectory.
 *
 * Wires PbsLoadGenerator → PbsSimRunner.runWithTrace → TrajectoryResponse.
 */
@Service
class PbsTrajectoryService {

    companion object {
        const val DEFAULT_SEED: Long = 42L
        const val DEFAULT_BODIES: Int = 100
        const val DEFAULT_POLICY: String = "dynamic"
        const val MAX_BODIES: Int = 2000

        /** Canonical 3×10 lane config matching the benchmark. */
        val DEFAULT_LANE_SPECS: List<PbsSimRunner.LaneSpec> = listOf(
            PbsSimRunner.LaneSpec("L1", 10),
            PbsSimRunner.LaneSpec("L2", 10),
            PbsSimRunner.LaneSpec("L3", 10)
        )
    }

    private val runner = PbsSimRunner()

    /**
     * Build a full trajectory for the given params.
     *
     * @param seed        RNG seed (default 42)
     * @param bodiesCount total bodies to simulate (1..2000)
     * @param policyStr   "dynamic" or "static"
     * @return [TrajectoryResponse] with honesty envelope + all frames
     */
    fun trajectory(seed: Long, bodiesCount: Int, policyStr: String): TrajectoryResponse {
        val bodyList = PbsLoadGenerator(seed, bodiesCount).generate()

        val policy = when (policyStr) {
            "dynamic" -> DynamicSequencingPolicy()
            "static"  -> StaticSequencingPolicy()
            else      -> throw IllegalArgumentException("Unknown policy: $policyStr")
        }

        val trace = runner.runWithTrace(bodyList, DEFAULT_LANE_SPECS, policy)

        val env = AdvisoryEnvelope.standard()

        // Build bodies map: id → TrajectoryBodyDto
        val bodiesMap: Map<String, TrajectoryBodyDto> = bodyList.associate { b ->
            b.id to TrajectoryBodyDto(
                color      = b.color,
                model      = b.model,
                options    = b.options.toList(),
                dueDateSeq = b.dueDateSeq
            )
        }

        // Build lanes list
        val lanesDto = trace.laneSpecs.map { s ->
            TrajectoryLaneDto(id = s.id, capacity = s.capacity)
        }

        // Build frames list
        val framesDto = trace.frames.map { f ->
            TrajectoryFrameDto(
                step     = f.step,
                lanes    = f.lanes,
                released = f.released,
                arrived  = f.arrived
            )
        }

        return TrajectoryResponse(
            synthetic  = env.synthetic,
            disclaimer = env.disclaimer,
            seed       = seed,
            policy     = policyStr,
            lanes      = lanesDto,
            bodies     = bodiesMap,
            frames     = framesDto
        )
    }
}
