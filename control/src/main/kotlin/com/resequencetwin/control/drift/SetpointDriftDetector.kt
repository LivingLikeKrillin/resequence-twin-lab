package com.resequencetwin.control.drift

import kotlin.math.abs

/** Computes recipe-setpoint drift findings. */
interface SetpointDriftDetector {
    fun detect(): List<SetpointDriftFinding>
}

/** Disabled (default): no recipe drift. Wired when pbs.drift.recipe.enabled=false. */
object DisabledSetpointDriftDetector : SetpointDriftDetector {
    override fun detect(): List<SetpointDriftFinding> = emptyList()
}

/**
 * Reads each canonical node from the shared OPC-UA sim and flags |observed - desired| > tolerance.
 * Read-only: never writes the field (the twin refuses write-back; koshei is the only governed writer).
 */
class RealSetpointDriftDetector(
    private val setpoints: List<RecipeSetpoint>,
    private val reader: RecipeSetpointReader,
    private val canonicalBytes: ByteArray? = null,
    private val manifest: RecipeManifest? = null,
) : SetpointDriftDetector {
    override fun detect(): List<SetpointDriftFinding> {
        val computed = canonicalBytes?.let { Sha256.hex(it) }
        val verified = computed != null && manifest != null && manifest.contentSha256.isNotBlank() && computed == manifest.contentSha256
        return setpoints.mapNotNull { sp ->
            val observed = reader.read(sp.nodeId) ?: return@mapNotNull null
            if (abs(observed - sp.desired) <= sp.tolerance) return@mapNotNull null
            SetpointDriftFinding(
                key = sp.key, nodeId = sp.nodeId, desired = sp.desired, observed = observed, breached = true,
                proposal = ReconciliationProposal(
                    action = RECONCILE_SETPOINT,
                    description = "setpoint '${sp.key}' drifted: observed $observed vs desired ${sp.desired} " +
                        "(tolerance ${sp.tolerance}); reconcile via koshei governed write-back",
                ),
                defRef = if (verified) manifest!!.defRef else null,
                contentSha256 = if (verified) computed else null,
                provenanceVerified = verified,
            )
        }
    }
}
