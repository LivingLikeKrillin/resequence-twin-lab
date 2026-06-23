package com.resequencetwin.control.drift

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Read-only REST surface for drift detection (S5). Advisory only; no write-back. */
@RestController
@RequestMapping("/api")
class DriftController(private val service: DriftService) {
    @GetMapping("/drift")
    fun getDrift(): DriftReport = service.report()
}
