package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimulatedSourcesTest {

    @Test
    fun `simulated config source returns its seeded config and reflects mutations`() {
        val src = SimulatedConfigSource(
            initial = ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10), blocked = emptySet())
        )
        assertThat(src.readConfig().lanes["L1"]).isEqualTo(10)

        src.setCapacity("L1", 8)
        assertThat(src.readConfig().lanes["L1"]).isEqualTo(8)

        src.block("L2")
        assertThat(src.readConfig().blocked).containsExactly("L2")

        src.removeLane("L1")
        assertThat(src.readConfig().lanes).doesNotContainKey("L1")
    }

    @Test
    fun `simulated telemetry source returns its seeded kpi and reflects mutations`() {
        val src = SimulatedTelemetrySource(initial = ObservedKpi(0, 0, 0))
        assertThat(src.readKpi().releases).isEqualTo(0)
        src.set(ObservedKpi(releases = 5, colourChanges = 2, assemblyOut = 5))
        assertThat(src.readKpi()).isEqualTo(ObservedKpi(5, 2, 5))
    }

    @Test
    fun `ports are implemented by the simulated adapters`() {
        val cfg: LiveConfigSource = SimulatedConfigSource(ObservedConfig(emptyMap(), emptySet()))
        val tel: LiveTelemetrySource = SimulatedTelemetrySource(ObservedKpi(0, 0, 0))
        assertThat(cfg.readConfig()).isNotNull()
        assertThat(tel.readKpi()).isNotNull()
    }
}
