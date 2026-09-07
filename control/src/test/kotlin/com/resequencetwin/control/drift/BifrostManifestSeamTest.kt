package com.resequencetwin.control.drift

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bifrost -> resequence provenance data-contract seam (③).
 *
 * The fixtures under `src/test/resources/bifrost-seam/` were NOT hand-authored: they are the literal
 * output of bifrost's own `gates` CLI (`provenance publish`) run against a real, committed git repo —
 * copied here byte-for-byte (see `.gitattributes` in that directory, which disables git's text/CRLF
 * normalization so the fixture stays identical to the blob bifrost hashed).
 *
 * resequence has ZERO code dependency on bifrost: it only knows its own [RecipeManifest] data class
 * (7-field JSON, unknown fields ignored) and its own [RealSetpointDriftDetector], which recomputes
 * SHA-256 over the canonical bytes it reads and compares that to `manifest.contentSha256`. This test
 * proves the two sides agree purely on the wire bytes — no shared library, no laundered reference.
 */
class BifrostManifestSeamTest {
    private val mapper = jacksonObjectMapper()

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream("bifrost-seam/$name")) {
            "missing fixture bifrost-seam/$name"
        }.use { it.readBytes() }

    private val manifestBytes = resourceBytes("manifest.json")
    private val yamlBytes = resourceBytes("recipe-setpoints.yaml")
    private val manifest = mapper.readValue(manifestBytes, RecipeManifest::class.java)

    private val setpoints = listOf(RecipeSetpoint("recipe.rpmSetpoint", "ns=2;s=Recipe/Rpm", 1500.0, 1.0))

    @Test fun `bifrost-published manifest verifies through the twin`() {
        val reader = RecipeSetpointReader { 1200.0 }
        val f = RealSetpointDriftDetector(setpoints, reader, yamlBytes, manifest).detect().single()

        assertTrue(f.provenanceVerified)
        assertEquals(manifest.contentSha256, f.contentSha256)
        assertEquals(Sha256.hex(yamlBytes), f.contentSha256)
        assertEquals(manifest.defRef, f.defRef)
        assertEquals(40, f.defRef!!.length)
    }

    @Test fun `tampered canonical yields UNVERIFIED (no laundered reference)`() {
        val tampered = yamlBytes + 'X'.code.toByte()
        val reader = RecipeSetpointReader { 1200.0 }
        val f = RealSetpointDriftDetector(setpoints, reader, tampered, manifest).detect().single()

        assertEquals(false, f.provenanceVerified)
        assertNull(f.contentSha256)
        assertNull(f.defRef)
    }
}
