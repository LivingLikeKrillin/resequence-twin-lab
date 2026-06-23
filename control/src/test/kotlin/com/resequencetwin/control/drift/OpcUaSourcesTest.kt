package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Integration test for [OpcUaConfigSource] and [OpcUaTelemetrySource].
 *
 * Boots an in-JVM [EmbeddedPbsOpcUaServer] (Eclipse milo sdk-server, test-scope) on an ephemeral
 * port, pre-seeds the PBS nodes, then verifies both adapters round-trip correctly via OPC-UA.
 *
 * This test intentionally uses the FULL embedded server path. If the embedded server fails to
 * start in CI or a restricted environment, add `-Dtest=!OpcUaSourcesTest` to skip it, or annotate
 * with `@Tag("opcua")` and run selectively with `-Dgroups=opcua`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpcUaSourcesTest {

    private lateinit var server: EmbeddedPbsOpcUaServer
    private val endpoint get() = server.endpointUrl

    @BeforeAll
    fun start() {
        server = EmbeddedPbsOpcUaServer()
        server.start()
        server.setConfig(lanes = mapOf("L1" to 10, "L2" to 8), blocked = setOf("L2"))
        server.setKpi(releases = 5, colourChanges = 2, assemblyOut = 5)
    }

    @AfterAll
    fun stop() {
        server.stop()
    }

    @Test
    fun `OpcUaConfigSource reads lane capacities and blocked set`() {
        val src = OpcUaConfigSource(endpoint, laneIds = listOf("L1", "L2"))
        val cfg = src.readConfig()
        assertThat(cfg.lanes).isEqualTo(mapOf("L1" to 10, "L2" to 8))
        assertThat(cfg.blocked).containsExactly("L2")
        src.close()
    }

    @Test
    fun `OpcUaTelemetrySource reads KPI nodes`() {
        val src = OpcUaTelemetrySource(endpoint)
        assertThat(src.readKpi()).isEqualTo(ObservedKpi(releases = 5, colourChanges = 2, assemblyOut = 5))
        src.close()
    }
}
