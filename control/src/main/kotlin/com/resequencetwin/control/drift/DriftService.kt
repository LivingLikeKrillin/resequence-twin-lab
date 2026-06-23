package com.resequencetwin.control.drift

import org.springframework.stereotype.Service

/** Thin read-only relay of the latest drift report held by [DriftMonitor]. */
@Service
class DriftService(private val monitor: DriftMonitor) {
    fun report(): DriftReport = monitor.currentReport()
}
