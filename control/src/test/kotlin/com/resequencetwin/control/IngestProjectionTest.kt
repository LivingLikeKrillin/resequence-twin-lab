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

/**
 * Chunk 1 — idempotency test.
 *
 * Verifies the in-process idempotency guard (not HTTP call count); Ditto PUT idempotency is inherent.
 * Applying the SAME eventId twice MUST project to Ditto only once.
 * Requires running Ditto on localhost:18080 (Docker compose).
 */
@SpringBootTest
@TestPropertySource(properties = [
    "ditto.base-url=http://localhost:18080",
    "ditto.auth-header=nginx:ditto",
    "spring.kafka.bootstrap-servers=localhost:19092",
])
class IngestProjectionTest {

    @Autowired
    private lateinit var projector: DittoProjector

    companion object {
        private const val NAMESPACE = "rtw"
        private const val UNIT_ID   = "$NAMESPACE:test-unit-idem"
        private const val RES_ID    = "$NAMESPACE:test-res-idem"
    }

    @BeforeEach
    fun cleanup() {
        // Ensure shared policy exists
        projector.bootstrapPolicy()
        // Delete Things to start fresh (ignore 404)
        projector.deleteThing(UNIT_ID)
        projector.deleteThing(RES_ID)
        // Reset in-process idempotency guard so each test starts clean
        projector.resetForTesting()
    }

    @Test
    fun applyingTheSameEventIdTwiceProjectsTodittoOnlyOnce() {
        val sharedEventId = UUID.randomUUID().toString()
        val event = NormalizedEvent(
            eventId      = sharedEventId,
            eventType    = "UnitArrived",
            simTs        = 0.0,
            seq          = 0,
            unitId       = UNIT_ID,
            resourceId   = RES_ID,
            moveId       = UUID.randomUUID().toString(),
            causationId  = sharedEventId
        )

        // First application
        projector.project(event)
        // Second application — same eventId
        projector.project(event)

        // Ditto Thing should exist exactly once (GET returns 200)
        val statusCode = projector.getThingStatus(UNIT_ID)
        assertEquals(200, statusCode, "Unit Thing should exist in Ditto after projection")

        // The processed-event count for this eventId must be 1 (idempotent)
        val processedCount = projector.getProcessedEventCount(sharedEventId)
        assertEquals(1L, processedCount,
            "Event projected twice but should have been applied to Ditto only once")
    }
}
