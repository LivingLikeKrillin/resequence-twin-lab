package com.resequencetwin.control.stream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.resequencetwin.control.pbs.Lane
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer

/**
 * Parses a lane-topology string of the form `"id:cap,id:cap,..."` into a [List] of [Lane].
 *
 * Validation rules:
 * - The string must not be blank (throws [IllegalArgumentException] with "empty").
 * - Each token must have exactly one colon separator and a positive integer capacity.
 *
 * This function is top-level so it can be tested without any Spring context.
 */
fun parseLaneTopology(spec: String): List<Lane> {
    require(spec.isNotBlank()) { "pbs.lanes must not be empty; got: '$spec'" }

    return spec.split(",").mapIndexed { index, token ->
        val trimmed = token.trim()
        val parts = trimmed.split(":")
        require(parts.size == 2) {
            "pbs.lanes token[$index] '$trimmed' must be in 'id:capacity' format"
        }
        val id = parts[0].trim()
        val cap = parts[1].trim().toIntOrNull()
            ?: throw IllegalArgumentException(
                "pbs.lanes token[$index] '$trimmed' has non-numeric capacity: '${parts[1].trim()}'"
            )
        require(cap > 0) {
            "pbs.lanes token[$index] '$trimmed' capacity must be > 0, got $cap"
        }
        require(id.isNotBlank()) {
            "pbs.lanes token[$index] '$trimmed' has a blank lane id"
        }
        Lane(id, cap)
    }
}

/**
 * Spring configuration for the live-PBS Kafka consumer pipeline.
 *
 * Registers:
 * - [LivePbsProcessor] bean built from the `pbs.lanes` topology property.
 * - [ConsumerFactory] wired with [JsonDeserializer] targeting [PbsEvent], using Jackson's
 *   `@JsonTypeInfo` discriminator (type headers disabled) so subtypes resolve from the JSON
 *   `type` field rather than Kafka record headers.
 * - [ConcurrentKafkaListenerContainerFactory] named `pbsListenerContainerFactory` that
 *   [PbsEventListener] references.
 */
@Configuration
class PbsStreamConfig(
    @Value("\${spring.kafka.bootstrap-servers:localhost:19092}")
    private val bootstrapServers: String,

    @Value("\${spring.kafka.consumer.group-id:resequence-twin-control}")
    private val groupId: String,

    @Value("\${spring.kafka.consumer.auto-offset-reset:earliest}")
    private val autoOffsetReset: String,

    @Value("\${pbs.lanes:L1:10,L2:10,L3:10}")
    private val lanesSpec: String,
) {

    @Bean
    fun livePbsProcessor(): LivePbsProcessor =
        LivePbsProcessor(parseLaneTopology(lanesSpec))

    /**
     * Consumer factory for [PbsEvent] values.
     *
     * Key decisions:
     * - `setUseTypeHeaders(false)`: Kafka records produced by the Python / CLI producer do NOT
     *   carry spring-kafka type headers, so we disable header-based type resolution entirely and
     *   let Jackson's `@JsonTypeInfo(property = "type")` discriminator drive subtype selection.
     * - `addTrustedPackages("com.resequencetwin.control.stream")`: defensive belt-and-suspenders —
     *   because a fixed target type ([PbsEvent]) is already set and `useTypeHeaders` is false,
     *   the allowlist is NOT strictly required in this configuration; it is kept for clarity
     *   and to guard against future configuration drift.
     * - Wrapped in [ErrorHandlingDeserializer]: a malformed / poison record is logged and the
     *   offset advanced; the partition continues processing.
     */
    @Bean
    fun pbsConsumerFactory(): ConsumerFactory<String, PbsEvent> {
        val mapper = jacksonObjectMapper()

        val valueDeserializer = JsonDeserializer<PbsEvent>(PbsEvent::class.java, mapper, false).apply {
            addTrustedPackages("com.resequencetwin.control.stream")
            setUseTypeHeaders(false)
        }

        val errorHandlingDeserializer = ErrorHandlingDeserializer(valueDeserializer)

        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to autoOffsetReset,
        )

        return DefaultKafkaConsumerFactory(
            props,
            StringDeserializer(),
            errorHandlingDeserializer,
        )
    }

    /**
     * Listener container factory named `pbsListenerContainerFactory`.
     * [PbsEventListener] references this name in its `@KafkaListener(containerFactory = ...)`.
     */
    @Bean
    fun pbsListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, PbsEvent>,
    ): ConcurrentKafkaListenerContainerFactory<String, PbsEvent> =
        ConcurrentKafkaListenerContainerFactory<String, PbsEvent>().apply {
            this.consumerFactory = consumerFactory
            // Zero-delay, no retry tuning: intentional for this PoC (fast fail / skip poison records).
            // Production should configure a FixedBackOff or DLT via DefaultErrorHandler(backOff, dlqPublisher).
            setCommonErrorHandler(DefaultErrorHandler())
            // Pin to a single consumer thread: LivePbsProcessor is designed for single-consumer use
            // (one partition, @Synchronized).  Explicit here so an accidental change to the topic's
            // partition count or a spring.kafka.listener.concurrency deployment override cannot
            // silently introduce concurrent consumers.
            setConcurrency(1)
        }
}
