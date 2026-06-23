package com.resequencetwin.control.ingest

import com.resequencetwin.control.stream.LivePbsProcessor
import com.resequencetwin.control.stream.LiveSnapshot
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scheduled component that projects the live PBS state to a Ditto twin Thing `rtw:pbs-line`.
 *
 * ## Separation of concerns
 * Ditto projection runs on a dedicated scheduler thread, **not** on the Kafka consumer thread.
 * This means [PbsEventListener] never touches Ditto directly, which keeps ingestion latency
 * deterministic: a Ditto outage (network partition, restart) cannot stall or crash the Kafka
 * consumer. The scheduler thread absorbs all Ditto-related faults in isolation.
 *
 * ## Scheduler activation
 * Scheduling is enabled globally via [SchedulingConfig]. Disable for unit/CI runs by setting
 * `pbs.twin.enabled=false` in `src/test/resources/application.properties` so no Ditto calls
 * are made when Docker is not available.
 *
 * @param dittoProjector shared Ditto client; may be null when constructed via [__forTest].
 * @param processor      shared live PBS state machine.
 * @param baseUrl        Ditto base URL (from application properties).
 * @param enabled        master on/off switch; when false [tick] is a no-op.
 * @param intervalMs     scheduler fixed-delay in ms (default 1000).
 */
@Component
class PbsTwinProjector(
    private val dittoProjector: DittoProjector?,
    private val processor: LivePbsProcessor?,
    @Value("\${ditto.base-url:http://localhost:18080}") private val baseUrl: String,
    @Value("\${pbs.twin.enabled:true}") private val enabled: Boolean,
) {

    companion object {
        private val log = LoggerFactory.getLogger(PbsTwinProjector::class.java)
        private const val THING_ID = "rtw:pbs-line"

        /**
         * Creates a minimal [PbsTwinProjector] for unit-testing the pure [buildTwinPayload]
         * function without a Spring context or a live Ditto instance.
         *
         * **Test use only.** The double-underscore prefix signals that this is a test seam,
         * not part of the production API.
         */
        fun __forTest(): PbsTwinProjector =
            PbsTwinProjector(
                dittoProjector = null,
                processor = null,
                baseUrl = "http://localhost:18080",
                enabled = false,
            )
    }

    /**
     * Guards one-time policy bootstrap: set to `true` only after [DittoProjector.bootstrapPolicy]
     * succeeds. Left `false` on failure so the next [project] call retries — this handles the
     * case where Ditto is down at the first tick but recovers later.
     *
     * **Dual-ownership is intentional.** [DittoProjector.init] also calls [DittoProjector.bootstrapPolicy]
     * eagerly via `@PostConstruct`, but does NOT retry on failure. This field + the guarded retry in
     * [project] provide the resilience for the "Ditto unavailable at startup, becomes available later"
     * case. [DittoProjector.bootstrapPolicy] is idempotent, so the redundant call on a clean startup
     * (when `@PostConstruct` already succeeded) is harmless.
     */
    private val policyBootstrapped = AtomicBoolean(false)

    // ─── scheduled projection ─────────────────────────────────────────────────

    /**
     * Fires every [intervalMs] ms (default 1 s). Delegates to [project] with a fresh snapshot
     * and the current wall-clock epoch-millisecond timestamp so consumers can detect stale twins.
     *
     * Early-returns when [enabled] is false so the scheduler bean is harmless in test contexts.
     */
    @Scheduled(fixedDelayString = "\${pbs.twin.project-interval-ms:1000}")
    fun tick() {
        if (!enabled) return
        val proc = processor ?: return
        val ditto = dittoProjector ?: return
        val snapshot = proc.snapshot()
        project(snapshot, System.currentTimeMillis(), ditto)
    }

    // ─── projection logic ─────────────────────────────────────────────────────

    /**
     * Builds the twin payload and PUTs it to Ditto. Network failures are caught and
     * logged as warnings — a Ditto outage must not crash the scheduler or the app.
     *
     * ## One-time policy bootstrap
     * [DittoProjector.bootstrapPolicy] is called **at most once per application lifetime**.
     * The [policyBootstrapped] flag is only set to `true` on success, so a transient Ditto
     * outage on the first tick does not permanently suppress future bootstrap attempts — the
     * next tick will retry until it succeeds.
     */
    fun project(
        snapshot: LiveSnapshot,
        updatedAt: Long,
        ditto: DittoProjector = requireNotNull(dittoProjector) {
            "DittoProjector not available — pass the explicit overload in tests"
        },
    ) {
        val payload = buildTwinPayload(snapshot, updatedAt)
        try {
            if (!policyBootstrapped.get()) {
                ditto.bootstrapPolicy()
                policyBootstrapped.set(true)
            }
            ditto.putThing(THING_ID, payload)
        } catch (e: IOException) {
            log.warn("PBS twin projection failed (Ditto unreachable?): {}", e.message)
        } catch (e: Exception) {
            log.warn("PBS twin projection unexpected error: {}", e.message)
        }
    }

    // ─── pure mapping ─────────────────────────────────────────────────────────

    /**
     * Builds the Ditto Thing body from a [LiveSnapshot] without any I/O or wall-clock calls.
     *
     * The [updatedAt] parameter is accepted as an argument (not read from the clock) so the
     * function is **deterministic and testable**: same inputs → identical output.
     *
     * Payload structure:
     * ```
     * {
     *   "policyId": "rtw:default-policy",
     *   "features": {
     *     "lanes":  { "properties": { "occupancy": { <laneId>: <count> }, "blocked": [<sorted>] } },
     *     "kpi":    { "properties": { "releases": N, "colourChanges": N, ... } },
     *     "status": { "properties": { "synthetic": true, "updatedAtMs": N } }
     *   }
     * }
     * ```
     */
    fun buildTwinPayload(snapshot: LiveSnapshot, updatedAt: Long): Map<String, Any> {
        // Occupancy: sorted by laneId for deterministic output
        val occupancy: Map<String, Int> = snapshot.laneOccupants
            .entries
            .sortedBy { it.key }
            .associate { (laneId, occupants) -> laneId to occupants.size }

        // Blocked lanes: sorted for deterministic output
        val blockedSorted: List<String> = snapshot.blockedLanes.sorted()

        return mapOf(
            "policyId" to "rtw:default-policy",
            "features" to mapOf(
                "lanes" to mapOf(
                    "properties" to mapOf(
                        "occupancy" to occupancy,
                        "blocked"   to blockedSorted,
                    )
                ),
                "kpi" to mapOf(
                    "properties" to mapOf(
                        "releases"      to snapshot.releases,
                        "colourChanges" to snapshot.colourChanges,
                        "assemblyOut"   to snapshot.assemblyOutSize,
                        "buffered"      to snapshot.buffered,
                        "rejected"      to snapshot.rejected,
                        "duplicates"    to snapshot.duplicates,
                        "outOfOrder"    to snapshot.outOfOrder,
                    )
                ),
                "status" to mapOf(
                    "properties" to mapOf(
                        "synthetic"  to true,
                        "updatedAtMs" to updatedAt,
                    )
                ),
            )
        )
    }
}
