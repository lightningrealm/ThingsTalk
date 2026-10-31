package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.MqttMessage
import com.lr.thingstalk.domain.mqtt.MqttQos
import kotlinx.coroutines.flow.Flow

/** Data-layer seam for a network session; its implementation owns the third-party client. */
internal interface MqttTransport {
    val connected: Flow<Boolean>
    val messages: Flow<MqttMessage>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun subscribe(topicFilter: String, qos: MqttQos): MqttQos
    suspend fun unsubscribe(topicFilter: String)
    suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean)
    fun close()
}
