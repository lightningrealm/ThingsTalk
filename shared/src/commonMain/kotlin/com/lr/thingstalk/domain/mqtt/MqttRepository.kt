package com.lr.thingstalk.domain.mqtt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class MqttConnectionState(
    val status: MqttConnectionStatus = MqttConnectionStatus.DISCONNECTED,
    val endpoint: String? = null,
    val error: String? = null,
)

enum class MqttQos { AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE }

data class MqttConnectionConfig(
    val url: String,
    val clientId: String,
    val username: String? = null,
    val password: String? = null,
) {
    // Configuration may contain credentials. Never include them in diagnostic output.
    override fun toString(): String = "MqttConnectionConfig(credentials=redacted)"
}

data class MqttSubscription(val topicFilter: String, val qos: MqttQos)

data class MqttMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: MqttQos,
    val retained: Boolean,
)

/** A safe, user-facing failure; transport exceptions and broker-provided text are not exposed. */
class MqttException(message: String) : Exception(message)

/**
 * One MQTT 5 session. Transport and platform APIs stay outside the domain layer.
 * Each connection starts a clean session; reconnecting and restoring subscriptions are explicit actions.
 */
interface MqttRepository {
    val state: StateFlow<MqttConnectionState>
    /** Subscriptions acknowledged by the current connection, with the granted QoS. */
    val subscriptions: StateFlow<List<MqttSubscription>>
    /** Live messages, without replay. Collect before subscribing; collectors must keep up. */
    val messages: Flow<MqttMessage>

    suspend fun connect(config: MqttConnectionConfig)
    suspend fun disconnect()
    suspend fun subscribe(topicFilter: String, qos: MqttQos)
    suspend fun unsubscribe(topicFilter: String)
    /** QoS acknowledges delivery to the broker, not successful execution by a device. */
    suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean)
    /** Terminal, idempotent resource release. This repository cannot be reused afterwards. */
    fun close()
}
