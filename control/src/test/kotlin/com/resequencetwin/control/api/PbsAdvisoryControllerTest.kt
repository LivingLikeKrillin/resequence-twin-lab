package com.resequencetwin.control.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.assertj.core.api.Assertions.assertThat

/**
 * WebMvcTest slice for [PbsAdvisoryController] (rev3 R4b Deliverable 2).
 *
 * Uses `@WebMvcTest` so the full application context (Kafka/Ditto beans from
 * [com.resequencetwin.control.ingest.DittoProjector]) does NOT start. The real
 * [PbsAdvisoryService] is imported to exercise the full deterministic simulation
 * pipeline — no mocks of the benchmark logic.
 *
 * ## Tests
 * 1. GET /api/kpi returns 200 with honesty envelope + KPI deltas
 * 2. GET /api/explain/release returns 200 with reason + envelope
 * 3. GET /api/predict/scramble returns 200 with riskScore + envelope
 * 4. Determinism: same params → identical JSON response body
 * 5. POST/PUT returns 405 Method Not Allowed (read-only)
 * 6. Bad param (bodies=0) returns 400
 */
@WebMvcTest(PbsAdvisoryController::class)
@Import(PbsAdvisoryService::class, PbsTrajectoryService::class)
class PbsAdvisoryControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    // =========================================================================
    // 1. GET /api/kpi — 200 + honesty envelope + deltas
    // =========================================================================

    @Test
    @DisplayName("KPI-1: GET /api/kpi returns 200 with synthetic flag, disclaimer, and delta fields")
    fun getKpiReturns200WithEnvelopeAndDeltas() {
        mockMvc.perform(get("/api/kpi")
                .param("seed", "42")
                .param("bodies", "100"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            // Honesty envelope
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
            // Raw KPI snapshots present
            .andExpect(jsonPath("$.staticKpis.colorChanges").isNumber)
            .andExpect(jsonPath("$.dynamicKpis.colorChanges").isNumber)
            // Delta fields present
            .andExpect(jsonPath("$.colorChangesDelta").isNumber)
            .andExpect(jsonPath("$.avgBatchLengthDelta").isNumber)
            .andExpect(jsonPath("$.dueDateDeviationDelta").isNumber)
            .andExpect(jsonPath("$.throughputDelta").isNumber)
            .andExpect(jsonPath("$.laneUtilizationDelta").isNumber)
            // Seed echoed back
            .andExpect(jsonPath("$.seed").value(42))
    }

    @Test
    @DisplayName("KPI-2: GET /api/kpi uses defaults (no params)")
    fun getKpiDefaultParams() {
        mockMvc.perform(get("/api/kpi"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.seed").value(42))
    }

    // =========================================================================
    // 2. GET /api/explain/release — 200 + reason + envelope
    // =========================================================================

    @Test
    @DisplayName("EXPLAIN-1: GET /api/explain/release returns 200 with reason and honesty envelope")
    fun getExplainReleaseReturns200WithReasonAndEnvelope() {
        mockMvc.perform(get("/api/explain/release")
                .param("seed", "42")
                .param("bodies", "100")
                .param("afterReleases", "15"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            // Honesty envelope
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
            // Context fields
            .andExpect(jsonPath("$.seed").value(42))
            .andExpect(jsonPath("$.afterReleases").value(15))
            .andExpect(jsonPath("$.assemblyOutSize").isNumber)
            // Explanation fields
            .andExpect(jsonPath("$.explanation.chosenLaneId").isString)
            .andExpect(jsonPath("$.explanation.chosenBodyId").isString)
            .andExpect(jsonPath("$.explanation.reason").isString)
            .andExpect(jsonPath("$.explanation.candidates").isArray)
    }

    @Test
    @DisplayName("EXPLAIN-2: GET /api/explain/release uses defaults (no params)")
    fun getExplainReleaseDefaultParams() {
        mockMvc.perform(get("/api/explain/release"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.synthetic").value(true))
    }

    // =========================================================================
    // 3. GET /api/predict/scramble — 200 + riskScore + envelope
    // =========================================================================

    @Test
    @DisplayName("PREDICT-1: GET /api/predict/scramble returns 200 with riskScore and honesty envelope")
    fun getPredictScrambleReturns200WithRiskScoreAndEnvelope() {
        mockMvc.perform(get("/api/predict/scramble")
                .param("seed", "42")
                .param("bodies", "100")
                .param("afterReleases", "15"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            // Honesty envelope
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
            // Context fields
            .andExpect(jsonPath("$.seed").value(42))
            .andExpect(jsonPath("$.afterReleases").value(15))
            // Forecast fields
            .andExpect(jsonPath("$.forecast.riskScore").isNumber)
            .andExpect(jsonPath("$.forecast.level").isString)
            .andExpect(jsonPath("$.forecast.factors").isArray)
            .andExpect(jsonPath("$.forecast.stabilizationHint").isString)
    }

    @Test
    @DisplayName("PREDICT-2: GET /api/predict/scramble uses defaults (no params)")
    fun getPredictScrambleDefaultParams() {
        mockMvc.perform(get("/api/predict/scramble"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.synthetic").value(true))
    }

    // =========================================================================
    // 4. Determinism: same params → identical JSON response body
    // =========================================================================

    @Test
    @DisplayName("DETERM-1: GET /api/kpi same params twice → identical JSON body")
    fun kpiIsDeterministic() {
        val r1 = mockMvc.perform(get("/api/kpi").param("seed", "42").param("bodies", "50"))
            .andExpect(status().isOk).andReturn()
        val r2 = mockMvc.perform(get("/api/kpi").param("seed", "42").param("bodies", "50"))
            .andExpect(status().isOk).andReturn()

        assertThat(r1.response.contentAsString)
            .`as`("KPI response must be identical for same params")
            .isEqualTo(r2.response.contentAsString)
    }

    @Test
    @DisplayName("DETERM-2: GET /api/explain/release same params twice → identical JSON body")
    fun explainReleaseIsDeterministic() {
        val r1 = mockMvc.perform(get("/api/explain/release")
                .param("seed", "7").param("bodies", "50").param("afterReleases", "10"))
            .andExpect(status().isOk).andReturn()
        val r2 = mockMvc.perform(get("/api/explain/release")
                .param("seed", "7").param("bodies", "50").param("afterReleases", "10"))
            .andExpect(status().isOk).andReturn()

        assertThat(r1.response.contentAsString)
            .`as`("explain/release response must be identical for same params")
            .isEqualTo(r2.response.contentAsString)
    }

    @Test
    @DisplayName("DETERM-3: GET /api/predict/scramble same params twice → identical JSON body")
    fun predictScrambleIsDeterministic() {
        val r1 = mockMvc.perform(get("/api/predict/scramble")
                .param("seed", "7").param("bodies", "50").param("afterReleases", "10"))
            .andExpect(status().isOk).andReturn()
        val r2 = mockMvc.perform(get("/api/predict/scramble")
                .param("seed", "7").param("bodies", "50").param("afterReleases", "10"))
            .andExpect(status().isOk).andReturn()

        assertThat(r1.response.contentAsString)
            .`as`("predict/scramble response must be identical for same params")
            .isEqualTo(r2.response.contentAsString)
    }

    // =========================================================================
    // 5. Read-only: POST/PUT return 405 Method Not Allowed
    // =========================================================================

    @Test
    @DisplayName("READONLY-1: POST /api/kpi returns 405 Method Not Allowed")
    fun postKpiReturns405() {
        mockMvc.perform(post("/api/kpi"))
            .andExpect(status().isMethodNotAllowed)
    }

    @Test
    @DisplayName("READONLY-2: PUT /api/explain/release returns 405 Method Not Allowed")
    fun putExplainReturns405() {
        mockMvc.perform(put("/api/explain/release"))
            .andExpect(status().isMethodNotAllowed)
    }

    @Test
    @DisplayName("READONLY-3: POST /api/predict/scramble returns 405 Method Not Allowed")
    fun postPredictScrambleReturns405() {
        mockMvc.perform(post("/api/predict/scramble"))
            .andExpect(status().isMethodNotAllowed)
    }

    // =========================================================================
    // 6. Bad params → 400
    // =========================================================================

    @Test
    @DisplayName("BADPARAM-1: bodies=0 on /api/kpi returns 400")
    fun kpiBodiesZeroReturns400() {
        mockMvc.perform(get("/api/kpi").param("bodies", "0"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-2: bodies=0 on /api/explain/release returns 400")
    fun explainBodiesZeroReturns400() {
        mockMvc.perform(get("/api/explain/release").param("bodies", "0"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-3: bodies=0 on /api/predict/scramble returns 400")
    fun predictBodiesZeroReturns400() {
        mockMvc.perform(get("/api/predict/scramble").param("bodies", "0"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-4: afterReleases negative returns 400")
    fun explainAfterReleasesNegativeReturns400() {
        mockMvc.perform(get("/api/explain/release").param("afterReleases", "-1"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-5: bodies negative returns 400")
    fun kpiBodiesNegativeReturns400() {
        mockMvc.perform(get("/api/kpi").param("bodies", "-5"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-6: afterReleases >= bodies on /api/explain/release returns 400 (all lanes would be empty)")
    fun explainAfterReleasesEqualBodiesReturns400() {
        // afterReleases == bodies means all bodies released, no lane occupants → 400
        mockMvc.perform(get("/api/explain/release")
                .param("bodies", "10")
                .param("afterReleases", "10"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-7: afterReleases >= bodies on /api/predict/scramble returns 400 (empty buffer makes forecast meaningless)")
    fun scrambleAfterReleasesEqualBodiesReturns400() {
        // afterReleases == bodies means all bodies released, lanes empty → scramble forecast
        // would be all-zero and meaningless. Consistent with /api/explain/release guard.
        mockMvc.perform(get("/api/predict/scramble")
                .param("bodies", "100")
                .param("afterReleases", "100"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-8: afterReleases=-1 on /api/predict/scramble returns 400")
    fun scrambleAfterReleasesNegativeReturns400() {
        mockMvc.perform(get("/api/predict/scramble").param("afterReleases", "-1"))
            .andExpect(status().isBadRequest)
    }
}
