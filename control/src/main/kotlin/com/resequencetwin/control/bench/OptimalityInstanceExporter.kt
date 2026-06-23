package com.resequencetwin.control.bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.resequencetwin.control.pbs.DynamicSequencingPolicy

/**
 * Exports a PBS instance + the dynamic heuristic's measured KPIs as a JSON fixture for the offline
 * CP-SAT optimality-gap oracle (`solver/`). Pure: same (seed, bodies, lanes) → identical JSON.
 *
 * Honesty: `heuristic.*` are the *actual* numbers from running [DynamicSequencingPolicy] here — the
 * Python oracle never re-computes them (avoids cross-language divergence).
 */
object OptimalityInstanceExporter {

    private val mapper = jacksonObjectMapper()

    fun exportInstanceJson(seed: Long, bodies: Int, lanes: List<PbsSimRunner.LaneSpec>): String {
        val stream = PbsLoadGenerator(seed, bodies).generate()
        val kpi = PbsSimRunner().run(stream, lanes, DynamicSequencingPolicy())
        val payload = linkedMapOf(
            "seed" to seed,
            "lanes" to lanes.map { linkedMapOf("id" to it.id, "cap" to it.capacity) },
            "bodies" to stream.map { linkedMapOf("id" to it.id, "color" to it.color, "dueDateSeq" to it.dueDateSeq) },
            "heuristic" to linkedMapOf(
                "colorChanges" to kpi.colorChanges,
                "dueDateDeviation" to kpi.dueDateDeviation,
            ),
        )
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
    }
}
