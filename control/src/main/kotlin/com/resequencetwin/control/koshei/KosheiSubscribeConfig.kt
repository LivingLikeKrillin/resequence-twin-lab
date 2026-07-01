package com.resequencetwin.control.koshei

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `koshei.subscribe.*`-gated Paho wiring that turns the twin into a live Sparkplug host for koshei's
 * governance Edge Node. Mirrors [com.resequencetwin.control.drift.DriftConfig]'s `@Value`-gated bean
 * pattern; default OFF so non-subscribe deployments and unit tests are unaffected.
 *
 * The [KosheiGovernanceSubscriber] host logic is unit-tested directly; this Paho glue is gate-exercised.
 */
@Configuration
class KosheiSubscribeConfig(
    @Value("\${koshei.subscribe.enabled:false}") private val enabled: Boolean,
    @Value("\${koshei.subscribe.mqtt-url:tcp://localhost:1883}") private val mqttUrl: String,
    @Value("\${koshei.subscribe.group:Koshei}") private val group: String,
    @Value("\${koshei.subscribe.edge:Governance}") private val edge: String,
) {
    /** Always available so [DriftMonitor] can merge annotations even when the subscriber is off. */
    @Bean
    fun reconciliationStore(): ReconciliationStore = ReconciliationStore()

    /**
     * Live Paho subscriber, wired only when `koshei.subscribe.enabled=true`. Subscribes the whole node
     * namespace `spBv1.0/{group}/+/{edge}` (NBIRTH + NDATA + …) and forwards each message to the host.
     */
    @Bean(destroyMethod = "close")
    fun kosheiMqttClient(store: ReconciliationStore): MqttClient? {
        if (!enabled) return null
        val subscriber = KosheiGovernanceSubscriber(edge) { key, ann -> store.put(key, ann) }
        val client = MqttClient(mqttUrl, "resequence-twin-$group-$edge", MemoryPersistence())
        return try {
            client.connect(MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
            })
            val topicFilter = "spBv1.0/$group/+/$edge"
            client.subscribe(topicFilter, 1) { topic, message -> subscriber.onMessage(topic, message.payload) }
            log.info("koshei governance subscriber connected to {} on {}", mqttUrl, topicFilter)
            client
        } catch (e: Exception) {
            log.warn("koshei governance subscriber failed to connect to {} (continuing without): {}", mqttUrl, e.toString())
            try { client.close() } catch (_: Exception) {}
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KosheiSubscribeConfig::class.java)
    }
}
