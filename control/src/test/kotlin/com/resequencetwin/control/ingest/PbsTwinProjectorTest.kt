package com.resequencetwin.control.ingest

import com.resequencetwin.control.pbs.Lane
import com.resequencetwin.control.stream.BodyArrived
import com.resequencetwin.control.stream.LaneBlocked
import com.resequencetwin.control.stream.LivePbsProcessor
import com.resequencetwin.control.stream.ReleaseTick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [PbsTwinProjector.buildTwinPayload].
 *
 * No Spring context, no Ditto, no Kafka — drives a real [LivePbsProcessor] through
 * a controlled event sequence and asserts the resulting Ditto Thing payload structure.
 *
 * If a live round-trip (PUT to Ditto) test is ever needed, gate it like
 * [com.resequencetwin.control.TwinStateQueryTest] (requires Docker) — do NOT add it here.
 */
class PbsTwinProjectorTest {

    private val lanes = listOf(Lane("L1", 10), Lane("L2", 10), Lane("L3", 10))

    /** Convenience: build a [PbsTwinProjector] without Spring injection (no DittoProjector needed for pure fn). */
    private val projector = PbsTwinProjector.__forTest()

    private fun arrivedEvent(id: String, seq: Long, color: String = "RED", dueDateSeq: Int = 0) =
        BodyArrived(eventId = "e-$id-$seq", seq = seq, bodyId = id,
                    color = color, model = "M1", dueDateSeq = dueDateSeq)

    // ─── helpers ─────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun features(payload: Map<String, Any>)     = payload["features"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    private fun lanesFeature(payload: Map<String, Any>) = features(payload)["lanes"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    private fun laneProps(payload: Map<String, Any>)    = lanesFeature(payload)["properties"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    private fun occupancy(payload: Map<String, Any>)    = laneProps(payload)["occupancy"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    private fun blocked(payload: Map<String, Any>)      = laneProps(payload)["blocked"] as List<String>
    @Suppress("UNCHECKED_CAST")
    private fun kpiProps(payload: Map<String, Any>)     = (features(payload)["kpi"] as Map<String, Any>)["properties"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    private fun statusProps(payload: Map<String, Any>)  = (features(payload)["status"] as Map<String, Any>)["properties"] as Map<String, Any>

    // ─── structural assertions ────────────────────────────────────────────────

    @Test
    fun `buildTwinPayload contains required top-level keys`() {
        val proc = LivePbsProcessor(lanes)
        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 0L)

        assertTrue(payload.containsKey("policyId"), "payload must have policyId key")
        assertTrue(payload.containsKey("features"),  "payload must have features key")
        assertEquals("rtw:default-policy", payload["policyId"])
    }

    @Test
    fun `features map contains lanes, kpi, and status sub-features`() {
        val proc = LivePbsProcessor(lanes)
        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 0L)

