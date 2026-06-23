package com.resequencetwin.control.drift

import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import java.util.Optional

/**
 * OPC-UA–backed [LiveConfigSource] that reads PBS lane configuration from a SYNTHETIC OPC-UA server.
 *
 * **This is NOT a real PLC connection.** The counterpart server for PoC/testing is
 * [com.resequencetwin.control.drift.EmbeddedPbsOpcUaServer] (test-scope) or any OPC-UA server that
 * publishes nodes in the layout described below.
 *
 * ## Node-ID layout (namespace index 2)
 * ```
 * ns=2;s=PBS/lanes/<laneId>/capacity   → Int32  (lane capacity)
 * ns=2;s=PBS/lanes/<laneId>/blocked    → Boolean (true = blocked)
 * ```
 *
 * ## Connection contract
 * - **Lazy:** the constructor does NOT connect. The OPC-UA client is created and connected on the
 *   FIRST call to [readConfig]. This is intentional — callers (e.g. Task-11 wiring tests) may
 *   construct this adapter without a live server being present.
 * - The connected client is cached; subsequent [readConfig] calls reuse the same connection.
 * - Call [close] to disconnect when done. [close] is idempotent and swallows errors.
 * - Not thread-safe between [close] and read calls; the caller must sequence them (single
 *   scheduler-thread usage in this PoC).
 *
 * @param endpoint OPC-UA endpoint URL, e.g. `"opc.tcp://localhost:4840/pbs"`
 * @param laneIds lane identifiers to query, e.g. `listOf("L1", "L2", "L3")`
 */
class OpcUaConfigSource(
    private val endpoint: String,
    private val laneIds: List<String>,
) : LiveConfigSource, AutoCloseable {

    // Cached client — null until first readConfig() call (lazy connect).
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
     * Reads lane capacities and blocked flags for each laneId from the OPC-UA server.
     *
     * Connects lazily on the first call. Propagates any I/O or connection exception so
     * [com.resequencetwin.control.drift.DriftMonitor] can treat this as a non-fatal source outage.
     */
    override fun readConfig(): ObservedConfig {
        val c = getOrConnect()
        val lanes = mutableMapOf<String, Int>()
        val blocked = mutableSetOf<String>()

        for (laneId in laneIds) {
            val capacityNodeId = NodeId.parse("ns=2;s=PBS/lanes/$laneId/capacity")
            val blockedNodeId = NodeId.parse("ns=2;s=PBS/lanes/$laneId/blocked")

            val capacityValue: DataValue =
                c.readValue(0.0, TimestampsToReturn.Neither, capacityNodeId).get()
            val blockedValue: DataValue =
                c.readValue(0.0, TimestampsToReturn.Neither, blockedNodeId).get()

            val rawCapacity = capacityValue.value.value
                ?: error("OPC-UA node $capacityNodeId returned null/bad status: ${capacityValue.statusCode}")
            val capacity = (rawCapacity as Number).toInt()

            val rawBlocked = blockedValue.value.value
                ?: error("OPC-UA node $blockedNodeId returned null/bad status: ${blockedValue.statusCode}")
            val isBlocked = rawBlocked as Boolean

            lanes[laneId] = capacity
            if (isBlocked) blocked.add(laneId)
        }

        return ObservedConfig(lanes = lanes.toMap(), blocked = blocked.toSet())
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
