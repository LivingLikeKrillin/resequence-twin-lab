package com.resequencetwin.control.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

/**
 * EmbeddedKafka end-to-end integration test for the PBS streaming pipeline.
 *
 * ## What is tested
 * 1. End-to-end: raw JSON → wire-format deserialization → LivePbsProcessor → correct snapshot.
 * 2. Replay / idempotency: replaying identical eventIds increments `duplicates` only, leaves
 *    substantive state unchanged.
 * 3. Poison record: a malformed JSON record is silently skipped by ErrorHandlingDeserializer,
 *    the subsequent valid record is still processed.
 *
 * ## Broker wiring
 * EmbeddedKafka provides an in-memory Kafka broker.  The consumer factory in PbsStreamConfig
 * reads `spring.kafka.bootstrap-servers` — TestPropertySource overrides it to point at the
 * embedded broker address injected by Spring's `${spring.embedded.kafka.brokers}` placeholder.
 *
 * ## DittoProjector
 * DittoProjector is NOT mocked: its @PostConstruct catches connection errors and only logs a
 * warning, so the application context loads cleanly without Docker/Ditto.  We leave it as-is
 * to avoid narrowing the test's context footprint unnecessarily.
 *
 * ## Consumer group isolation
 * Each test class run uses a dedicated group id (`pbs-it-group`) distinct from the production
 * group (`resequence-twin-control`), preventing offset collisions across test and production runs.
 * @DirtiesContext ensures a fresh context (and therefore a fresh LivePbsProcessor with zeroed
 * counters) per test class invocation.
 *
 * ## Sync strategy
 * After producing, [awaitSnapshot] polls the processor's snapshot in a bounded loop (max 10 s,
 * 100 ms intervals) until an expected condition holds.  No fixed Thread.sleep is used as the
 * sole synchronisation primitive.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = ["pbs-events"],
)
@TestPropertySource(
    properties = [
        // Point the consumer factory at the embedded broker (resolved by Spring's Kafka test support)
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        // Dedicated test group: prevents offset collisions with any other test or production run
        "spring.kafka.consumer.group-id=pbs-it-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        // Explicit String serializers so Spring Boot auto-configures a KafkaTemplate<String,String>
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        // Minimal server port: avoids collision with other Spring Boot test contexts
        "server.port=0",
    ]
)
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PbsStreamIntegrationTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Injected beans
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The singleton LivePbsProcessor that PbsEventListener writes to.
     * Reading snapshot() here reflects events the Kafka listener has processed.
     */
    @Autowired
    private lateinit var processor: LivePbsProcessor

    /**
     * String-keyed, String-valued template so we can send raw JSON strings.
     * This deliberately exercises on-the-wire polymorphic deserialization:
     * the consumer's JsonDeserializer uses the JSON `type` field (not Kafka headers).
     */
    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    /**
     * Used to wait for the listener container to be assigned its partition before
     * we start producing — avoids a race between producer and not-yet-assigned consumer.
     */
    @Autowired
    private lateinit var registry: KafkaListenerEndpointRegistry

    @Autowired
    private lateinit var embeddedKafka: EmbeddedKafkaBroker

    // ──────────────────────────────────────────────────────────────────────────
    // Test utilities
    // ──────────────────────────────────────────────────────────────────────────

    private fun waitForAssignment() {
        // Block until the listener container has been assigned its 1 partition.
        // ContainerTestUtils.waitForAssignment throws AssertionError if timeout (default 60 s) is exceeded.
        registry.listenerContainers.forEach { container ->
            ContainerTestUtils.waitForAssignment(container, embeddedKafka.partitionsPerTopic)
        }
    }

    /**
     * Polls [condition] every 100 ms for up to [maxMs] milliseconds.
     * Returns the last snapshot when condition is satisfied.
     * Fails with AssertionError if the condition is not met within the timeout.
     */
    private fun awaitSnapshot(maxMs: Long = 10_000L, condition: (LiveSnapshot) -> Boolean): LiveSnapshot {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val snap = processor.snapshot()
            if (condition(snap)) return snap
            Thread.sleep(100)
        }
        val snap = processor.snapshot()
        assertThat(condition(snap))
            .overridingErrorMessage { "Timed out waiting for condition. Last snapshot: $snap" }
            .isTrue()
        return snap
    }

    private fun produce(key: String, json: String) {
        kafkaTemplate.send("pbs-events", key, json).get() // .get() to flush/await broker ack
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario helpers — raw JSON matching the PbsEvent wire format
    // ──────────────────────────────────────────────────────────────────────────

    // dueDateSeq=0 → immediately overdue on first ReleaseTick (assemblyOut.size=0, lag=0)
    // This makes release selection deterministic: overdue-override fires before scoring.
    private val bodyB1 = """{"type":"BodyArrived","eventId":"it-e1","seq":1,"bodyId":"B1","color":"RED","model":"M1","options":[],"dueDateSeq":0}"""
    private val bodyB2 = """{"type":"BodyArrived","eventId":"it-e2","seq":2,"bodyId":"B2","color":"BLUE","model":"M1","options":[],"dueDateSeq":0}"""
    private val bodyB3 = """{"type":"BodyArrived","eventId":"it-e3","seq":3,"bodyId":"B3","color":"RED","model":"M1","options":[],"dueDateSeq":5}"""
    private val tick1  = """{"type":"ReleaseTick","eventId":"it-t1","seq":10}"""
    private val tick2  = """{"type":"ReleaseTick","eventId":"it-t2","seq":11}"""
    private val block1 = """{"type":"LaneBlocked","eventId":"it-blk1","seq":20,"laneId":"L1"}"""
    private val unblock1 = """{"type":"LaneUnblocked","eventId":"it-ublk1","seq":21,"laneId":"L1"}"""

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: End-to-end
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * End-to-end scenario:
     *
     * - 3 BodyArrived → 3 lanes (L1/L2/L3, capacity 10 each), each lane gets 1 body
     * - 2 ReleaseTick → 2 bodies released (B1 and B2 have dueDateSeq=0, both overdue
     *   on ticks 1 and 2 since assemblyOut grows from 0→1→2, lag always ≤0)
     * - LaneBlocked(L1) then LaneUnblocked(L1) → blockedLanes must be empty at the end
     *
     * Exact assertions: releases==2, assemblyOutSize==2, 1 body remains, duplicates==0,
     * blockedLanes is empty.
     * colourChanges is asserted ≥0 and ≤2 (policy-driven; not pinned to exact value here
     * because it depends on which two lanes' front bodies are selected by overdue-override).
     */
    @Test
    @Order(1)
    fun `end-to-end - arrivals releases block-unblock reflect correct snapshot`() {
        waitForAssignment()

        produce("k1", bodyB1)
        produce("k2", bodyB2)
        produce("k3", bodyB3)
        produce("k4", tick1)
        produce("k5", tick2)
        produce("k6", block1)
        produce("k7", unblock1)

        // Wait until all 7 events have been processed (7 unique eventIds → duplicates==0,
        // and 2 ticks processed means releases==2).
        val snap = awaitSnapshot { it.releases == 2 && it.blockedLanes.isEmpty() }

        assertThat(snap.releases).isEqualTo(2)
        assertThat(snap.assemblyOutSize).isEqualTo(2)
        assertThat(snap.laneOccupants.values.flatten()).hasSize(1) // 3 arrived - 2 released = 1 remaining
        assertThat(snap.duplicates).isEqualTo(0)
        assertThat(snap.rejected).isEqualTo(0)
        assertThat(snap.blockedLanes).isEmpty()
        // colourChanges max is 1, not 2: the FIRST release never counts as a change
        // because lastReleasedColour starts null, so only the second release can differ.
        assertThat(snap.colourChanges).isBetween(0, 1)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Replay / idempotency
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Replay / idempotency scenario:
     *
     * Re-produces the exact same 7 events (identical eventIds) after test 1 has consumed them.
     * The processor must:
     * - Increment `duplicates` by exactly 7 (one per replayed event)
     * - Leave releases, assemblyOutSize, laneOccupants, and blockedLanes unchanged
     *
     * This proves replay-safety: a Kafka consumer restart or upstream re-delivery cannot
     * corrupt state.
     *
     * NOTE: This test runs in the same @DirtiesContext class as test 1 (method order 1→2),
     * so it picks up the snapshot AFTER test 1's events are settled and re-verifies invariants.
     */
    @Test
    @Order(2)
    fun `replay idempotency - re-processing same eventIds increments duplicates only`() {
        waitForAssignment()

        // First, ensure the base state is settled from test 1 (releases==2).
        // We verify this as a precondition to make the assertion unambiguous.
        val snapBefore = awaitSnapshot { it.releases == 2 && it.duplicates == 0 }

        // Fail fast if the inherited state from Test 1 is not what this test expects.
        // Without this guard, a wrong precondition would surface as a confusing await-timeout.
        check(snapBefore.releases == 2 && snapBefore.duplicates == 0) {
            "Test 2 precondition failed: expected releases=2, duplicates=0 from Test 1; got $snapBefore"
        }

        // Replay all 7 original events (same eventIds)
        val replayedCount = 7
        produce("r1", bodyB1)
        produce("r2", bodyB2)
        produce("r3", bodyB3)
        produce("r4", tick1)
        produce("r5", tick2)
        produce("r6", block1)
        produce("r7", unblock1)

        // Wait until duplicates has risen by replayedCount
        val snapAfter = awaitSnapshot { it.duplicates == snapBefore.duplicates + replayedCount }

        // Substantive state unchanged
        assertThat(snapAfter.duplicates).isEqualTo(snapBefore.duplicates + replayedCount)
        assertThat(snapAfter.releases).isEqualTo(snapBefore.releases)
        assertThat(snapAfter.assemblyOutSize).isEqualTo(snapBefore.assemblyOutSize)
        assertThat(snapAfter.laneOccupants).isEqualTo(snapBefore.laneOccupants)
        assertThat(snapAfter.colourChanges).isEqualTo(snapBefore.colourChanges)
        assertThat(snapAfter.blockedLanes).isEqualTo(snapBefore.blockedLanes)
        // Replayed (valid) events are caught by the idempotency guard BEFORE validation,
        // so rejected must not change — proving no double-counted validation failures.
        assertThat(snapAfter.rejected).isEqualTo(snapBefore.rejected)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: Poison record
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Poison-record scenario:
     *
     * Produces one malformed JSON record (truncated — Jackson cannot parse it), then
     * a subsequent valid BodyArrived event.  ErrorHandlingDeserializer must skip the
     * poison record without killing the partition consumer; the valid event must still
     * be processed.
     *
     * To keep this test independent of test-ordering side effects (snapshot from tests 1/2),
     * we assert on the *delta* in releases/laneOccupants before and after the poison+valid pair.
     * The valid event is a BodyArrived (no release tick), so we assert the lane count increases
     * by 1 and duplicates do NOT change (the new eventId is fresh).
     *
     * Note: this test runs in the same context as tests 1 and 2; the processor already has
     * state from those tests (releases==2, duplicates==7, 1 lane occupant).  We use a unique
     * eventId (it-poison-valid-B9) to avoid the idempotency guard.
     */
    @Test
    @Order(3)
    fun `poison record - malformed JSON skipped, subsequent valid event processed`() {
        waitForAssignment()

        // Capture state before the poison/valid pair
        // Wait for test-2's final state to settle first (idempotency test may still be consuming)
        val snapBefore = awaitSnapshot { it.duplicates == 7 }
        val occupantsBefore = snapBefore.laneOccupants.values.flatten().size
        val duplicatesBefore = snapBefore.duplicates

        // Truncated JSON — Jackson parse will fail; ErrorHandlingDeserializer will skip it
        val poison = """{"type":"BodyArrived","eventId":"""

        // Valid BodyArrived with a fresh eventId not seen in tests 1/2
        val validAfterPoison = """{"type":"BodyArrived","eventId":"it-poison-valid-B9","seq":99,"bodyId":"B9","color":"WHITE","model":"M9","options":[],"dueDateSeq":5}"""

        produce("poison-key", poison)
        produce("valid-after-poison-key", validAfterPoison)

        // The valid event must have been processed: lane occupant count increases by 1
        val snapAfter = awaitSnapshot { it.laneOccupants.values.flatten().size == occupantsBefore + 1 }

        // Poison record did NOT increment duplicates (it was deserialization-rejected, never reached process())
        assertThat(snapAfter.duplicates).isEqualTo(duplicatesBefore)
        // Valid event processed: one more body in a lane
        assertThat(snapAfter.laneOccupants.values.flatten()).hasSize(occupantsBefore + 1)
        assertThat(snapAfter.laneOccupants.values.flatten()).contains("B9")
    }
}