        val feats = features(payload)
        assertTrue(feats.containsKey("lanes"),  "features must have lanes")
        assertTrue(feats.containsKey("kpi"),    "features must have kpi")
        assertTrue(feats.containsKey("status"), "features must have status")
    }

    // ─── lanes feature ────────────────────────────────────────────────────────

    @Test
    fun `lanes feature occupancy reflects lane occupant counts`() {
        val proc = LivePbsProcessor(lanes)
        proc.process(arrivedEvent("B1", seq = 1, color = "RED"))
        proc.process(arrivedEvent("B2", seq = 2, color = "BLUE"))
        proc.process(arrivedEvent("B3", seq = 3, color = "RED"))

        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 42L)
        val occ = occupancy(payload)

        // All 3 lanes must appear in occupancy
        assertTrue(occ.containsKey("L1"), "occupancy must contain L1")
        assertTrue(occ.containsKey("L2"), "occupancy must contain L2")
        assertTrue(occ.containsKey("L3"), "occupancy must contain L3")

        // Total occupants across all lanes must be 3
        val total = occ.values.sumOf { (it as Number).toInt() }
        assertEquals(3, total, "total occupancy must be 3")
    }

    @Test
    fun `lanes feature occupancy counts match snapshot laneOccupants sizes`() {
        val proc = LivePbsProcessor(lanes)
        for (i in 1..5) {
            proc.process(arrivedEvent("B$i", seq = i.toLong(), color = if (i % 2 == 0) "BLUE" else "RED"))
        }

        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 10L)
        val occ = occupancy(payload)

        for ((laneId, occupants) in snap.laneOccupants) {
            val count = (occ[laneId] as Number).toInt()
            assertEquals(occupants.size, count,
                "occupancy[$laneId] should equal laneOccupants[$laneId].size")
        }
    }

    @Test
    fun `lanes feature blocked list reflects blocked lanes (sorted)`() {
        val proc = LivePbsProcessor(lanes)
        proc.process(LaneBlocked(eventId = "blk-L3", seq = 1, laneId = "L3"))
        proc.process(LaneBlocked(eventId = "blk-L1", seq = 2, laneId = "L1"))

        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 0L)

        assertThat(blocked(payload)).containsExactly("L1", "L3")  // sorted order
    }

    @Test
    fun `lanes feature blocked list is empty when no lanes are blocked`() {
        val proc = LivePbsProcessor(lanes)
        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 0L)

        assertThat(blocked(payload)).isEmpty()
    }

    // ─── kpi feature ─────────────────────────────────────────────────────────

    @Test
    fun `kpi feature properties match snapshot KPI values`() {
        val proc = LivePbsProcessor(lanes)
        proc.process(arrivedEvent("B1", seq = 1, color = "RED",  dueDateSeq = 0))
        proc.process(arrivedEvent("B2", seq = 2, color = "BLUE", dueDateSeq = 1))
        proc.process(ReleaseTick(eventId = "tick-3", seq = 3))  // releases one body
        proc.process(ReleaseTick(eventId = "tick-4", seq = 4))  // releases another → colour change

        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 99L)
        val kpi = kpiProps(payload)

        assertEquals(snap.releases,       (kpi["releases"]       as Number).toInt())
        assertEquals(snap.colourChanges,  (kpi["colourChanges"]  as Number).toInt())
        assertEquals(snap.assemblyOutSize,(kpi["assemblyOut"]     as Number).toInt())
        assertEquals(snap.buffered,       (kpi["buffered"]        as Number).toInt())
        assertEquals(snap.rejected,       (kpi["rejected"]        as Number).toInt())
        assertEquals(snap.duplicates,     (kpi["duplicates"]      as Number).toInt())
        assertEquals(snap.outOfOrder,     (kpi["outOfOrder"]      as Number).toInt())
    }

    // ─── status feature ───────────────────────────────────────────────────────

    @Test
    fun `status feature properties has synthetic=true and correct updatedAtMs`() {
        val proc = LivePbsProcessor(lanes)
        val snap = proc.snapshot()
        val payload = projector.buildTwinPayload(snap, updatedAt = 42L)

        val status = statusProps(payload)
        assertEquals(true,  status["synthetic"])
        assertEquals(42L,   (status["updatedAtMs"] as Number).toLong())
    }

    @Test
    fun `updatedAtMs in status reflects exactly the parameter passed in`() {
        val proc = LivePbsProcessor(lanes)
        val snap = proc.snapshot()

        val payload1 = projector.buildTwinPayload(snap, updatedAt = 1L)
        val payload2 = projector.buildTwinPayload(snap, updatedAt = 9999L)

        assertEquals(1L,    (statusProps(payload1)["updatedAtMs"] as Number).toLong())
        assertEquals(9999L, (statusProps(payload2)["updatedAtMs"] as Number).toLong())
    }

    // ─── determinism ─────────────────────────────────────────────────────────

    @Test
    fun `same snapshot and same updatedAt produce identical payload (deep-equals)`() {
        val proc = LivePbsProcessor(lanes)
        proc.process(arrivedEvent("B1", seq = 1, color = "RED"))
        proc.process(LaneBlocked(eventId = "blk-L2", seq = 2, laneId = "L2"))
        val snap = proc.snapshot()

        val payload1 = projector.buildTwinPayload(snap, updatedAt = 77L)
        val payload2 = projector.buildTwinPayload(snap, updatedAt = 77L)

        assertThat(payload1 as Map<*, *>).isEqualTo(payload2 as Map<*, *>)
    }

    @Test
    fun `different updatedAt produces different status but same lane and kpi data`() {
        val proc = LivePbsProcessor(lanes)
        proc.process(arrivedEvent("B1", seq = 1))
        val snap = proc.snapshot()

        val p1 = projector.buildTwinPayload(snap, updatedAt = 1L)
        val p2 = projector.buildTwinPayload(snap, updatedAt = 2L)

        assertThat((statusProps(p1)["updatedAtMs"] as Number).toLong())
            .isNotEqualTo((statusProps(p2)["updatedAtMs"] as Number).toLong())

        // lanes and kpi are snapshot-derived → equal for same snapshot
        assertThat(lanesFeature(p1) as Map<*, *>).isEqualTo(lanesFeature(p2) as Map<*, *>)
        assertThat(kpiProps(p1) as Map<*, *>).isEqualTo(kpiProps(p2) as Map<*, *>)
    }
}
