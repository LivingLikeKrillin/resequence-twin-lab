package com.resequencetwin.control.stream

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Kafka consumer that bridges raw [PbsEvent] records to the shared [LivePbsProcessor].
 *
 * ## Concurrency
 * The PoC assumes a single partition, so a single listener thread feeds the processor.
 * [LivePbsProcessor.process] is `@Synchronized` as a safety belt for any future
 * multi-thread scenario.
 *
 * ## Error handling
 * Deserialization errors are handled upstream by [ErrorHandlingDeserializer]: a poison record
 * is logged and skipped before [onEvent] is ever called. Business-level [ProcessResult]
 * outcomes (Rejected, Duplicate, etc.) are logged at DEBUG — they are expected operational
 * states and should not surface as errors.
 *
 * ## Metrics
 * None wired here — Task 3 (Micrometer) adds counters by wrapping / extending this listener.
 */
@Component
class PbsEventListener(private val processor: LivePbsProcessor) {

    private val log = LoggerFactory.getLogger(PbsEventListener::class.java)

    @KafkaListener(
        topics = ["\${pbs.topic:pbs-events}"],
        containerFactory = "pbsListenerContainerFactory",
    )
    fun onEvent(event: PbsEvent) {
        val result = processor.process(event)
        log.debug("Processed {} → {}", event::class.simpleName, result)
    }
}
