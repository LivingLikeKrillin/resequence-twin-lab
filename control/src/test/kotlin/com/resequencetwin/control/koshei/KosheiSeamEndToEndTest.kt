package com.resequencetwin.control.koshei

import com.resequencetwin.control.drift.CanonicalSetpointsFile
import com.resequencetwin.control.drift.DriftMonitor
import com.resequencetwin.control.drift.ObservedConfig
import com.resequencetwin.control.drift.ObservedKpi
import com.resequencetwin.control.drift.RECONCILE_SETPOINT
import com.resequencetwin.control.drift.RealSetpointDriftDetector
import com.resequencetwin.control.drift.RecipeSetpointReader
import com.resequencetwin.control.drift.SimulatedConfigSource
import com.resequencetwin.control.drift.SimulatedTelemetrySource
import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.LivePbsProcessor
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import java.io.File
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The end-to-end SEAM test: the whole pillar-②↔pillar-③ governance loop wired with REAL components and
 * no per-stage fakes, proving the pieces the two half-tests exercise in isolation actually compose.
 *
 * The half-tests, for contrast:
 * - [KosheiGovernanceSubscriberTest] proves Tahu wire bytes -> [ReconciliationAnnotation] (the decode).
 * - [ReconciliationLifecycleTest] proves a store annotation is merged into findings across ticks —
 *   but with a *hand-written* detector and a *direct* [ReconciliationStore.put] (no bytes, no subscriber).
 *
 * This test closes the gap between them by chaining every real link the running system uses:
 *
 *   koshei Sparkplug B bytes (Tahu)
 *     -> real [KosheiGovernanceSubscriber] (host NBIRTH/NDATA rules, real SpB decode)
 *     -> onReconciliation wired to store::put  ← EXACT production wiring from KosheiSubscribeConfig
 *     -> real [ReconciliationStore]
 *     -> real [DriftMonitor.tick] merge
 *     -> onto a real [RealSetpointDriftDetector] finding driven by the COMMITTED canonical contract
 *   == the reconciliation state visible on the /api/drift report (DriftMonitor.currentReport()).
 *
 * The only faked links are the genuine external boundaries: the OPC-UA read (a [RecipeSetpointReader]
 * lambda, matching SetpointDriftDetectorTest) and the config/telemetry sim sources. Everything on the
 * governance path — decode, host rules, store, merge, detector — is the real code.
 */
class KosheiSeamEndToEndTest {

