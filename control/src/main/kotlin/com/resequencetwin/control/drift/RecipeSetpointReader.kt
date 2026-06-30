package com.resequencetwin.control.drift

import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import java.util.Optional

/** Reads a Double node value from the shared OPC-UA sim. Returns null on unreadable/bad status. */
fun interface RecipeSetpointReader {
    fun read(nodeId: String): Double?
}

/** Milo-backed reader (lazy-connect, SecurityPolicy.None/anonymous — PoC, mirrors OpcUaConfigSource). */
class MiloRecipeSetpointReader(private val endpoint: String) : RecipeSetpointReader, AutoCloseable {
    @Volatile private var client: OpcUaClient? = null

    private fun getOrConnect(): OpcUaClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val c = OpcUaClient.create(
                endpoint,
                { endpoints -> Optional.ofNullable(endpoints.firstOrNull { it.securityPolicyUri == SecurityPolicy.None.uri }) },
                { it.build() },
            )
            c.connect().get()
            client = c
            return c
        }
    }

    override fun read(nodeId: String): Double? = try {
        val dv: DataValue = getOrConnect().readValue(0.0, TimestampsToReturn.Neither, NodeId.parse(nodeId)).get()
        (dv.value?.value as? Number)?.toDouble()
    } catch (_: Exception) { null }

    override fun close() {
        val c = client ?: return
        client = null
        try { c.disconnect().get() } catch (_: Exception) {}
    }
}
