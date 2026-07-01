package com.resequencetwin.control.koshei

import com.resequencetwin.control.drift.DriftMonitor
import com.resequencetwin.control.drift.ObservedConfig
import com.resequencetwin.control.drift.ObservedKpi
import com.resequencetwin.control.drift.RECONCILE_SETPOINT
import com.resequencetwin.control.drift.ReconciliationProposal
import com.resequencetwin.control.drift.SetpointDriftDetector
import com.resequencetwin.control.drift.SetpointDriftFinding
import com.resequencetwin.control.drift.SimulatedConfigSource
import com.resequencetwin.control.drift.SimulatedTelemetrySource
import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.LivePbsProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves a subscriber-written annotation survives successive `tick()` rebuilds and is merged onto the
 * freshly-detected (immutable) findings — the exact lifecycle the outbound-emit gate's T1 asserts.
 */
class ReconciliationLifecycleTest {

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))

    /** Always reports one DETECTED drift finding for recipe.rpmSetpoint (no reconciliation of its own). */
    private val detector = object : SetpointDriftDetector {
        override fun detect(): List<SetpointDriftFinding> = listOf(
            SetpointDriftFinding(
                key = "recipe.rpmSetpoint", nodeId = "ns=2;s=Recipe/Rpm",
                desired = 1500.0, observed = 1200.0, breached = true,
                proposal = ReconciliationProposal(RECONCILE_SETPOINT, "drift"),
            )
        )
    }

    private fun monitor(store: ReconciliationStore) = DriftMonitor(
        LivePbsProcessor(lanes),
        SimulatedConfigSource(ObservedConfig(lanes = mapOf("L1" to 10, "L2" to 10, "L3" to 10), blocked = emptySet())),
        SimulatedTelemetrySource(ObservedKpi(0, 0, 0)),
        detector,
        reconciliationStore = store,
        enabled = true, ewmaAlpha = 0.5, residualThreshold = 1.0,
    )

    private fun setpointFinding(m: DriftMonitor) =
        m.currentReport().setpointFindings.single { it.key == "recipe.rpmSetpoint" }

    @Test fun `annotation is merged into findings and survives successive ticks`() {
        val store = ReconciliationStore()
        val m = monitor(store)

        // No annotation yet -> finding surfaces DETECTED with a null reconciliation.
        m.tick()
        assertNull(setpointFinding(m).reconciliation)

        // koshei parks the run -> RECONCILING annotation, merged on the next tick.
        store.put("recipe.rpmSetpoint", ReconciliationAnnotation(ReconciliationState.RECONCILING, "wf-1", 100L))
        m.tick()
        val reconciling = setpointFinding(m).reconciliation!!
        assertEquals(ReconciliationState.RECONCILING, reconciling.state)
        assertEquals("wf-1", reconciling.runId)

        // Governed write-back confirmed -> CLEARED, surviving into a later tick.
        store.put("recipe.rpmSetpoint", ReconciliationAnnotation(ReconciliationState.CLEARED, "wf-1", 200L))
        m.tick()
        assertEquals(ReconciliationState.CLEARED, setpointFinding(m).reconciliation!!.state)
    }
}
