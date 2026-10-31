package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.MqttConnectionConfig
import com.lr.thingstalk.domain.mqtt.MqttException
import com.lr.thingstalk.domain.mqtt.MqttMessage
import com.lr.thingstalk.domain.mqtt.MqttQos
import de.kempmobil.ktor.mqtt.PublishRequest
import de.kempmobil.ktor.mqtt.QoS
import de.kempmobil.ktor.mqtt.ReasonCode
import de.kempmobil.ktor.mqtt.SubscriptionOptions
import de.kempmobil.ktor.mqtt.Topic
import de.kempmobil.ktor.mqtt.TopicFilter
import de.kempmobil.ktor.mqtt.ws.MqttClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.io.bytestring.ByteString
import kotlin.time.Duration.Companion.seconds

internal class KtorMqttTransport(config: MqttConnectionConfig) : MqttTransport {
    private val http = HttpClient { install(WebSockets) }
    private val client = try {
        MqttClient(config.url) {
            clientId = config.clientId
            username = config.username
            password = config.password
            keepAliveSeconds = 30u
            ackMessageTimeout = 10.seconds
            sessionExpiryInterval = 0.seconds
            receiveMaximum = 64u
            maximumPacketSize = 1_048_576u
            connection { http = { this@KtorMqttTransport.http } }
        }
    } catch (error: Exception) {
        http.close()
        http.coroutineContext.cancel()
        throw error
    }

    override val connected = client.connectionState.map { it.isConnected }
    override val messages = client.publishedPackets.map { packet ->
        MqttMessage(packet.topic.name, packet.payload.toByteArray(), packet.qoS.toDomain(), packet.isRetainMessage)
    }

    override suspend fun connect() {
        val acknowledgement = client.connect(isCleanStart = true).getOrThrow()
        mqttRequire(acknowledgement.isSuccess) { brokerFailure("连接", acknowledgement.reason) }
        mqttRequire(connected.first()) { "服务器已断开 MQTT 连接，请检查协议版本和认证信息。" }
    }

    override suspend fun disconnect() = client.disconnect()

    override suspend fun subscribe(topicFilter: String, qos: MqttQos): MqttQos {
        mqttRequire(!Topic(topicFilter).containsWildcard() || client.isWildcardSubscriptionAvailable) {
            "此服务器不支持通配符订阅。"
        }
        mqttRequire(!Topic(topicFilter).isShared() || client.isSharedSubscriptionAvailable) {
            "此服务器不支持共享订阅。"
        }
        val acknowledgement = client.subscribe(listOf(TopicFilter(Topic(topicFilter), SubscriptionOptions(qos.toLibrary())))).getOrThrow()
        val reason = acknowledgement.reasons.singleOrNull()
            ?: throw MqttException("服务器返回了无效的订阅确认。")
        return when (reason.code) {
            0 -> MqttQos.AT_MOST_ONCE
            1 -> MqttQos.AT_LEAST_ONCE
            2 -> MqttQos.EXACTLY_ONCE
            else -> throw MqttException(brokerFailure("订阅", reason))
        }
    }

    override suspend fun unsubscribe(topicFilter: String) {
        val acknowledgement = client.unsubscribe(listOf(Topic(topicFilter))).getOrThrow()
        val reason = acknowledgement.reasons.singleOrNull()
            ?: throw MqttException("服务器返回了无效的取消订阅确认。")
        mqttRequire(reason.code == 0 || reason.code == 17) { brokerFailure("取消订阅", reason) }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos, retain: Boolean) {
        mqttRequire(qos.toLibrary().value <= client.maxQos.value) { "服务器不支持所选的 QoS，请降低 QoS 后重试。" }
        mqttRequire(!retain || client.isRetainAvailable) { "此服务器不支持保留消息。" }
        // Leave space for topic and MQTT properties; do not rely on the library silently truncating a packet.
        mqttRequire(payload.size.toLong() + topic.encodeToByteArray().size + 32L <= client.maxPacketSize.toLong()) {
            "消息超过服务器允许的数据包大小。"
        }
        val acknowledgement = client.publish(PublishRequest(topic) {
            desiredQoS = qos.toLibrary()
            isRetainMessage = retain
            payload(ByteString(payload))
        }).getOrThrow()
        mqttRequire(acknowledgement.reason.code < 128) { brokerFailure("发布", acknowledgement.reason) }
    }

    override fun close() {
        client.close()
        // HttpClient.close() alone waits for active requests; cancel the WebSocket immediately too.
        http.coroutineContext.cancel()
    }
}

private fun MqttQos.toLibrary() = when (this) {
    MqttQos.AT_MOST_ONCE -> QoS.AT_MOST_ONCE
    MqttQos.AT_LEAST_ONCE -> QoS.AT_LEAST_ONCE
    MqttQos.EXACTLY_ONCE -> QoS.EXACTLY_ONE
}

private fun QoS.toDomain() = when (this) {
    QoS.AT_MOST_ONCE -> MqttQos.AT_MOST_ONCE
    QoS.AT_LEAST_ONCE -> MqttQos.AT_LEAST_ONCE
    QoS.EXACTLY_ONE -> MqttQos.EXACTLY_ONCE
}

private fun brokerFailure(action: String, reason: ReasonCode): String = when (reason.code) {
    132 -> "服务器不支持 MQTT 5。"
    133 -> "服务器拒绝了客户端 ID。"
    134, 135 -> "${action}被服务器拒绝，请检查用户名、密码和访问权限。"
    136, 137, 139 -> "服务器暂时不可用，请稍后重试。"
    else -> "服务器拒绝${action}（MQTT 原因码 ${reason.code}）。"
}
