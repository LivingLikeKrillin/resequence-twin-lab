package com.resequencetwin.control.drift

/** Port: a live source of the plant's currently observed cumulative KPI. Implementations may do I/O. */
interface LiveTelemetrySource {
    fun readKpi(): ObservedKpi
}
