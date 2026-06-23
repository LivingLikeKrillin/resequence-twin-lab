package com.resequencetwin.control.bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptimalityInstanceExporterTest {

    private val lanes = listOf(
        PbsSimRunner.LaneSpec("L1", 2), PbsSimRunner.LaneSpec("L2", 2), PbsSimRunner.LaneSpec("L3", 2)
    )

    @Test
    fun `export is deterministic for the same seed and config`() {
        val a = OptimalityInstanceExporter.exportInstanceJson(seed = 42L, bodies = 12, lanes = lanes)
        val b = OptimalityInstanceExporter.exportInstanceJson(seed = 42L, bodies = 12, lanes = lanes)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `export carries seed, lanes, N bodies with colours, and heuristic KPIs`() {
        val json = OptimalityInstanceExporter.exportInstanceJson(seed = 7L, bodies = 12, lanes = lanes)
        val node = jacksonObjectMapper().readTree(json as String)

        assertThat(node["seed"].asInt()).isEqualTo(7)
        assertThat(node["lanes"]).hasSize(3)
        assertThat(node["lanes"][0]["id"].asText()).isEqualTo("L1")
        assertThat(node["lanes"][0]["cap"].asInt()).isEqualTo(2)
        assertThat(node["bodies"]).hasSize(12)
        assertThat(node["bodies"][0]["color"].asText()).isNotBlank()
        assertThat(node["bodies"][0]["dueDateSeq"].asInt()).isGreaterThanOrEqualTo(0)
        assertThat(node["heuristic"]["colorChanges"].asInt()).isGreaterThanOrEqualTo(0)
        assertThat(node["heuristic"]["dueDateDeviation"].asDouble()).isGreaterThanOrEqualTo(0.0)
    }
}
