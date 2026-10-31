package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.domain.panel.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Own one executor per device connection. Subscriptions live until that connection disconnects. */
class MqttPanelOperations(private val repository: MqttRepository) : PanelOperations {
    private val mutex = Mutex()
    private val subscribed = mutableMapOf<String, Int>()

    override suspend fun read(definition: PanelDefinition, capabilityId: String): String =
        error("MQTT 没有通用读取操作，请通过状态 topic 接收设备回报")

    override suspend fun write(definition: PanelDefinition, capabilityId: String, value: String) {
        val (capability, binding) = resolve(definition, capabilityId)
        val operation = requireNotNull(binding.publish) { "能力未绑定发布 topic" }
        val bytes = PanelPayloadCodec.encode(definition, capability, operation.payload, value)
        repository.publish(operation.topic, bytes, MqttQos.entries[operation.qos], retain = false)
    }

    override fun observe(definition: PanelDefinition, capabilityId: String): Flow<String> =
        observeEvents(definition, capabilityId).mapNotNull { (it as? PanelObservation.Value)?.value }

    override fun observeEvents(definition: PanelDefinition, capabilityId: String): Flow<PanelObservation> = channelFlow {
        val (capability, binding) = resolve(definition, capabilityId)
        val operation = requireNotNull(binding.subscribe) { "能力未绑定订阅 topic" }
        // Start reception before SUBSCRIBE: retained state may arrive before its acknowledgement.
        launch(start = CoroutineStart.UNDISPATCHED) {
            repository.messages.filter { it.topic == operation.topic }.collect { message ->
                val observation = try {
                    PanelObservation.Value(PanelPayloadCodec.decode(definition, capability, operation.payload, message.payload))
                } catch (error: IllegalArgumentException) {
                    PanelObservation.InvalidPayload(error.message?.take(300) ?: "MQTT 报文解析失败")
                }
                send(observation)
            }
            error("MQTT 消息流已结束")
        }
        launch {
            repository.state.collect { check(it.status == MqttConnectionStatus.CONNECTED) { "MQTT 连接已断开" } }
        }
        mutex.withLock {
            if (subscribed[operation.topic] != operation.qos) {
                repository.subscribe(operation.topic, MqttQos.entries[operation.qos])
                subscribed[operation.topic] = operation.qos
            }
        }
        send(PanelObservation.Ready)
        awaitCancellation()
    }

    private fun resolve(definition: PanelDefinition, id: String): Pair<PanelCapability, PanelBinding> {
        require(definition.transport == PanelTransport.MQTT) { "需要 MQTT 面板" }
        require(validatePanel(definition).isEmpty()) { validatePanel(definition).joinToString("\n") }
        return definition.capabilities.single { it.id == id } to definition.bindings.single { it.capabilityId == id }
    }
}
