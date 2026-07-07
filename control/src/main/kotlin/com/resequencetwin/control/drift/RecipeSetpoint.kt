package com.resequencetwin.control.drift

import com.resequencetwin.control.koshei.ReconciliationAnnotation

/** Machine action tag for a recipe-setpoint reconciliation proposal. */
const val RECONCILE_SETPOINT = "RECONCILE_SETPOINT"

/** One canonical desired setpoint, parsed from koshei's model/recipe-setpoints.yaml. */
data class RecipeSetpoint(
    val key: String,
    val nodeId: String,
    val desired: Double,
    val tolerance: Double,
)

/**
 * One recipe-setpoint drift finding: the field value read from the shared OPC-UA sim differs from the
 * koshei Git canonical desired by more than [tolerance]. Advisory only (read-only twin, no write-back).
 */
data class SetpointDriftFinding(
    val key: String,
    val nodeId: String,
    val desired: Double,
    val observed: Double,
    val breached: Boolean,
    val proposal: ReconciliationProposal,
    /** Governance lifecycle annotation from koshei's Sparkplug surface; null until an event is seen. */
    val reconciliation: ReconciliationAnnotation? = null,
    val defRef: String? = null,              // provenance (from manifest) — null when UNVERIFIED
    val contentSha256: String? = null,       // the hash the TWIN computed — null when UNVERIFIED
    val provenanceVerified: Boolean = false, // true only when twin's hash == manifest.contentSha256
)
