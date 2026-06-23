package com.resequencetwin.control

import com.resequencetwin.control.ingest.DittoProjector
import com.resequencetwin.control.model.NormalizedEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Chunk 1 — twin state query test.
 *
 * After applying a known event stream, querying Ditto for "what is where now"
 * must return the correct current state.
 * Requires running Ditto on localhost:18080 (Docker compose).
 */
@SpringBootTest
@TestPropertySource(properties = [
    "ditto.base-url=http://localhost:18080",
    "ditto.auth-header=nginx:ditto",
    "spring.kafka.bootstrap-servers=localhost:19092",
])
class TwinStateQueryTest {

    @Autowired
    private lateinit var projector: DittoProjector

    companion object {
        private const val NAMESPACE = "rtw"
        private const val UNIT_ID   = "$NAMESPACE:query-unit-001"
        private const val RES_A_ID  = "$NAMESPACE:query-res-A"
        private const val RES_B_ID  = "$NAMESPACE:query-res-B"
    }

    @BeforeEach
    fun cleanup() {
        // Ensure shared policy exists
        projector.bootstrapPolicy()
        projector.deleteThing(UNIT_ID)
        projector.deleteThing(RES_A_ID)
        projector.deleteThing(RES_B_ID)
        // Reset in-process idempotency guard so each test starts clean
        projector.resetForTesting()
    }

    @Test
    fun afterKnownEventStreamTwinStateIsCorrect() {
        val moveId = UUID.randomUUID().toString()
        // simTs values are simulation-relative seconds (not epoch), matching generator output
        val t0 = 0.0
        val t1 = 10.0
        val t2 = 12.0

        // Pre-generate event IDs so causation references work
        val arriveAId  = UUID.randomUUID().toString()
        val occupyAId  = UUID.randomUUID().toString()
        val departAId  = UUID.randomUUID().toString()
        val releaseAId = UUID.randomUUID().toString()
        val arriveBId  = UUID.randomUUID().toString()
        val occupyBId  = UUID.randomUUID().toString()

        // Unit arrives at RES_A
        val arriveA = NormalizedEvent(arriveAId, "UnitArrived", t0, 0, UNIT_ID, RES_A_ID, moveId, UUID.randomUUID().toString())

        // Resource A becomes occupied
        val occupyA = NormalizedEvent(occupyAId, "ResourceOccupied", t0, 1, UNIT_ID, RES_A_ID, moveId, arriveAId)

        // Unit departs RES_A
        val departA = NormalizedEvent(departAId, "UnitDeparted", t1, 2, UNIT_ID, RES_A_ID, moveId, occupyAId)

        // Resource A is released
        val releaseA = NormalizedEvent(releaseAId, "ResourceReleased", t1, 3, UNIT_ID, RES_A_ID, moveId, departAId)

        // Unit arrives at RES_B
        val arriveB = NormalizedEvent(arriveBId, "UnitArrived", t2, 0, UNIT_ID, RES_B_ID, moveId, departAId)

        // Resource B becomes occupied
        val occupyB = NormalizedEvent(occupyBId, "ResourceOccupied", t2, 1, UNIT_ID, RES_B_ID, moveId, arriveBId)

        for (ev in listOf(arriveA, occupyA, departA, releaseA, arriveB, occupyB)) {
            projector.project(ev)
        }

        // --- Query Ditto state ---
        val unitState = projector.getThingFeatures(UNIT_ID)
        val resAState = projector.getThingFeatures(RES_A_ID)
        val resBState = projector.getThingFeatures(RES_B_ID)

        // Unit's current location must be RES_B (last UnitArrived)
        assertNotNull(unitState, "Unit Thing features should not be null")
        @Suppress("UNCHECKED_CAST")
        val locationFeature = unitState!!["location"] as Map<String, Any>
        assertNotNull(locationFeature, "Unit must have location feature")
        @Suppress("UNCHECKED_CAST")
        val locationProps = locationFeature["properties"] as Map<String, Any>
        assertEquals(RES_B_ID, locationProps["resourceId"],
            "Unit's current location must be RES_B after the event stream")

        // Unit status must be 'processing' (arrived at RES_B, not yet departed)
        @Suppress("UNCHECKED_CAST")
        val statusFeature = unitState["status"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val statusProps = statusFeature["properties"] as Map<String, Any>
        assertEquals("processing", statusProps["value"],
            "Unit status should be 'processing' after UnitArrived + ResourceOccupied")

        // Resource A occupancy must be 0 (released)
        assertNotNull(resAState, "Resource A Thing features should not be null")
        @Suppress("UNCHECKED_CAST")
        val resAOccupancy = resAState!!["occupancy"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val resAOccProps = resAOccupancy["properties"] as Map<String, Any>
        assertEquals(0, (resAOccProps["current"] as Number).toInt(),
            "Resource A occupancy must be 0 after ResourceReleased")

        // Resource B occupancy must be 1 (occupied)
        assertNotNull(resBState, "Resource B Thing features should not be null")
        @Suppress("UNCHECKED_CAST")
        val resBOccupancy = resBState!!["occupancy"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val resBOccProps = resBOccupancy["properties"] as Map<String, Any>
        assertEquals(1, (resBOccProps["current"] as Number).toInt(),
            "Resource B occupancy must be 1 after ResourceOccupied")
    }
}
