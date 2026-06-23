package com.resequencetwin.control.ingest

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.resequencetwin.control.model.NormalizedEvent
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Idempotent projector: translates normalized events into Ditto Thing/Feature mutations.
 */
@Component
class DittoProjector(
    @Value("\${ditto.base-url:http://localhost:18080}") private val baseUrl: String,
    @Value("\${ditto.auth-header:nginx:ditto}") private val authHeaderValue: String
) {

    companion object {
        private val log = LoggerFactory.getLogger(DittoProjector::class.java)

        /** Shared policy for all Things in the rtw namespace. */
        private const val RTW_POLICY_ID = "rtw:default-policy"
    }

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    /** In-process idempotency guard: eventId → number of times applied to Ditto. */
    private val processedEvents = ConcurrentHashMap<String, Long>()

    @PostConstruct
    fun init() {
        try {
            bootstrapPolicy()
        } catch (e: Exception) {
            log.warn("Could not bootstrap Ditto policy on startup (may retry): {}", e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun project(event: NormalizedEvent) {
        if (processedEvents.putIfAbsent(event.eventId, 1L) != null) {
            log.debug("Skipping duplicate eventId={}", event.eventId); return
        }
        applyToDitto(event)
    }

    fun getProcessedEventCount(eventId: String): Long =
        processedEvents.getOrDefault(eventId, 0L)

    fun getThingStatus(thingId: String): Int {
        val req = dittoRequest("GET", thingsUrl(thingId), null)
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    fun getThingFeatures(thingId: String): Map<String, Any>? {
        val req = dittoRequest("GET", thingsUrl(thingId) + "/features", null)
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        return mapper.readValue(resp.body(), object : TypeReference<Map<String, Any>>() {})
    }

    fun deleteThing(thingId: String) {
        val req = dittoRequest("DELETE", thingsUrl(thingId), null)
        http.send(req, HttpResponse.BodyHandlers.discarding())
    }

    fun resetForTesting() {
        processedEvents.clear()
    }

    /**
     * Idempotent PUT of an arbitrary Thing body to Ditto.
     *
     * Used by [PbsTwinProjector] to push the live PBS line twin state without duplicating
     * the HTTP/auth stack. Throws [IOException] on HTTP >= 400 responses.
     */
    fun putThing(thingId: String, body: Map<String, Any>) {
        dittoSend("PUT", thingsUrl(thingId), mapper.writeValueAsString(body))
    }

    // -------------------------------------------------------------------------
    // Ditto projection logic
    // -------------------------------------------------------------------------

    private fun applyToDitto(ev: NormalizedEvent) {
        when (ev.eventType) {
            "UnitArrived" -> {
                ensureUnitThing(ev.unitId, ev.resourceId)
                updateUnitLocation(ev.unitId, ev.resourceId, ev.simTs)
                updateUnitStatus(ev.unitId, "processing", ev.simTs)
            }
            "UnitDeparted" -> updateUnitStatus(ev.unitId, "in-transit", ev.simTs)
            "MoveAssigned" -> {
                ensureUnitThing(ev.unitId, ev.resourceId)
                updateUnitStatus(ev.unitId, "idle", ev.simTs)
            }
            "MoveStarted"    -> updateUnitStatus(ev.unitId, "in-transit", ev.simTs)
            "MoveBlocked"    -> updateUnitStatus(ev.unitId, "blocked", ev.simTs)
            "MoveCompleted"  -> updateUnitStatus(ev.unitId, "idle", ev.simTs)
            "ResourceOccupied" -> {
                ensureResourceThing(ev.resourceId)
                incrementResourceOccupancy(ev.resourceId, +1, ev.simTs)
            }
            "ResourceReleased" -> {
                ensureResourceThing(ev.resourceId)
                incrementResourceOccupancy(ev.resourceId, -1, ev.simTs)
            }
            else -> log.debug("Unhandled eventType={}", ev.eventType)
        }
    }

    // -------------------------------------------------------------------------
    // Ditto Thing / Feature helpers
    // -------------------------------------------------------------------------

    fun bootstrapPolicy() {
        val req = dittoRequest("GET", "$baseUrl/api/2/policies/$RTW_POLICY_ID", null)
        val status = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
        if (status == 200) return

        val body = mapper.writeValueAsString(
            mapOf(
                "entries" to mapOf(
                    "DEFAULT" to mapOf(
                        "subjects" to mapOf(
                            "nginx:ditto" to mapOf("type" to "nginx pre-authenticated user")
                        ),
                        "resources" to mapOf(
                            "thing:/"   to mapOf("grant" to arrayOf("READ", "WRITE"), "revoke" to arrayOf<String>()),
                            "policy:/"  to mapOf("grant" to arrayOf("READ", "WRITE"), "revoke" to arrayOf<String>()),
                            "message:/" to mapOf("grant" to arrayOf("READ", "WRITE"), "revoke" to arrayOf<String>())
                        )
                    )
                )
            )
        )
        dittoSend("PUT", "$baseUrl/api/2/policies/$RTW_POLICY_ID", body)
    }

    private fun ensureUnitThing(unitId: String, initialResourceId: String) {
        if (getThingStatus(unitId) == 200) return
        val body = mapper.writeValueAsString(
            mapOf(
                "policyId"   to RTW_POLICY_ID,
                "attributes" to mapOf("type" to "car-body"),
                "features"   to mapOf(
                    "location" to mapOf("properties" to mapOf("resourceId" to initialResourceId, "updatedAt" to 0L)),
                    "status"   to mapOf("properties" to mapOf("value" to "idle", "updatedAt" to 0L))
                )
            )
        )
        dittoSend("PUT", thingsUrl(unitId), body)
    }

    private fun ensureResourceThing(resourceId: String) {
        if (getThingStatus(resourceId) == 200) return
        val body = mapper.writeValueAsString(
            mapOf(
                "policyId"   to RTW_POLICY_ID,
                "attributes" to mapOf("type" to "resource"),
                "features"   to mapOf(
                    "occupancy" to mapOf("properties" to mapOf("current" to 0, "updatedAt" to 0L))
                )
            )
        )
        dittoSend("PUT", thingsUrl(resourceId), body)
    }

    private fun updateUnitLocation(unitId: String, resourceId: String, simTs: Double) {
        val body = mapper.writeValueAsString(mapOf("resourceId" to resourceId, "updatedAt" to simTs))
        dittoSend("PUT", thingsUrl(unitId) + "/features/location/properties", body)
    }

    private fun updateUnitStatus(unitId: String, status: String, simTs: Double) {
        val body = mapper.writeValueAsString(mapOf("value" to status, "updatedAt" to simTs))
        dittoSend("PUT", thingsUrl(unitId) + "/features/status/properties", body)
    }

    private fun incrementResourceOccupancy(resourceId: String, delta: Int, simTs: Double) {
        val features = getThingFeatures(resourceId)
        var current = 0
        if (features != null && features.containsKey("occupancy")) {
            @Suppress("UNCHECKED_CAST")
            val occ = features["occupancy"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val props = occ["properties"] as Map<String, Any>
            current = (props["current"] as Number).toInt()
        }
        val updated = maxOf(0, current + delta)
        val body = mapper.writeValueAsString(mapOf("current" to updated, "updatedAt" to simTs))
        dittoSend("PUT", thingsUrl(resourceId) + "/features/occupancy/properties", body)
    }

    // -------------------------------------------------------------------------
    // HTTP utilities
    // -------------------------------------------------------------------------

    private fun thingsUrl(thingId: String): String = "$baseUrl/api/2/things/$thingId"

    private fun dittoSend(method: String, url: String, jsonBody: String) {
        val req = dittoRequest(method, url, jsonBody)
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) {
            throw IOException("Ditto $method $url → ${resp.statusCode()} ${resp.body()}")
        }
    }

    private fun dittoRequest(method: String, url: String, jsonBody: String?): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("x-ditto-pre-authenticated", authHeaderValue)
            .header("Content-Type", "application/json")
        if (jsonBody != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
        } else if (method == "DELETE") {
            builder.DELETE()
        } else {
            builder.GET()
        }
        return builder.build()
    }
}
