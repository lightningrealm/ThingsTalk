package com.lr.thingstalk.domain.panel

import kotlinx.serialization.Serializable

/** Exact topics deliberately keep one component tied to one device's state. */
@Serializable
data class MqttOperationBinding(
    val topic: String = "",
    val payload: PayloadReference = PayloadReference(""),
    val qos: Int = 0,
    val retain: Boolean = false,
)

val PanelBinding.canWrite: Boolean get() = when (selectedControl) { PanelTransport.BLE -> write != null; PanelTransport.MQTT -> publish != null; else -> false }
val PanelBinding.canObserve: Boolean get() = when (selectedState) { PanelTransport.BLE -> notify != null; PanelTransport.MQTT -> subscribe != null; else -> false }

internal fun validateMqttBinding(definition: PanelDefinition, binding: PanelBinding): List<String> = buildList {
    fun issue(condition: Boolean, message: String) { if (condition) add(message) }
    val capability = definition.capabilities.singleOrNull { it.id == binding.capabilityId }
    issue(capability == null, "MQTT 绑定引用不存在的能力：${binding.capabilityId}")
    issue(binding.read != null || binding.write != null || binding.notify != null || binding.actionValue != null, "MQTT 绑定不能包含 BLE 操作或固定 HEX 动作")
    issue(binding.publish == null && binding.subscribe == null, "MQTT 绑定至少需要发布或订阅")
    issue(capability?.type == CapabilityType.TEXT && (binding.publish == null || binding.subscribe != null), "文本输入仅支持 MQTT 发布")
    issue(capability?.type == CapabilityType.ACTION && (binding.publish == null || binding.subscribe != null), "MQTT 动作仅支持发布")
    listOf(true to binding.publish, false to binding.subscribe).forEach { (publish, operation) ->
        if (operation != null) {
            issue(operation.topic.isBlank() || operation.topic.startsWith("\$share/") || operation.topic.encodeToByteArray().size > 65535 || operation.topic.any { it == '\u0000' || it == '+' || it == '#' || it.isSurrogate() }, "MQTT 面板需要不含通配符的有效 topic")
            issue(operation.qos !in 0..2, "MQTT QoS 需要为 0、1 或 2")
            issue(operation.retain, "面板命令不支持 retained 发布；订阅无需设置 retain")
            val format = definition.payloadFormats.singleOrNull { it.id == operation.payload.formatId }
            issue(format == null, "MQTT 引用的报文格式不存在")
            if (format != null) {
                val field = format.fields.singleOrNull { it.id == operation.payload.fieldId }
                if (capability?.type == CapabilityType.ACTION) {
                    issue(operation.payload.fieldId != null, "动作发布完整默认报文，不指定字段")
                } else {
                    issue(field == null, "MQTT 属性需要有效报文字段")
                    if (field != null) issue(when (capability?.type) {
                        CapabilityType.BOOLEAN -> field.codec != CodecType.BOOLEAN
                        CapabilityType.NUMBER -> field.codec !in numericPanelCodecs
                        CapabilityType.TEXT -> field.codec != CodecType.UTF8
                        else -> true
                    }, "MQTT 字段与能力类型不匹配")
                }
                if (publish) format.fields.filterNot { capability?.type != CapabilityType.ACTION && it.id == operation.payload.fieldId }
                    .forEach { issue(it.defaultValue == null, "发布字段 ${it.id} 需要默认值") }
            }
        }
    }
}