    private val group = "Koshei"
    private val edge = "Governance"
    private fun nbirthTopic() = "spBv1.0/$group/NBIRTH/$edge"
    private fun ndataTopic() = "spBv1.0/$group/NDATA/$edge"

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))

    /** The canonical file lives at repo-root/model/; resolve it whether tests run from control/ or root. */
    private fun canonicalFile(): File =
        listOf("model/recipe-setpoints.yaml", "../model/recipe-setpoints.yaml")
            .map(::File)
            .firstOrNull(File::exists)
            ?: error("model/recipe-setpoints.yaml not found (cwd=${File(".").absolutePath})")

    /** Build a Sparkplug B NBIRTH payload (seq 0) — a host must observe a birth before acting on data. */
    private fun nbirthBytes(): ByteArray {
        val p = SparkplugBPayloadBuilder().setTimestamp(Date()).setSeq(0L)
            .addMetric(MetricBuilder("bdSeq", MetricDataType.Int64, 0L).createMetric())
            .createPayload()
        return SparkplugBPayloadEncoder().getBytes(p, false)
    }

    /** Build a Sparkplug B NDATA payload carrying the governance header + one touched setpoint. */
    private fun ndataBytes(
        eventType: String,
        runId: String,
        at: Long,
        setpointKey: String,
        value: Double,
        seq: Long = 1L,
    ): ByteArray {
        val p = SparkplugBPayloadBuilder().setTimestamp(Date()).setSeq(seq)
            .addMetric(MetricBuilder("Governance/LastEventType", MetricDataType.String, eventType).createMetric())
            .addMetric(MetricBuilder("Governance/LastRunId", MetricDataType.String, runId).createMetric())
            .addMetric(MetricBuilder("Governance/LastEventTimestamp", MetricDataType.Int64, at).createMetric())
            .addMetric(MetricBuilder("Setpoint/$setpointKey", MetricDataType.Double, value).createMetric())
            .createPayload()
        return SparkplugBPayloadEncoder().getBytes(p, false)
    }

    private fun monitor(store: ReconciliationStore, detector: RealSetpointDriftDetector) = DriftMonitor(
        LivePbsProcessor(lanes),
        SimulatedConfigSource(ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = emptySet())),
        SimulatedTelemetrySource(ObservedKpi(0, 0, 0)),
        detector,
        reconciliationStore = store,
        enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0,
    )

    private fun rpmFinding(m: DriftMonitor) =
        m.currentReport().setpointFindings.single { it.key == "recipe.rpmSetpoint" }

    @Test
    fun `koshei governance bytes drive the reconciliation state onto a real drift finding`() {
        // --- Real detector: the committed canonical contract vs a live OPC-UA read that drifts on rpm. ---
        val loaded = CanonicalSetpointsFile.load(canonicalFile().path)
        val rpm = loaded.setpoints.first { it.key == "recipe.rpmSetpoint" }
        val reader = RecipeSetpointReader { nodeId ->
            if (nodeId == rpm.nodeId) rpm.desired - (rpm.tolerance + 100.0)   // drift beyond tolerance
            else loaded.setpoints.first { it.nodeId == nodeId }.desired        // everything else on-spec
        }
        val detector = RealSetpointDriftDetector(loaded.setpoints, reader)

        // --- Real seam wiring, byte-for-byte as KosheiSubscribeConfig assembles it in production. ---
        val store = ReconciliationStore()
        val subscriber = KosheiGovernanceSubscriber(edge) { key, ann -> store.put(key, ann) }
        val monitor = monitor(store, detector)

        // (1) Before any governance event: the real detector reports DETECTED drift, no reconciliation yet.
        monitor.tick()
        val detected = rpmFinding(monitor)
        assertTrue(detected.breached, "rpm setpoint should be detected as drifted")
        assertEquals(RECONCILE_SETPOINT, detected.proposal.action)
        assertNull(detected.reconciliation, "no koshei event seen yet -> no reconciliation annotation")

        // (2) koshei announces the node (NBIRTH) then parks the reconciliation run (NDATA RECONCILING).
        //     These are REAL Sparkplug B bytes decoded by the REAL host subscriber.
        subscriber.onMessage(nbirthTopic(), nbirthBytes())
        subscriber.onMessage(ndataTopic(), ndataBytes("RECONCILING", "wf-seam-1", 100L, "recipe.rpmSetpoint", 1500.0))

        monitor.tick()
        val reconciling = rpmFinding(monitor).reconciliation
            ?: error("expected the RECONCILING annotation to be merged onto the finding")
        assertEquals(ReconciliationState.RECONCILING, reconciling.state)
        assertEquals("wf-seam-1", reconciling.runId, "provenance (which koshei run) must survive the seam")
        assertEquals(100L, reconciling.atMillis)

        // (3) koshei confirms the governed write-back (NDATA CONFIRMED -> CLEARED), surviving a later tick.
        subscriber.onMessage(ndataTopic(), ndataBytes("CONFIRMED", "wf-seam-1", 200L, "recipe.rpmSetpoint", 1500.0, seq = 2L))

        monitor.tick()
        val cleared = rpmFinding(monitor).reconciliation
            ?: error("expected the CLEARED annotation to be merged onto the finding")
        assertEquals(ReconciliationState.CLEARED, cleared.state)
        assertEquals("wf-seam-1", cleared.runId)
    }

    @Test
    fun `NDATA before NBIRTH does not annotate the finding (host rule holds across the whole seam)`() {
        val loaded = CanonicalSetpointsFile.load(canonicalFile().path)
        val rpm = loaded.setpoints.first { it.key == "recipe.rpmSetpoint" }
        val reader = RecipeSetpointReader { nodeId ->
            if (nodeId == rpm.nodeId) rpm.desired - (rpm.tolerance + 100.0)
            else loaded.setpoints.first { it.nodeId == nodeId }.desired
        }
        val detector = RealSetpointDriftDetector(loaded.setpoints, reader)

        val store = ReconciliationStore()
        val subscriber = KosheiGovernanceSubscriber(edge) { key, ann -> store.put(key, ann) }
        val monitor = monitor(store, detector)

        // NDATA arrives with no preceding NBIRTH: the host rule drops it, so nothing reaches the store.
        subscriber.onMessage(ndataTopic(), ndataBytes("RECONCILING", "wf-orphan", 1L, "recipe.rpmSetpoint", 1500.0))

        monitor.tick()
        val finding = rpmFinding(monitor)
        assertTrue(finding.breached, "the drift is still detected")
        assertNull(finding.reconciliation, "but no governance annotation — NDATA before NBIRTH was dropped")
    }
}
