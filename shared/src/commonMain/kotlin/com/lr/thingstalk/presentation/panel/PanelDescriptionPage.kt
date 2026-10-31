package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
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
    Page("PANEL CAPABILITIES", tr(AppStrings.capabilities_and_bindings_c77cc4), tr(AppStrings.current_panel_configuration_schemaversion_07ba96, state.definition.schemaVersion)) {
        if (state.definition.capabilities.isEmpty()) Text(tr(AppStrings.no_capabilities_yet_add_a_widget_first_0d64da))
        state.definition.capabilities.forEach { capability ->
            val binding = state.definition.bindings.singleOrNull { it.capabilityId == capability.id }
            Panel {
                Text(capability.label, style = MaterialTheme.typography.titleMedium)
                Tag(capability.id)
                KeyValue(tr(AppStrings.type_e4e46c), when (capability.type) {
                    CapabilityType.NUMBER -> tr(AppStrings.number_112789, capability.minimum ?: 0.0, capability.maximum ?: 255.0, capability.unit)
                    CapabilityType.BOOLEAN -> tr(AppStrings.boolean_a091ff)
                    CapabilityType.ACTION -> tr(AppStrings.action_d9d982)
                    CapabilityType.TEXT -> tr(AppStrings.text_input)
                })
                binding?.controlTransport?.let { KeyValue(tr(AppStrings.control_channel_cdcfeb), it.name) }
                binding?.stateTransport?.let { KeyValue(tr(AppStrings.state_source_377857), it.name) }
                if (binding == null) Text(tr(AppStrings.no_operation_bound_ad33a9))
                listOf(tr(AppStrings.read_b6b97a) to binding?.read, tr(AppStrings.write_9e93bd) to binding?.write, tr(AppStrings.notify_7a66c0) to binding?.notify).forEach { (label, operation) ->
                    if (operation != null) {
                        val reference = operation.payload
                        val format = state.definition.payloadFormats.singleOrNull { it.id == reference?.formatId }
                        KeyValue(label, if (reference == null) operation.codec.name else "${format?.label ?: reference.formatId} · ${reference.fieldId ?: tr(AppStrings.complete_default_payload_929818)}")
                        if (reference == null && capability.type == CapabilityType.NUMBER) {
                            KeyValue(tr(AppStrings.byte_order_a15ac8), operation.byteOrder.name)
                            KeyValue(tr(AppStrings.conversion_942742), tr(AppStrings.raw_966247, operation.scale, operation.bias))
                        }
                        if (reference == null && label != tr(AppStrings.write_9e93bd)) KeyValue(tr(AppStrings.read_offset_17e6c9), tr(AppStrings.bytes_c55609, operation.readOffset))
                        else if (reference == null) {
                            if (operation.writePrefixHex.isNotBlank()) KeyValue(tr(AppStrings.write_prefix_9a7ddf), operation.writePrefixHex)
                            if (operation.writeSuffixHex.isNotBlank()) KeyValue(tr(AppStrings.write_suffix_7c5502), operation.writeSuffixHex)
                        }
                        if (reference == null && capability.type == CapabilityType.BOOLEAN) KeyValue(tr(AppStrings.boolean_encoding_41411e), tr(AppStrings.off_on_6abb88, operation.falseHex, operation.trueHex))
                        Text(tr(AppStrings.service_ncharacteristic_02a5f6, operation.serviceUuid, operation.characteristicUuid), style = MaterialTheme.typography.bodySmall)
                    }
                }
                listOf(tr(AppStrings.publish_94f172) to binding?.publish, tr(AppStrings.subscribe_5319af) to binding?.subscribe).forEach { (label, op) ->
                    if (op != null) {
                        KeyValue(label, op.topic)
                        KeyValue(tr(AppStrings.payload_field_852467), "${op.payload.formatId} / ${op.payload.fieldId ?: tr(AppStrings.complete_default_payload_929818)}")
                        KeyValue("QoS", op.qos.toString())
                    }
                }
                binding?.actionValue?.let { KeyValue(tr(AppStrings.fixed_data_4dec8c), it) }
            }
        }
        state.definition.payloadFormats.forEach { format ->
            Panel {
                Text(format.label, style = MaterialTheme.typography.titleMedium)
                KeyValue(tr(AppStrings.format_id_ee5bf1), format.id)
                KeyValue(tr(AppStrings.encoding_c94faa), if (format.encoding == PayloadEncoding.BINARY) tr(AppStrings.fixed_length_binary_fields_in_order_9777dc) else tr(AppStrings.json_field_names_as_keys_d4edb3))
                if (format.prefixHex.isNotEmpty()) KeyValue(tr(AppStrings.header_29a161), format.prefixHex)
                if (format.suffixHex.isNotEmpty()) KeyValue(tr(AppStrings.trailer_f1bf8b), format.suffixHex)
                format.fields.forEach { field ->
                    KeyValue(field.id, field.codec.name)
                    if (format.encoding == PayloadEncoding.BINARY) {
                        KeyValue(tr(AppStrings.byte_order_conversion_49beea, field.id), "${field.byteOrder.name} · × ${field.scale} + ${field.bias}")
                    }
                    field.defaultValue?.let { KeyValue(tr(AppStrings.default_write_value_39167b, field.id), it) }
                }
            }
        }
    }
}
