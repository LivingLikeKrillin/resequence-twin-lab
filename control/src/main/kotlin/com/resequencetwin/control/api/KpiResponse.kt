package com.resequencetwin.control.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.resequencetwin.control.bench.PbsBenchmarkComparison
import com.resequencetwin.control.bench.PbsKpiCollector

data class KpiResponse(
    @field:JsonProperty("synthetic")              val synthetic: Boolean,
    @field:JsonProperty("disclaimer")             val disclaimer: String,
    @field:JsonProperty("seed")                   val seed: Long,
    @field:JsonProperty("staticKpis")             val staticKpis: PbsKpiCollector.Snapshot,
    @field:JsonProperty("dynamicKpis")            val dynamicKpis: PbsKpiCollector.Snapshot,
    @field:JsonProperty("colorChangesDelta")      val colorChangesDelta: Int,
    @field:JsonProperty("avgBatchLengthDelta")    val avgBatchLengthDelta: Double,
    @field:JsonProperty("dueDateDeviationDelta")  val dueDateDeviationDelta: Double,
    @field:JsonProperty("throughputDelta")        val throughputDelta: Double,
    @field:JsonProperty("laneUtilizationDelta")   val laneUtilizationDelta: Double
) {
    companion object {
        fun from(report: PbsBenchmarkComparison.Report): KpiResponse {
            val env = AdvisoryEnvelope.standard()
            return KpiResponse(
                synthetic             = env.synthetic,
                disclaimer            = env.disclaimer,
                seed                  = report.seed,
                staticKpis            = report.staticKpis,
                dynamicKpis           = report.dynamicKpis,
                colorChangesDelta     = report.colorChangesDelta(),
                avgBatchLengthDelta   = report.avgBatchLengthDelta(),
                dueDateDeviationDelta = report.dueDateDeviationDelta(),
                throughputDelta       = report.throughputDelta(),
                laneUtilizationDelta  = report.laneUtilizationDelta()
            )
        }
    }
}
