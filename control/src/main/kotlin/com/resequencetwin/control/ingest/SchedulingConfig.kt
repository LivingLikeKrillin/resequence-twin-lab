package com.resequencetwin.control.ingest

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring's `@Scheduled` support app-wide.
 *
 * Kept as a separate [Configuration] class rather than annotating [ResequenceTwinControlApplication]
 * to minimise diff surface and make scheduling easy to locate.
 */
@Configuration
@EnableScheduling
class SchedulingConfig
