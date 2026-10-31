package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun MqttPanelBindingEditor(capability: PanelCapability, binding: PanelBinding, formats: List<PayloadFormat>, onChange: (PanelBinding) -> Unit) {
    if (capability.type != CapabilityType.ACTION) MqttOperationEditor(tr(AppStrings.subscribe_to_state_b8115f), capability, binding.subscribe, formats, false) { onChange(binding.copy(subscribe = it)) }
    MqttOperationEditor(tr(AppStrings.publish_command_8863c4), capability, binding.publish, formats, true) { onChange(binding.copy(publish = it)) }
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
            Text(tr(AppStrings.use_a_complete_topic_e_g_devices_lamp_01_panel_bindings_7428a5, if (publish) "set" else "state"), color = Muted)
            Choices(listOf("QoS 0", "QoS 1", "QoS 2"), "QoS ${operation.qos}") { onChange(operation.copy(qos = it.last().digitToInt())) }
            PanelPicker(tr(AppStrings.payload_format_2c4fc2, title), formats.map { it.id to "${it.label} · ${it.encoding}" }, operation.payload.formatId) {
                onChange(operation.copy(payload = PayloadReference(it)))
            }
            val format = formats.singleOrNull { it.id == operation.payload.formatId }
            if (capability.type != CapabilityType.ACTION) {
                val fields = format?.fields.orEmpty().filter { if (capability.type == CapabilityType.BOOLEAN) it.codec == CodecType.BOOLEAN else it.codec in numericPanelCodecs }
                PanelPicker(tr(AppStrings.widget_field_f686aa, title), fields.map { it.id to it.id }, operation.payload.fieldId.orEmpty()) {
                    onChange(operation.copy(payload = operation.payload.copy(fieldId = it)))
                }
            }
            Text(if (publish) tr(AppStrings.sends_a_complete_payload_with_defaults_for_other_fields_e6554d)
                else tr(AppStrings.subscribe_to_receive_state_including_retained_messages_b49bbf), color = Muted)
        }
    }
}
