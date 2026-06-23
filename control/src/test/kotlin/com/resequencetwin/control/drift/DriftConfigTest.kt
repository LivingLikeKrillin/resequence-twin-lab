package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DriftConfigTest {

    @Test
    fun `sim source selection yields simulated adapters seeded from pbs-lanes`() {
        val cfg = DriftConfig(lanesSpec = "L1:10,L2:8", source = "sim",
            opcuaEndpoint = "opc.tcp://unused")
        val configSource = cfg.liveConfigSource()
        assertThat(configSource).isInstanceOf(SimulatedConfigSource::class.java)
        // seeded from pbs.lanes: L1=10, L2=8, no blocked
        assertThat((configSource as SimulatedConfigSource).readConfig().lanes)
            .isEqualTo(mapOf("L1" to 10, "L2" to 8))
        assertThat(cfg.liveTelemetrySource()).isInstanceOf(SimulatedTelemetrySource::class.java)
    }

    @Test
    fun `opcua source selection yields OPC-UA adapters`() {
        val cfg = DriftConfig(lanesSpec = "L1:10,L2:10", source = "opcua",
            opcuaEndpoint = "opc.tcp://localhost:4840")
        // OPC-UA adapters connect lazily, so constructing them here does NOT need a live server.
        assertThat(cfg.liveConfigSource()).isInstanceOf(OpcUaConfigSource::class.java)
        assertThat(cfg.liveTelemetrySource()).isInstanceOf(OpcUaTelemetrySource::class.java)
    }
}
