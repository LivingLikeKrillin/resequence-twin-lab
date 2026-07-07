package com.resequencetwin.control.drift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetpointDefRefTest {
    private val setpoints = listOf(RecipeSetpoint("recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm", 1500.0, 1.0))
    private val bytes = "endpoint: x\n".toByteArray()
    private val goodSha = Sha256.hex(bytes)

    @Test fun `verified finding carries twin-computed contentSha256 and defRef`() {
        val reader = RecipeSetpointReader { 1200.0 }
        val manifest = RecipeManifest(defRef = "cafe".repeat(10), contentSha256 = goodSha)
        val f = RealSetpointDriftDetector(setpoints, reader, bytes, manifest).detect().single()
        assertTrue(f.provenanceVerified)
        assertEquals(goodSha, f.contentSha256)
        assertEquals("cafe".repeat(10), f.defRef)
    }

    @Test fun `tampered manifest yields UNVERIFIED finding, no laundered reference`() {
        val reader = RecipeSetpointReader { 1200.0 }
        val manifest = RecipeManifest(defRef = "cafe".repeat(10), contentSha256 = "WRONG")
        val f = RealSetpointDriftDetector(setpoints, reader, bytes, manifest).detect().single()
        assertEquals(false, f.provenanceVerified)
        assertNull(f.contentSha256)
        assertNull(f.defRef)
    }
}
