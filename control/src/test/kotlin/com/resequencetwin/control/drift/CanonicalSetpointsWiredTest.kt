package com.resequencetwin.control.drift

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wires the COMMITTED canonical contract (model/recipe-setpoints.yaml) through the real
 * [RealSetpointDriftDetector]. This proves the twin can compute setpoint drift against the
 * Git-canonical desired state self-contained — without koshei and without a live OPC-UA endpoint
 * (the read side is a [RecipeSetpointReader] lambda fake, matching SetpointDriftDetectorTest).
 *
 * Before this, model/recipe-setpoints.yaml did not exist, so RealSetpointDriftDetector was inert.
 */
class CanonicalSetpointsWiredTest {

    /** The canonical file lives at repo-root/model/; resolve it whether tests run from control/ or root. */
    private fun canonicalFile(): File =
        listOf("model/recipe-setpoints.yaml", "../model/recipe-setpoints.yaml")
            .map(::File)
            .firstOrNull(File::exists)
            ?: error("model/recipe-setpoints.yaml not found (cwd=${File(".").absolutePath})")

    @Test
    fun `committed canonical file parses into well-formed setpoints`() {
        val loaded = CanonicalSetpointsFile.load(canonicalFile().path)

        assertTrue(loaded.endpoint.isNotBlank(), "endpoint must be set")
        assertTrue(loaded.setpoints.isNotEmpty(), "at least one setpoint")
        assertTrue(
            "recipe.rpmSetpoint" in loaded.setpoints.map { it.key },
            "expected recipe.rpmSetpoint among ${loaded.setpoints.map { it.key }}",
        )
        loaded.setpoints.forEach { sp ->
            assertTrue(sp.nodeId.startsWith("ns="), "nodeId must be a full OPC-UA NodeId: ${sp.nodeId}")
            assertTrue(sp.tolerance > 0.0, "tolerance must be positive: ${sp.key}")
        }
    }

    @Test
    fun `canonical setpoints drive real drift detection when observed is beyond tolerance`() {
        val loaded = CanonicalSetpointsFile.load(canonicalFile().path)
        val rpm = loaded.setpoints.first { it.key == "recipe.rpmSetpoint" }

        // Fake OPC-UA read: the rpm node reads well beyond tolerance (drift); every other node reads
        // exactly its desired (within tolerance -> no finding).
        val reader = RecipeSetpointReader { nodeId ->
            if (nodeId == rpm.nodeId) rpm.desired - (rpm.tolerance + 100.0)
            else loaded.setpoints.first { it.nodeId == nodeId }.desired
        }

        val findings = RealSetpointDriftDetector(loaded.setpoints, reader).detect()

        assertEquals(1, findings.size, "only the rpm setpoint drifted")
        val f = findings.single()
        assertEquals("recipe.rpmSetpoint", f.key)
        assertEquals(rpm.desired, f.desired)
        assertTrue(f.breached)
        assertEquals(RECONCILE_SETPOINT, f.proposal.action)
    }
}
