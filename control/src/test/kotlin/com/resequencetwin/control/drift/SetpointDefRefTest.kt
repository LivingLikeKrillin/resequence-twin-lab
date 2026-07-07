package com.resequencetwin.control.drift

import kotlin.test.Test
import kotlin.test.assertEquals

class SetpointDefRefTest {
    private val setpoints = listOf(RecipeSetpoint("recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm", 1500.0, 1.0))

    @Test fun `finding carries the injected defRef`() {
        val reader = RecipeSetpointReader { 1200.0 }
        val findings = RealSetpointDriftDetector(setpoints, reader, defRef = "cafebabe").detect()
        assertEquals("cafebabe", findings.single().defRef)
    }
}
