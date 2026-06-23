package com.resequencetwin.control.drift

import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.api.DataItem
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration
import java.net.ServerSocket

/**
 * Lightweight in-JVM OPC-UA server (Eclipse milo [sdk-server]) for integration-testing the
 * PBS drift-seam adapters. Exposes the same PBS node layout that [OpcUaConfigSource] and
 * [OpcUaTelemetrySource] expect. SYNTHETIC / test-only — not a real PLC.
 *
 * ## Node layout (namespace index 2, NAMESPACE_URI = "urn:resequencetwin:pbs")
 * ```
 * PBS/
 *   lanes/<laneId>/capacity   Int32
 *   lanes/<laneId>/blocked    Boolean
 *   kpi/releases              Int32
 *   kpi/colourChanges         Int32
 *   kpi/assemblyOut           Int32
 * ```
 *
 * Binds on an ephemeral port (OS-assigned). The [endpointUrl] property returns the
 * `opc.tcp://localhost:<port>/pbs` URL after [start] has been called.
 */
class EmbeddedPbsOpcUaServer {

    private lateinit var server: OpcUaServer
    private lateinit var namespace: PbsNamespace
    private var boundPort: Int = 0

    /** The `opc.tcp://…` URL clients should use. Valid only after [start]. */
    val endpointUrl: String
        get() {
            check(boundPort > 0) { "Server not started yet or port not resolved" }
            return "opc.tcp://localhost:$boundPort/pbs"
        }

    fun start() {
        // Pre-allocate a free port: open a ServerSocket on port 0, record the port, close it.
        // The tiny window between close and milo's bind is acceptable in tests.
        boundPort = ServerSocket(0).use { it.localPort }

        val endpointConfig = EndpointConfiguration.newBuilder()
            .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
            .setBindAddress("localhost")
            .setBindPort(boundPort)  // explicit free port — avoids port-0 URL issue
            .setHostname("localhost")
            .setPath("/pbs")
            .setSecurityPolicy(SecurityPolicy.None)
            .setSecurityMode(MessageSecurityMode.None)
            .addTokenPolicies(
                UserTokenPolicy(
                    "anonymous",
                    UserTokenType.Anonymous,
                    null, null, null
                )
            )
            .build()

        val serverConfig = OpcUaServerConfig.builder()
            .setApplicationUri("urn:resequencetwin:pbs:test-server")
            .setApplicationName(LocalizedText.english("Resequence-Twin PBS Test Server"))
            .setEndpoints(setOf(endpointConfig))
            .build()

        server = OpcUaServer(serverConfig)

        // Register our namespace URI in the server's NamespaceTable so nodes resolve correctly
        server.namespaceTable.addUri(PbsNamespace.NAMESPACE_URI)

        namespace = PbsNamespace(server)
        namespace.startup()

        server.startup().get()
    }

    fun stop() {
        if (::server.isInitialized) {
            try { namespace.shutdown() } catch (_: Exception) {}
            try { server.shutdown().get() } catch (_: Exception) {}
        }
    }

    /** Overwrite lane capacities and blocked flags published by the server. */
    fun setConfig(lanes: Map<String, Int>, blocked: Set<String>) {
        namespace.setConfig(lanes, blocked)
    }

    /** Overwrite KPI counters published by the server. */
    fun setKpi(releases: Int, colourChanges: Int, assemblyOut: Int) {
        namespace.setKpi(releases, colourChanges, assemblyOut)
    }
}

// ---------------------------------------------------------------------------
// Internal namespace — visible within the test package
// ---------------------------------------------------------------------------

