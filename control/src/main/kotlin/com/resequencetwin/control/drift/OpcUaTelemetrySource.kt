package com.resequencetwin.control.drift

import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import java.util.Optional

/**
 * OPC-UA–backed [LiveTelemetrySource] that reads cumulative PBS KPI counters from a SYNTHETIC
 * OPC-UA server.
 *
 * **This is NOT a real PLC connection.** The counterpart server for PoC/testing is
 * [com.resequencetwin.control.drift.EmbeddedPbsOpcUaServer] (test-scope) or any OPC-UA server that
 * publishes nodes in the layout described below.
 *
 * ## Node-ID layout (namespace index 2)
 * ```
 * ns=2;s=PBS/kpi/releases       → Int32  (cumulative release count)
 * ns=2;s=PBS/kpi/colourChanges  → Int32  (cumulative colour-change count)
 * ns=2;s=PBS/kpi/assemblyOut    → Int32  (cumulative assembly-out count)
 * ```
 *
 * ## Connection contract
 * - **Lazy:** the constructor does NOT connect. The OPC-UA client is created and connected on the
 *   FIRST call to [readKpi]. This is intentional — callers (e.g. Task-11 wiring tests) may
 *   construct this adapter without a live server being present.
 * - The connected client is cached; subsequent [readKpi] calls reuse the same connection.
 * - Call [close] to disconnect when done. [close] is idempotent and swallows errors.
 * - Not thread-safe between [close] and read calls; the caller must sequence them (single
 *   scheduler-thread usage in this PoC).
 *
 * @param endpoint OPC-UA endpoint URL, e.g. `"opc.tcp://localhost:4840/pbs"`
 */
class OpcUaTelemetrySource(
    private val endpoint: String,
) : LiveTelemetrySource, AutoCloseable {

    // Cached client — null until first readKpi() call (lazy connect).
    @Volatile private var client: OpcUaClient? = null

    /**
     * Returns the cached client if already connected, otherwise creates and connects a new one.
     * Uses SecurityPolicy.None + anonymous identity (PoC — not for production).
     */
    private fun getOrConnect(): OpcUaClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val c = OpcUaClient.create(
                endpoint,
                { endpoints ->
                    Optional.ofNullable(
                        endpoints.firstOrNull { e -> e.securityPolicyUri == SecurityPolicy.None.uri }
                    )
                },
                { configBuilder -> configBuilder.build() },
            )
            c.connect().get()
            client = c
            return c
        }
    }

    /**
     * Reads the three cumulative KPI counters from the OPC-UA server.
     *
     * Connects lazily on the first call. Propagates any I/O or connection exception so
     * [com.resequencetwin.control.drift.DriftMonitor] can treat this as a non-fatal source outage.
     */
    override fun readKpi(): ObservedKpi {
        val c = getOrConnect()

        fun readInt(nodeIdStr: String): Int {
            val v: DataValue = c.readValue(0.0, TimestampsToReturn.Neither, NodeId.parse(nodeIdStr)).get()
            val raw = v.value.value
                ?: error("OPC-UA node $nodeIdStr returned null/bad status: ${v.statusCode}")
            return (raw as Number).toInt()
        }

        return ObservedKpi(
            releases = readInt("ns=2;s=PBS/kpi/releases"),
            colourChanges = readInt("ns=2;s=PBS/kpi/colourChanges"),
            assemblyOut = readInt("ns=2;s=PBS/kpi/assemblyOut"),
        )
    }

    /**
     * Disconnects the cached OPC-UA client if one has been connected. Idempotent; swallows errors.
     */
    override fun close() {
        val c = client ?: return
        client = null
        try {
            c.disconnect().get()
        } catch (_: Exception) {
            // swallow — best-effort close
        }
    }
}
