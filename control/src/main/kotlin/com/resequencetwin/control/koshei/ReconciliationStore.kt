package com.resequencetwin.control.koshei

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe side-store of reconciliation annotations keyed by logical setpoint key.
 *
 * Why a side-store: `DriftMonitor.tick()` rebuilds its report from fresh, immutable
 * [com.resequencetwin.control.drift.SetpointDriftFinding] instances every ~1s, while the subscriber
 * callback runs asynchronously on the Paho thread. Annotating a finding instance directly would be
 * overwritten on the next tick, so annotations live here and are merged into findings in `tick()`.
 */
class ReconciliationStore {
    private val byKey = ConcurrentHashMap<String, ReconciliationAnnotation>()
    fun put(logicalKey: String, ann: ReconciliationAnnotation) { byKey[logicalKey] = ann }
    fun get(logicalKey: String): ReconciliationAnnotation? = byKey[logicalKey]
}
