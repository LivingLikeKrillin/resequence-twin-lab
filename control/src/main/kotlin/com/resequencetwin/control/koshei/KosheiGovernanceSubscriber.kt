package com.resequencetwin.control.koshei

import org.eclipse.tahu.message.SparkplugBPayloadDecoder

/**
 * Genuine Sparkplug B **host** consumer of koshei's governance Edge Node.
 *
 * Host semantics: NDATA is buffered/ignored until an NBIRTH has been observed for the node (a host must
 * not act on data before the birth that declares the metric set). On each valid NDATA, the
 * `Governance/LastEventType` header is mapped to a [ReconciliationState] and every touched `Setpoint/<key>`
 * metric is annotated with provenance (which koshei run) via [onReconciliation].
 *
 * Kept pure/injected: [onMessage] is both the Paho message target and the unit-test seam, so the
 * lifecycle needs no live broker.
 */
class KosheiGovernanceSubscriber(
    private val edge: String,
    private val onReconciliation: (logicalKey: String, ann: ReconciliationAnnotation) -> Unit,
) {
    @Volatile private var birthSeen = false

    /** Paho callback target + test seam. `topic` = `spBv1.0/{group}/{KIND}/{edge}`. */
    fun onMessage(topic: String, bytes: ByteArray) {
        // KIND is the element at index 1 after the "spBv1.0/" prefix: {group}/{KIND}/{edge}.
        val kind = topic.substringAfter("spBv1.0/").split("/").getOrNull(1) ?: return
        when (kind) {
            "NBIRTH" -> birthSeen = true
            "NDATA" -> if (birthSeen) applyNdata(bytes)   // ignore NDATA before NBIRTH (host rule)
        }
    }

    private fun applyNdata(bytes: ByteArray) {
        val p = SparkplugBPayloadDecoder().buildFromByteArray(bytes, null)
        val byName = p.metrics.associateBy { it.name }
        val type = byName["Governance/LastEventType"]?.value as? String ?: return
        val runId = byName["Governance/LastRunId"]?.value as? String ?: return
        val at = (byName["Governance/LastEventTimestamp"]?.value as? Number)?.toLong() ?: 0L
        val state = when (type) {
            "CONFIRMED" -> ReconciliationState.CLEARED
            "RECON_FAILED" -> ReconciliationState.RECONCILING_FAILED
            "RECONCILING" -> ReconciliationState.RECONCILING
            else -> return
        }
        p.metrics.filter { it.name.startsWith("Setpoint/") }.forEach {
            onReconciliation(it.name.removePrefix("Setpoint/"), ReconciliationAnnotation(state, runId, at))
        }
    }
}
