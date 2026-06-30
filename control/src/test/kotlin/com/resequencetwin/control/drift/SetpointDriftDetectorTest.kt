package com.resequencetwin.control.drift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetpointDriftDetectorTest {
    private val setpoints = listOf(
        RecipeSetpoint("recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm", 1500.0, 1.0),
        RecipeSetpoint("recipe.tempSetpoint", "ns=2;s=Recipe/Temp", 200.0, 1.0),
    )

    @Test fun `drifted setpoint yields a RECONCILE_SETPOINT finding`() {
        val reader = RecipeSetpointReader { id -> if (id.endsWith("Rpm")) 1200.0 else 200.0 }
        val findings = RealSetpointDriftDetector(setpoints, reader).detect()
        assertEquals(1, findings.size)
        val f = findings.single()
        assertEquals("recipe.rpmSetpoint", f.key)
        assertEquals(1200.0, f.observed)
        assertTrue(f.breached)
        assertEquals(RECONCILE_SETPOINT, f.proposal.action)
    }

    @Test fun `within tolerance yields no finding`() {
        val reader = RecipeSetpointReader { id -> if (id.endsWith("Rpm")) 1500.4 else 200.0 }
        assertTrue(RealSetpointDriftDetector(setpoints, reader).detect().isEmpty())
    }

    @Test fun `unreadable node is skipped (no crash, no finding)`() {
        val reader = RecipeSetpointReader { _ -> null }
        assertTrue(RealSetpointDriftDetector(setpoints, reader).detect().isEmpty())
    }

    @Test fun `disabled detector always empty`() {
        assertTrue(DisabledSetpointDriftDetector.detect().isEmpty())
    }
}
