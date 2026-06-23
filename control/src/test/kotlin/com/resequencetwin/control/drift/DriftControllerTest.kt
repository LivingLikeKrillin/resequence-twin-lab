package com.resequencetwin.control.drift

import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * WebMvcTest slice for [DriftController]. [DriftService] is mocked so the slice does not need
 * [DriftMonitor]'s scheduler or the LivePbsProcessor/Kafka/Ditto beans — matching the existing
 * `api/` slice pattern.
 */
@WebMvcTest(DriftController::class)
class DriftControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockBean private lateinit var service: DriftService

    private fun sampleReport() = DriftReport.of(
        sourceAvailable = true,
        baseline = ExpectedConfig(mapOf("L1" to 10), emptySet()),
        observed = ObservedConfig(mapOf("L1" to 8), emptySet()),
        configFindings = listOf(ConfigDriftFinding("L1", DriftKind.CAPACITY_CHANGED, "10", "8",
            ReconciliationProposal("ADJUST_CAPACITY", "twin L1 10 -> 8"))),
        behavioralFindings = listOf(BehavioralDriftFinding("releases", 3.0, 1.0, 2.0, 1.4, true)),
    )

    @Test
    fun `GET api drift returns 200 with envelope and findings`() {
        `when`(service.report()).thenReturn(sampleReport())
        mockMvc.perform(get("/api/drift"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.synthetic").value(true))
            .andExpect(jsonPath("$.disclaimer").isString)
            .andExpect(jsonPath("$.sourceAvailable").value(true))
            .andExpect(jsonPath("$.configFindings[0].kind").value("CAPACITY_CHANGED"))
            .andExpect(jsonPath("$.configFindings[0].proposal.action").value("ADJUST_CAPACITY"))
            .andExpect(jsonPath("$.reconciliationProposals[0].action").value("ADJUST_CAPACITY"))
            .andExpect(jsonPath("$.behavioralFindings[0].metric").value("releases"))
            .andExpect(jsonPath("$.behavioralFindings[0].breached").value(true))
    }

    @Test
    fun `POST api drift returns 405 (read-only surface)`() {
        mockMvc.perform(post("/api/drift")).andExpect(status().isMethodNotAllowed)
    }
}
