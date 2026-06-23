package com.resequencetwin.control.drift

import com.resequencetwin.control.stream.LivePbsProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Scheduled, decoupled drift monitor.
 *
 * ## Separation of concerns
 * Runs on a dedicated scheduler thread (enabled via [com.resequencetwin.control.ingest.SchedulingConfig]),
 * **never** on the Kafka consumer thread — identical rationale to
 * [com.resequencetwin.control.ingest.PbsTwinProjector]. A live-source outage cannot stall or crash ingestion.
 *
 * ## Read-only twin
 * The injected [processor] is the shared [LivePbsProcessor] singleton. This monitor only calls
 * [LivePbsProcessor.snapshot] — it never re-seeds, re-instantiates, or mutates the twin (no write-back).
 *
 * ## Baseline = real source of truth
 * [captureBaseline] takes one snapshot of the live config source and treats it as the expected
 * baseline (captured, not hand-authored). Config drift is `diff(baseline, later observed)`.
 *
 * @param enabled read once at construction (test sets false); when false [tick] is a no-op.
 */
@Component
class DriftMonitor(
    private val processor: LivePbsProcessor,
    private val configSource: LiveConfigSource,
    private val telemetrySource: LiveTelemetrySource,
    @Value("\${pbs.drift.enabled:true}") private val enabled: Boolean,
    @Value("\${pbs.drift.ewma-alpha:0.3}") ewmaAlpha: Double,
    @Value("\${pbs.drift.residual-threshold:2.0}") residualThreshold: Double,
) {
    private val configDetector = ConfigDriftDetector()

    // Exactly three residual detectors, one per comparable cumulative metric (decision #1).
    private val residualDetectors: Map<String, ResidualDriftDetector> = linkedMapOf(
        "releases"      to ResidualDriftDetector("releases", ewmaAlpha, residualThreshold),
        "colourChanges" to ResidualDriftDetector("colourChanges", ewmaAlpha, residualThreshold),
        "assemblyOut"   to ResidualDriftDetector("assemblyOut", ewmaAlpha, residualThreshold),
    )

    private val latestReport = AtomicReference(DriftReport.empty())
    @Volatile private var baseline: ExpectedConfig? = null

    fun currentReport(): DriftReport = latestReport.get()

    /**
     * One snapshot of the live config source as the expected baseline (captured, not authored).
     *
     * **Threading:** call only from the scheduler thread (via [tick]) or from a single-threaded test;
     * it mutates the shared `baseline` field and may race with [tick] if called concurrently from another thread.
     */
    fun captureBaseline(): ExpectedConfig {
        val observed = configSource.readConfig()
        val captured = ExpectedConfig(lanes = observed.lanes.toMap(), expectedBlocked = observed.blocked.toSet())
        baseline = captured
        return captured
    }

    @Scheduled(fixedDelayString = "\${pbs.drift.interval-ms:1000}")
    fun tick() {
        if (!enabled) return
        val base = baseline ?: try {
            captureBaseline()
        } catch (e: Exception) {
            log.warn("Drift baseline capture failed (source unavailable?)", e)
            latestReport.set(DriftReport.of(sourceAvailable = false, baseline = null, observed = null,
                configFindings = emptyList(), behavioralFindings = emptyList()))
            return
        }

        val observedConfig = try {
            configSource.readConfig()
        } catch (e: Exception) {
            log.warn("Drift config read failed (source unavailable?)", e)
            latestReport.set(DriftReport.of(sourceAvailable = false, baseline = base, observed = null,
                configFindings = emptyList(), behavioralFindings = emptyList()))
            return
        }

        val observedKpi = try {
            telemetrySource.readKpi()
        } catch (e: Exception) {
            log.warn("Drift telemetry read failed (source unavailable?)", e)
            latestReport.set(DriftReport.of(sourceAvailable = false, baseline = base, observed = observedConfig,
                configFindings = configDetector.detect(base, observedConfig), behavioralFindings = emptyList()))
            return
        }

        val configFindings = configDetector.detect(base, observedConfig)

        val predicted = processor.snapshot()
        val behavioral = listOf(
            residualDetectors.getValue("releases").update(predicted.releases.toDouble(), observedKpi.releases.toDouble()),
            residualDetectors.getValue("colourChanges").update(predicted.colourChanges.toDouble(), observedKpi.colourChanges.toDouble()),
            residualDetectors.getValue("assemblyOut").update(predicted.assemblyOutSize.toDouble(), observedKpi.assemblyOut.toDouble()),
        )

        latestReport.set(DriftReport.of(sourceAvailable = true, baseline = base, observed = observedConfig,
            configFindings = configFindings, behavioralFindings = behavioral))
    }

    companion object {
        private val log = LoggerFactory.getLogger(DriftMonitor::class.java)
    }
}
