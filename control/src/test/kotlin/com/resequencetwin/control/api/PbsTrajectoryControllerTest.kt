package com.resequencetwin.control.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * WebMvcTest slice for GET /api/trajectory (viz-1).
 *
 * Exercises the real simulation — no mocks of the sim pipeline.
 * Follows the pattern of PbsAdvisoryControllerTest.
 */
@WebMvcTest(PbsAdvisoryController::class)
@Import(PbsAdvisoryService::class, PbsTrajectoryService::class)
class PbsTrajectoryControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    // =========================================================================
    // 1. Structure: GET /api/trajectory 200 + JSON shape
    // =========================================================================

    @Test
    @DisplayName("TRAJ-1: GET /api/trajectory returns 200 with honesty envelope")
    fun getTrajectoryReturns200WithEnvelope() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
    }

    @Test
    @DisplayName("TRAJ-2: GET /api/trajectory returns seed, policy, non-empty lanes/bodies/frames")
    fun getTrajectoryReturnsFullStructure() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.seed").value(42))
            .andExpect(jsonPath("$.policy").value("dynamic"))
            .andExpect(jsonPath("$.lanes").isArray)
            .andExpect(jsonPath("$.lanes.length()").value(3))
            .andExpect(jsonPath("$.bodies").isMap)
            .andExpect(jsonPath("$.frames").isArray)
    }

    @Test
    @DisplayName("TRAJ-3: frames array is non-empty and first frame has required keys")
    fun framesArrayNonEmptyWithRequiredKeys() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "10")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.frames").isArray)
            .andExpect(jsonPath("$.frames[0].step").isNumber)
            .andExpect(jsonPath("$.frames[0].lanes").isMap)
            .andExpect(jsonPath("$.frames[0].arrived").isArray)
    }

    @Test
    @DisplayName("TRAJ-4: bodies map is non-empty and frames array is non-empty")
    fun bodiesMapNonEmpty() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "10")
                .param("policy", "dynamic"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bodies").isMap)
            .andExpect(jsonPath("$.frames.length()").value(org.hamcrest.Matchers.greaterThan(0)))
    }

    @Test
    @DisplayName("TRAJ-5: lanes array has id and capacity fields")
    fun lanesArrayHasIdAndCapacity() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lanes[0].id").isString)
            .andExpect(jsonPath("$.lanes[0].capacity").isNumber)
    }

    @Test
    @DisplayName("TRAJ-6: defaults — no params — returns 200")
    fun defaultParamsReturn200() {
        mockMvc.perform(get("/api/trajectory"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.seed").value(42))
            .andExpect(jsonPath("$.policy").value("dynamic"))
    }

    // =========================================================================
    // 2. Determinism: same params → identical JSON response
    // =========================================================================

    @Test
    @DisplayName("DETERM-TRAJ-1: same params twice → identical response body (byte-equality)")
    fun trajectoryIsDeterministic() {
        val r1 = mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk).andReturn()
        val r2 = mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "dynamic"))
            .andExpect(status().isOk).andReturn()

        assertThat(r1.response.contentAsString)
            .`as`("trajectory response must be byte-identical for same params")
            .isEqualTo(r2.response.contentAsString)
    }

    // =========================================================================
    // 3. policy=static returns a valid (different) trajectory
    // =========================================================================

    @Test
    @DisplayName("POLICY-1: policy=static returns 200 with policy field == 'static'")
    fun staticPolicyReturns200() {
        mockMvc.perform(get("/api/trajectory")
                .param("seed", "42")
                .param("bodies", "20")
                .param("policy", "static"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.policy").value("static"))
            .andExpect(jsonPath("$.frames").isArray)
    }

    // =========================================================================
    // 4. Bad params → 400
    // =========================================================================

    @Test
    @DisplayName("BADPARAM-TRAJ-1: bodies=0 returns 400")
    fun bodiesZeroReturns400() {
        mockMvc.perform(get("/api/trajectory").param("bodies", "0"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-2: bodies=99999 returns 400 (absurd size)")
    fun bodiesAbsurdSizeReturns400() {
        mockMvc.perform(get("/api/trajectory").param("bodies", "99999"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-3: bodies=2001 returns 400 (just over limit)")
    fun bodiesJustOverLimitReturns400() {
        mockMvc.perform(get("/api/trajectory").param("bodies", "2001"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-4: policy=bogus returns 400")
    fun bogusHolicyReturns400() {
        mockMvc.perform(get("/api/trajectory").param("policy", "bogus"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("BADPARAM-TRAJ-5: policy=DYNAMIC (uppercase) returns 400 — policy is case-sensitive")
    fun policyUppercaseReturns400() {
        mockMvc.perform(get("/api/trajectory").param("policy", "DYNAMIC"))
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("READONLY-TRAJ: POST /api/trajectory returns 405 Method Not Allowed")
    fun postTrajectoryReturns405() {
        mockMvc.perform(post("/api/trajectory"))
            .andExpect(status().isMethodNotAllowed)
    }
}
