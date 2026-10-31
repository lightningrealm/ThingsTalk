package com.lr.thingstalk.presentation.panel

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lr.thingstalk.domain.panel.CapabilityType
import com.lr.thingstalk.domain.panel.PayloadEncoding
import com.lr.thingstalk.presentation.components.KeyValue
import com.lr.thingstalk.presentation.components.Page
import com.lr.thingstalk.presentation.components.Panel
import com.lr.thingstalk.presentation.components.Tag

@Composable
fun PanelDescriptionPage(viewModel: PanelViewModel) {
    val state by viewModel.state.collectAsState()
    Page("PANEL CAPABILITIES", "能力与通信绑定", "来自当前面板配置 · schemaVersion ${state.definition.schemaVersion}") {
        if (state.definition.capabilities.isEmpty()) Text("还没有组件能力。请先添加组件。")
        state.definition.capabilities.forEach { capability ->
            val binding = state.definition.bindings.singleOrNull { it.capabilityId == capability.id }
            Panel {
                Text(capability.label, style = MaterialTheme.typography.titleMedium)
                Tag(capability.id)
                KeyValue("类型", when (capability.type) {
                    CapabilityType.NUMBER -> "数值 ${capability.minimum ?: 0.0}–${capability.maximum ?: 255.0} ${capability.unit}"
                    CapabilityType.BOOLEAN -> "布尔"
                    CapabilityType.ACTION -> "动作"
                })
                binding?.controlTransport?.let { KeyValue("控制通道", it.name) }
                binding?.stateTransport?.let { KeyValue("状态来源", it.name) }
                if (binding == null) Text("尚未绑定操作")
                listOf("读取" to binding?.read, "写入" to binding?.write, "通知" to binding?.notify).forEach { (label, operation) ->
                    if (operation != null) {
                        val reference = operation.payload
                        val format = state.definition.payloadFormats.singleOrNull { it.id == reference?.formatId }
                        KeyValue(label, if (reference == null) operation.codec.name else "${format?.label ?: reference.formatId} · ${reference.fieldId ?: "完整默认报文"}")
                        if (reference == null && capability.type == CapabilityType.NUMBER) {
                            KeyValue("字节序", operation.byteOrder.name)
                            KeyValue("换算", "原始值 × ${operation.scale} + ${operation.bias}")
                        }
                        if (reference == null && label != "写入") KeyValue("读取偏移", "${operation.readOffset} 字节")
                        else if (reference == null) {
                            if (operation.writePrefixHex.isNotBlank()) KeyValue("写入前缀", operation.writePrefixHex)
                            if (operation.writeSuffixHex.isNotBlank()) KeyValue("写入后缀", operation.writeSuffixHex)
                        }
                        if (reference == null && capability.type == CapabilityType.BOOLEAN) KeyValue("布尔编码", "关闭 ${operation.falseHex} / 开启 ${operation.trueHex}")
                        Text("服务 ${operation.serviceUuid}\n特征 ${operation.characteristicUuid}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                listOf("发布" to binding?.publish, "订阅" to binding?.subscribe).forEach { (label, op) ->
                    if (op != null) {
                        KeyValue(label, op.topic)
                        KeyValue("报文 / 字段", "${op.payload.formatId} / ${op.payload.fieldId ?: "完整默认报文"}")
                        KeyValue("QoS", op.qos.toString())
                    }
                }
                binding?.actionValue?.let { KeyValue("固定数据", it) }
            }
        }
        state.definition.payloadFormats.forEach { format ->
            Panel {
                Text(format.label, style = MaterialTheme.typography.titleMedium)
                KeyValue("格式 ID", format.id)
                KeyValue("编码", if (format.encoding == PayloadEncoding.BINARY) "定长二进制 · 字段按顺序排列" else "JSON · 字段名作为键")
                if (format.prefixHex.isNotEmpty()) KeyValue("帧头", format.prefixHex)
                if (format.suffixHex.isNotEmpty()) KeyValue("帧尾", format.suffixHex)
                format.fields.forEach { field ->
                    KeyValue(field.id, field.codec.name)
                    if (format.encoding == PayloadEncoding.BINARY) {
                        KeyValue("${field.id} · 字节序 / 换算", "${field.byteOrder.name} · × ${field.scale} + ${field.bias}")
                    }
                    field.defaultValue?.let { KeyValue("${field.id} · 写入默认值", it) }
                }
            }
        }
    }
}
