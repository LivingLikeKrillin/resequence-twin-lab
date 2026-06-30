package com.resequencetwin.control.drift

import com.resequencetwin.control.stream.parseLaneTopology
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the live-source beans selected by `pbs.drift.source` (default `sim`).
 *
 * Supported source values:
 * - `sim`   — deterministic in-process simulation; zero external dependencies; suitable for CI and
 *             offline demos. Seeded from the same `pbs.lanes` topology the twin uses, so a clean
 *             startup shows zero drift until a demo mutates the source.
 * - `opcua` — Eclipse Milo OPC-UA client that reads lane config and KPI counters from a SYNTHETIC
 *             OPC-UA server (e.g. the embedded test server or a user-run demo server). The adapters
 *             connect **lazily** on the first read, so constructing them requires no live server.
 *
 * Set `pbs.drift.opcua.endpoint` (default `opc.tcp://localhost:4840`) when using the `opcua` source.
 */
@Configuration
class DriftConfig(
    @Value("\${pbs.lanes:L1:10,L2:10,L3:10}") private val lanesSpec: String,
    @Value("\${pbs.drift.source:sim}") private val source: String,
    @Value("\${pbs.drift.opcua.endpoint:opc.tcp://localhost:4840}") private val opcuaEndpoint: String,
    @Value("\${pbs.drift.recipe.enabled:false}") private val recipeEnabled: Boolean,
    @Value("\${pbs.drift.recipe.canonical-path:}") private val recipeCanonicalPath: String,
    @Value("\${pbs.drift.recipe.opcua.endpoint:}") private val recipeEndpointOverride: String,
) {
    private fun seedConfig(): ObservedConfig {
        val lanes = parseLaneTopology(lanesSpec).associate { it.id to it.capacity }
        return ObservedConfig(lanes = lanes, blocked = emptySet())
    }

    @Bean
    fun liveConfigSource(): LiveConfigSource = when (source) {
        "sim"   -> SimulatedConfigSource(seedConfig())
        "opcua" -> OpcUaConfigSource(opcuaEndpoint, parseLaneTopology(lanesSpec).map { it.id })
        else    -> throw IllegalStateException("Unsupported pbs.drift.source='$source' (expected 'sim' or 'opcua')")
    }

    @Bean
    fun liveTelemetrySource(): LiveTelemetrySource = when (source) {
        "sim"   -> SimulatedTelemetrySource(ObservedKpi(0, 0, 0))
        "opcua" -> OpcUaTelemetrySource(opcuaEndpoint)
        else    -> throw IllegalStateException("Unsupported pbs.drift.source='$source' (expected 'sim' or 'opcua')")
    }

    @Bean
    fun setpointDriftDetector(): SetpointDriftDetector {
        if (!recipeEnabled) return DisabledSetpointDriftDetector
        require(recipeCanonicalPath.isNotBlank()) { "pbs.drift.recipe.enabled=true requires pbs.drift.recipe.canonical-path" }
        val loaded = CanonicalSetpointsFile.load(recipeCanonicalPath)
        val endpoint = recipeEndpointOverride.ifBlank { loaded.endpoint }
        return RealSetpointDriftDetector(loaded.setpoints, MiloRecipeSetpointReader(endpoint))
    }
}