internal class PbsNamespace(server: OpcUaServer) :
    ManagedNamespaceWithLifecycle(server, NAMESPACE_URI) {

    companion object {
        const val NAMESPACE_URI: String = "urn:resequencetwin:pbs"
    }

    // lane nodes: laneId -> (capacityNode, blockedNode)
    private val laneNodes = mutableMapOf<String, Pair<UaVariableNode, UaVariableNode>>()

    // kpi nodes — initialised during startup in createNodes()
    private lateinit var releasesNode: UaVariableNode
    private lateinit var colourChangesNode: UaVariableNode
    private lateinit var assemblyOutNode: UaVariableNode

    init {
        lifecycleManager.addStartupTask(::createNodes)
    }

    // MonitoredItemServices abstract methods — no-op for a static PoC server
    override fun onDataItemsCreated(items: List<DataItem>) = Unit
    override fun onDataItemsModified(items: List<DataItem>) = Unit
    override fun onDataItemsDeleted(items: List<DataItem>) = Unit
    override fun onMonitoringModeChanged(items: List<MonitoredItem>) = Unit

    private fun createNodes() {
        val nm = nodeManager

        // Root PBS folder
        val pbsFolder = UaFolderNode(
            nodeContext,
            newNodeId("PBS"),
            newQualifiedName("PBS"),
            LocalizedText.english("PBS"),
        )
        nm.addNode(pbsFolder)

        // lanes folder
        val lanesFolder = UaFolderNode(
            nodeContext,
            newNodeId("PBS/lanes"),
            newQualifiedName("lanes"),
            LocalizedText.english("lanes"),
        )
        nm.addNode(lanesFolder)
        pbsFolder.addOrganizes(lanesFolder)

        // kpi folder
        val kpiFolder = UaFolderNode(
            nodeContext,
            newNodeId("PBS/kpi"),
            newQualifiedName("kpi"),
            LocalizedText.english("kpi"),
        )
        nm.addNode(kpiFolder)
        pbsFolder.addOrganizes(kpiFolder)

        // KPI nodes (Int32, initial value 0)
        releasesNode = makeIntNode("PBS/kpi/releases", "releases", 0)
        colourChangesNode = makeIntNode("PBS/kpi/colourChanges", "colourChanges", 0)
        assemblyOutNode = makeIntNode("PBS/kpi/assemblyOut", "assemblyOut", 0)
        nm.addNode(releasesNode)
        nm.addNode(colourChangesNode)
        nm.addNode(assemblyOutNode)
        kpiFolder.addOrganizes(releasesNode)
        kpiFolder.addOrganizes(colourChangesNode)
        kpiFolder.addOrganizes(assemblyOutNode)
    }

    /** Creates lane nodes on-demand the first time they are referenced. */
    private fun ensureLane(laneId: String): Pair<UaVariableNode, UaVariableNode> =
        laneNodes.getOrPut(laneId) {
            val capNode = makeIntNode("PBS/lanes/$laneId/capacity", "capacity", 0)
            val blkNode = makeBoolNode("PBS/lanes/$laneId/blocked", "blocked", false)
            nodeManager.addNode(capNode)
            nodeManager.addNode(blkNode)
            Pair(capNode, blkNode)
        }

    @Synchronized
    fun setConfig(lanes: Map<String, Int>, blocked: Set<String>) {
        for ((laneId, capacity) in lanes) {
            val (capNode, blkNode) = ensureLane(laneId)
            capNode.value = DataValue(Variant(capacity))
            blkNode.value = DataValue(Variant(laneId in blocked))
        }
    }

    @Synchronized
    fun setKpi(releases: Int, colourChanges: Int, assemblyOut: Int) {
        releasesNode.value = DataValue(Variant(releases))
        colourChangesNode.value = DataValue(Variant(colourChanges))
        assemblyOutNode.value = DataValue(Variant(assemblyOut))
    }

    // ---------- node-creation helpers ----------

    private fun makeIntNode(nodeIdStr: String, browseName: String, initial: Int): UaVariableNode =
        UaVariableNode.UaVariableNodeBuilder(nodeContext)
            .setNodeId(newNodeId(nodeIdStr))
            .setBrowseName(newQualifiedName(browseName))
            .setDisplayName(LocalizedText.english(browseName))
            .setDataType(Identifiers.Int32)
            .setTypeDefinition(Identifiers.BaseDataVariableType)
            .build()
            .also { it.value = DataValue(Variant(initial)) }

    private fun makeBoolNode(nodeIdStr: String, browseName: String, initial: Boolean): UaVariableNode =
        UaVariableNode.UaVariableNodeBuilder(nodeContext)
            .setNodeId(newNodeId(nodeIdStr))
            .setBrowseName(newQualifiedName(browseName))
            .setDisplayName(LocalizedText.english(browseName))
            .setDataType(Identifiers.Boolean)
            .setTypeDefinition(Identifiers.BaseDataVariableType)
            .build()
            .also { it.value = DataValue(Variant(initial)) }
}
