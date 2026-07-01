package com.resequencetwin.control.koshei

import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Twin-side MIRROR of the cross-repo Sparkplug wire contract (koshei's `SpbNodeCodec`). The twin has no
 * access to koshei's codec, so this test builds the payloads itself with Tahu, hard-coding the EXACT
 * wire-contract metric names — any divergence surfaces here as a decode/mapping failure.
 */
class KosheiGovernanceSubscriberTest {

    private val group = "Koshei"
    private val edge = "Governance"
    private fun nbirthTopic() = "spBv1.0/$group/NBIRTH/$edge"
    private fun ndataTopic() = "spBv1.0/$group/NDATA/$edge"

    /** Build a Sparkplug B NBIRTH payload (seq 0, no header values needed for the host rule). */
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

    @Test fun `NBIRTH then NDATA CONFIRMED fires a CLEARED annotation with provenance`() {
        val seen = mutableListOf<Pair<String, ReconciliationAnnotation>>()
        val sub = KosheiGovernanceSubscriber(edge) { key, ann -> seen += key to ann }

        sub.onMessage(nbirthTopic(), nbirthBytes())
        sub.onMessage(ndataTopic(), ndataBytes("CONFIRMED", "wf-1", 1234L, "recipe.rpmSetpoint", 1450.0))

        assertEquals(1, seen.size)
        val (key, ann) = seen.single()
        assertEquals("recipe.rpmSetpoint", key)
        assertEquals(ReconciliationState.CLEARED, ann.state)
        assertEquals("wf-1", ann.runId)
        assertEquals(1234L, ann.atMillis)
    }

    @Test fun `NDATA arriving before any NBIRTH is ignored (Sparkplug host rule)`() {
        val seen = mutableListOf<Pair<String, ReconciliationAnnotation>>()
        val sub = KosheiGovernanceSubscriber(edge) { key, ann -> seen += key to ann }

        sub.onMessage(ndataTopic(), ndataBytes("CONFIRMED", "wf-1", 1234L, "recipe.rpmSetpoint", 1450.0))

        assertTrue(seen.isEmpty())
    }

    @Test fun `RECON_FAILED maps to RECONCILING_FAILED`() {
        val seen = mutableListOf<Pair<String, ReconciliationAnnotation>>()
        val sub = KosheiGovernanceSubscriber(edge) { key, ann -> seen += key to ann }

        sub.onMessage(nbirthTopic(), nbirthBytes())
        sub.onMessage(ndataTopic(), ndataBytes("RECON_FAILED", "wf-2", 55L, "recipe.rpmSetpoint", 1450.0))

        assertEquals(ReconciliationState.RECONCILING_FAILED, seen.single().second.state)
        assertEquals("wf-2", seen.single().second.runId)
    }

    @Test fun `RECONCILING maps to RECONCILING`() {
        val seen = mutableListOf<Pair<String, ReconciliationAnnotation>>()
        val sub = KosheiGovernanceSubscriber(edge) { key, ann -> seen += key to ann }

        sub.onMessage(nbirthTopic(), nbirthBytes())
        sub.onMessage(ndataTopic(), ndataBytes("RECONCILING", "wf-3", 99L, "recipe.rpmSetpoint", 1500.0))

        assertEquals(ReconciliationState.RECONCILING, seen.single().second.state)
    }
}
