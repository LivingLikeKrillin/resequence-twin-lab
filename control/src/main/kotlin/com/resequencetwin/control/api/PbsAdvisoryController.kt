package com.resequencetwin.control.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Read-only REST surface for the PBS advisory analytics engine (rev3 R4b).
 */
@RestController
@RequestMapping("/api")
class PbsAdvisoryController(
    private val service: PbsAdvisoryService,
    private val trajectoryService: PbsTrajectoryService
) {

    companion object {
        /** Upper bound for afterReleases to guard against absurdly large requests. */
        const val MAX_AFTER_RELEASES: Int = 100_000
        val VALID_POLICIES: Set<String> = setOf("dynamic", "static")
        const val MAX_BODIES_TRAJECTORY: Int = 2000
    }

    // -------------------------------------------------------------------------
    // GET /api/kpi
    // -------------------------------------------------------------------------

    @GetMapping("/kpi")
    fun getKpi(
        @RequestParam(defaultValue = "42") seed: Long,
        @RequestParam(defaultValue = "100") bodies: Int
    ): KpiResponse {
        validateBodies(bodies)
        return service.kpi(seed, bodies)
    }

    // -------------------------------------------------------------------------
    // GET /api/explain/release
    // -------------------------------------------------------------------------

    @GetMapping("/explain/release")
    fun getExplainRelease(
        @RequestParam(defaultValue = "42") seed: Long,
        @RequestParam(defaultValue = "100") bodies: Int,
        @RequestParam(defaultValue = "15") afterReleases: Int
    ): ExplainReleaseResponse {
        validateBodies(bodies)
        validateAfterReleases(afterReleases)
        if (afterReleases >= bodies) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "afterReleases ($afterReleases) must be < bodies ($bodies)" +
                    ": the buffer must have remaining occupants to explain a release"
            )
        }
        return service.explainRelease(seed, bodies, afterReleases)
    }

    // -------------------------------------------------------------------------
    // GET /api/predict/scramble
    // -------------------------------------------------------------------------

    @GetMapping("/predict/scramble")
    fun getPredictScramble(
        @RequestParam(defaultValue = "42") seed: Long,
        @RequestParam(defaultValue = "100") bodies: Int,
        @RequestParam(defaultValue = "15") afterReleases: Int
    ): PredictScrambleResponse {
        validateBodies(bodies)
        validateAfterReleases(afterReleases)
        if (afterReleases >= bodies) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "afterReleases ($afterReleases) must be < bodies ($bodies)" +
                    ": the buffer must have remaining occupants for a meaningful scramble forecast"
            )
        }
        return service.predictScramble(seed, bodies, afterReleases)
    }

    // -------------------------------------------------------------------------
    // GET /api/trajectory
    // -------------------------------------------------------------------------

    @GetMapping("/trajectory")
    fun getTrajectory(
        @RequestParam(defaultValue = "42")      seed: Long,
        @RequestParam(defaultValue = "100")     bodies: Int,
        @RequestParam(defaultValue = "dynamic") policy: String
    ): TrajectoryResponse {
        validateBodies(bodies)
        if (bodies > MAX_BODIES_TRAJECTORY) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "bodies must be <= $MAX_BODIES_TRAJECTORY (got $bodies)"
            )
        }
        if (policy !in VALID_POLICIES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "policy must be one of $VALID_POLICIES (got '$policy')"
            )
        }
        return trajectoryService.trajectory(seed, bodies, policy)
    }

    // -------------------------------------------------------------------------
    // Param validation helpers
    // -------------------------------------------------------------------------

    private fun validateBodies(bodies: Int) {
        if (bodies <= 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "bodies must be > 0 (got $bodies)"
            )
        }
    }

    private fun validateAfterReleases(afterReleases: Int) {
        if (afterReleases < 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "afterReleases must be >= 0 (got $afterReleases)"
            )
        }
        if (afterReleases > MAX_AFTER_RELEASES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "afterReleases must be <= $MAX_AFTER_RELEASES (got $afterReleases)"
            )
        }
    }
}
