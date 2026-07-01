package com.resequencetwin.control.koshei

import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.slf4j.LoggerFactory

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
    private val log = LoggerFactory.getLogger(KosheiGovernanceSubscriber::class.java)
    @Volatile private var birthSeen = false

    /** Paho callback target + test seam. `topic` = `spBv1.0/{group}/{KIND}/{edge}`. */
    fun onMessage(topic: String, bytes: ByteArray) {
        try {
            // {group}/{KIND}/{edge} after the "spBv1.0/" prefix: KIND at index 1, edge at index 2.
            val parts = topic.substringAfter("spBv1.0/").split("/")
            val kind = parts.getOrNull(1) ?: return
            if (parts.getOrNull(2)?.let { it != edge } == true) return   // defensive: ignore cross-edge traffic
            when (kind) {
                "NBIRTH" -> { birthSeen = true; log.info("koshei NBIRTH received — host ready") }
                "NDATA" ->
                    if (birthSeen) applyNdata(bytes)          // ignore NDATA before NBIRTH (host rule)
                    else log.warn("koshei NDATA ignored — no NBIRTH observed yet (host not initialised)")
            }
        } catch (e: Exception) {
            // A malformed payload must never propagate into the Paho callback thread — Paho would tear
            // down the connection (and, with a retained bad message + auto-reconnect, loop). Log and drop,
            // mirroring koshei's SparkplugEdgeSession.onNcmd.
            log.warn("dropping bad koshei governance message on {}: {}", topic, e.toString())
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
        val keys = p.metrics.filter { it.name.startsWith("Setpoint/") }.map { it.name.removePrefix("Setpoint/") }
        log.info("koshei NDATA applied: type={} state={} runId={} setpoints={}", type, state, runId, keys)
        keys.forEach { onReconciliation(it, ReconciliationAnnotation(state, runId, at)) }
    }
}
