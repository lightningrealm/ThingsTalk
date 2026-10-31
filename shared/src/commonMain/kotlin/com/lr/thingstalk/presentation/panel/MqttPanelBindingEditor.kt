package com.lr.thingstalk.presentation.panel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun MqttPanelBindingEditor(capability: PanelCapability, binding: PanelBinding, formats: List<PayloadFormat>, onChange: (PanelBinding) -> Unit) {
    if (capability.type != CapabilityType.ACTION) MqttOperationEditor("订阅状态", capability, binding.subscribe, formats, false) { onChange(binding.copy(subscribe = it)) }
    MqttOperationEditor("发布命令", capability, binding.publish, formats, true) { onChange(binding.copy(publish = it)) }
}

@Composable
private fun MqttOperationEditor(title: String, capability: PanelCapability, operation: MqttOperationBinding?, formats: List<PayloadFormat>, publish: Boolean, onChange: (MqttOperationBinding?) -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Switch(operation != null, { onChange(if (it) MqttOperationBinding() else null) })
        }
        if (operation != null) {
            OutlinedTextField(operation.topic, { onChange(operation.copy(topic = it)) }, label = { Text("$title · Topic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("使用完整 topic，例如 devices/lamp-01/${if (publish) "set" else "state"}。面板绑定不使用 + / # 通配符。", color = Muted)
            Choices(listOf("QoS 0", "QoS 1", "QoS 2"), "QoS ${operation.qos}") { onChange(operation.copy(qos = it.last().digitToInt())) }
            PanelPicker("$title · 报文格式", formats.map { it.id to "${it.label} · ${it.encoding}" }, operation.payload.formatId) {
                onChange(operation.copy(payload = PayloadReference(it)))
            }
            val format = formats.singleOrNull { it.id == operation.payload.formatId }
            if (capability.type != CapabilityType.ACTION) {
                val fields = format?.fields.orEmpty().filter { if (capability.type == CapabilityType.BOOLEAN) it.codec == CodecType.BOOLEAN else it.codec in numericPanelCodecs }
                PanelPicker("$title · 组件字段", fields.map { it.id to it.id }, operation.payload.fieldId.orEmpty()) {
                    onChange(operation.copy(payload = operation.payload.copy(fieldId = it)))
                }
            }
            Text(if (publish) "操作组件后发送完整报文，其余字段使用默认值。命令不设 retained；设备回传状态才更新组件，服务器确认不代表设备执行成功。"
                else "先订阅再接收状态，支持服务器保留的状态消息。格式不匹配时显示解析错误。", color = Muted)
        }
    }
}
