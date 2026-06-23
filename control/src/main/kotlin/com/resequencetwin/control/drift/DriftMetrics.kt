package com.resequencetwin.control.drift

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * Binds drift-monitor state to Micrometer gauges at `/actuator/prometheus`.
 *
 * [DriftMonitor] is the single source of truth: each gauge reads [DriftMonitor.currentReport] freshly
 * on scrape. Gauges (not counters) because both counts can go up or down as drift appears and clears.
 *
 * Honesty: these observe a SYNTHETIC perturbation, demonstrating the detection mechanism.
 */
@Component
class DriftMetrics(private val monitor: DriftMonitor) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("pbs.drift.config.findings", monitor) { it.currentReport().configFindings.size.toDouble() }
            .description("Current number of structural config-drift findings")
            .baseUnit("findings")
            .register(registry)

        Gauge.builder("pbs.drift.behavioral.breaches", monitor) { m ->
            m.currentReport().behavioralFindings.count { it.breached }.toDouble()
        }
            .description("Current number of behavioral-drift metrics in breach")
            .baseUnit("breaches")
            .register(registry)
    }
}
