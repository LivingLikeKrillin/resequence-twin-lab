package com.resequencetwin.control.drift

import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic, **mutable** in-process telemetry source for offline TDD and the offline demo.
 * [set] replaces the current observed KPI so a test/demo can drive a residual against the twin
 * prediction. Synthetic — demonstrates the mechanism, not real telemetry.
 */
class SimulatedTelemetrySource(initial: ObservedKpi) : LiveTelemetrySource {
    private val current = AtomicReference(initial)
    override fun readKpi(): ObservedKpi = current.get()
    fun set(kpi: ObservedKpi) { current.set(kpi) }
}
