package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.ble.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun PanelBindingEditor(
    capability: PanelCapability,
    binding: PanelBinding,
    services: List<BleService>,
    payloadFormats: List<PayloadFormat> = emptyList(),
    onChange: (PanelBinding) -> Unit,
) {
    val codecs = when (capability.type) {
        CapabilityType.BOOLEAN -> listOf(CodecType.BOOLEAN)
        CapabilityType.NUMBER -> numericPanelCodecs.toList()
        CapabilityType.ACTION -> listOf(CodecType.HEX)
        CapabilityType.TEXT -> listOf(CodecType.UTF8)
    }
    if (capability.type !in listOf(CapabilityType.ACTION, CapabilityType.TEXT)) {
        OperationEditor(tr(AppStrings.read_b6b97a), OperationKind.READ, binding.read, codecs, services, payloadFormats, capability.type) { onChange(binding.copy(read = it)) }
        OperationEditor(tr(AppStrings.receive_notifications_18d4c7), OperationKind.NOTIFY, binding.notify, codecs, services, payloadFormats, capability.type) { onChange(binding.copy(notify = it)) }
    }
    OperationEditor(tr(AppStrings.write_9e93bd), OperationKind.WRITE, binding.write, codecs, services, payloadFormats, capability.type) {
        onChange(binding.copy(write = it, actionValue = if (it?.payload != null || it == null) null else binding.actionValue))
    }
    if (capability.type == CapabilityType.ACTION && binding.write != null && binding.write.payload == null) Panel {
        val value = binding.actionValue.orEmpty()
        OutlinedTextField(
            value, { onChange(binding.copy(actionValue = it)) },
            label = { Text(tr(AppStrings.fixed_command_complete_hex_payload_929591)) },
            supportingText = { Text(tr(AppStrings.include_all_protocol_bytes_e_g_aa_01_ff_supports_1_512_33b295)) },
            isError = !validBindingHex(value, 1, 512),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private enum class OperationKind { READ, WRITE, NOTIFY }
private enum class EncodingMode { INLINE, PAYLOAD }

private fun BleCharacteristic.supports(kind: OperationKind) = when (kind) {
    OperationKind.READ -> BleProperty.READ in properties
    OperationKind.NOTIFY -> BleProperty.NOTIFY in properties || BleProperty.INDICATE in properties
    OperationKind.WRITE -> BleProperty.WRITE in properties || BleProperty.WRITE_WITHOUT_RESPONSE in properties
}

/** Duplicate UUIDs cannot be addressed safely by the current UUID-based transport. */
private fun List<BleService>.unambiguous(kind: OperationKind): List<BleService> =
    groupBy { it.uuid.lowercase() }.values.filter { it.size == 1 }.mapNotNull { matches ->
        val service = matches.single()
        val characteristics = service.characteristics.groupBy { it.id.characteristicUuid.lowercase() }
            .values.filter { it.size == 1 }.map { it.single() }.filter { it.supports(kind) }
        if (characteristics.isEmpty()) null else service.copy(characteristics = characteristics)
    }

private fun BleOperationBinding.select(characteristic: BleCharacteristic, kind: OperationKind): BleOperationBinding {
    val mode = if (kind != OperationKind.WRITE) PanelWriteMode.WITH_RESPONSE else when {
        writeMode == PanelWriteMode.WITH_RESPONSE && BleProperty.WRITE in characteristic.properties -> writeMode
        writeMode == PanelWriteMode.WITHOUT_RESPONSE && BleProperty.WRITE_WITHOUT_RESPONSE in characteristic.properties -> writeMode
        BleProperty.WRITE in characteristic.properties -> PanelWriteMode.WITH_RESPONSE
        else -> PanelWriteMode.WITHOUT_RESPONSE
    }
    return copy(serviceUuid = characteristic.id.serviceUuid, characteristicUuid = characteristic.id.characteristicUuid, writeMode = mode)
}

@Composable
private fun OperationEditor(
    title: String,
    kind: OperationKind,
    operation: BleOperationBinding?,
    codecs: List<CodecType>,
    services: List<BleService>,
    payloadFormats: List<PayloadFormat>,
    capabilityType: CapabilityType,
    onChange: (BleOperationBinding?) -> Unit,
) {
    val availableServices = services.unambiguous(kind)
    val compatibleFormats = compatiblePayloadFormats(payloadFormats, capabilityType)
    var pendingMode by remember { mutableStateOf<EncodingMode?>(null) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (operation == null) tr(AppStrings.disabled_8bb38e) else tr(AppStrings.enabled_save_to_apply_b19244), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(operation != null, onCheckedChange = { enabled ->
                if (!enabled) onChange(null)
                else {
                    val blank = BleOperationBinding("", "", codecs.first())
                    val first = availableServices.firstOrNull()?.characteristics?.firstOrNull()
                    onChange(if (first == null) blank else blank.select(first, kind))
                }
            }, modifier = Modifier.semantics { contentDescription = title })
        }
        if (operation != null) {
            Text(tr(AppStrings.enter_full_uuids_from_the_protocol_or_connect_to_choose_954a7f), color = Muted, style = MaterialTheme.typography.bodySmall)
            if (availableServices.isNotEmpty()) {
                PanelPicker(tr(AppStrings.select_discovered_service_abf6b3, title), availableServices.map { it.uuid to "Service ${shortUuid(it.uuid)}" },
                    availableServices.firstOrNull { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }?.uuid ?: tr(AppStrings.select_a_service_0a3ccf)) { uuid ->
                    val characteristic = availableServices.first { it.uuid == uuid }.characteristics.first()
                    onChange(operation.select(characteristic, kind))
                }
                val available = availableServices.firstOrNull { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }?.characteristics.orEmpty()
                PanelPicker(tr(AppStrings.select_discovered_characteristic_8ce602, title), available.map { it.id.characteristicUuid to "${shortUuid(it.id.characteristicUuid)} · ${propertyNames(it)}" },
                    available.firstOrNull { it.id.characteristicUuid.equals(operation.characteristicUuid, ignoreCase = true) }?.id?.characteristicUuid ?: tr(AppStrings.select_a_characteristic_f112ee)) { uuid ->
                    onChange(operation.select(available.first { it.id.characteristicUuid == uuid }, kind))
                }
            } else if (services.isNotEmpty()) {
                Text(tr(AppStrings.no_uniquely_identifiable_characteristic_supports_this_o_125c31), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(operation.serviceUuid, { onChange(operation.copy(serviceUuid = it)) },
                label = { Text(tr(AppStrings.service_uuid_f910d8, title)) }, isError = !bindingUuid.matches(operation.serviceUuid),
                supportingText = { Text(tr(AppStrings.full_128_bit_uuid_e_g_0000180f_0000_1000_8000_00805f9b3_903507)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(operation.characteristicUuid, { onChange(operation.copy(characteristicUuid = it)) },
                label = { Text(tr(AppStrings.characteristic_uuid_ccdbe4, title)) }, isError = !bindingUuid.matches(operation.characteristicUuid),
                singleLine = true, modifier = Modifier.fillMaxWidth())

            val matchingServices = services.filter { it.uuid.equals(operation.serviceUuid, ignoreCase = true) }
            val matchingCharacteristics = matchingServices.singleOrNull()?.characteristics.orEmpty()
                .filter { it.id.characteristicUuid.equals(operation.characteristicUuid, ignoreCase = true) }
            val characteristic = matchingCharacteristics.singleOrNull()
            val problem = when {
                matchingServices.size > 1 || matchingCharacteristics.size > 1 -> tr(AppStrings.duplicate_uuids_prevent_uniquely_locating_this_characte_d530fd)
                characteristic != null && !characteristic.supports(kind) -> tr(AppStrings.this_characteristic_does_not_support_check_the_protocol_9dcf25, title)
                else -> null
            }
            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (characteristic == null) Text(
                if (services.isEmpty()) tr(AppStrings.characteristic_not_verified_yet_connecting_checks_prope_ba938c) else tr(AppStrings.this_binding_was_not_found_in_the_current_gatt_check_th_7db0bf),
                color = Muted, style = MaterialTheme.typography.bodySmall)
            else Text(tr(AppStrings.discovered_f342a5, propertyNames(characteristic)), color = Muted, style = MaterialTheme.typography.bodySmall)

            val reusable = operation.payload != null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !reusable, onClick = { if (reusable) pendingMode = EncodingMode.INLINE }, label = { Text(tr(AppStrings.single_value_encoding_ea65a3)) })
                FilterChip(selected = reusable, onClick = { if (!reusable) pendingMode = EncodingMode.PAYLOAD },
                    label = { Text(tr(AppStrings.use_payload_format_80dba3)) }, enabled = compatibleFormats.isNotEmpty())
            }
            if (compatibleFormats.isEmpty()) Text(tr(AppStrings.define_a_valid_payload_format_with_a_field_matching_thi_1828e3), color = Muted, style = MaterialTheme.typography.bodySmall)
            if (reusable) {
                PayloadBindingFields(title, kind, capabilityType, operation, payloadFormats) { onChange(it) }
            } else {
                if (codecs.size > 1) PanelPicker(tr(AppStrings.data_type_a8a02f, title), codecs.map { it.name to it.bindingLabel() }, operation.codec.name) { selected ->
                    onChange(operation.withCodec(CodecType.valueOf(selected), kind == OperationKind.WRITE))
                } else Text(tr(AppStrings.data_type_5bf135, codecs.single().bindingLabel()), color = Muted, style = MaterialTheme.typography.bodySmall)
                key(operation.codec) {
                    PanelBindingFormatFields(title, operation, kind == OperationKind.WRITE) { onChange(it) }
                }
            }
            if (kind == OperationKind.WRITE) {
                val modes = buildList {
                    if (characteristic == null || BleProperty.WRITE in characteristic.properties) add(PanelWriteMode.WITH_RESPONSE)
                    if (characteristic == null || BleProperty.WRITE_WITHOUT_RESPONSE in characteristic.properties) add(PanelWriteMode.WITHOUT_RESPONSE)
                }
                if (modes.isNotEmpty()) Choices(modes.map { it.label() }, operation.writeMode.label()) { label ->
                    onChange(operation.copy(writeMode = modes.first { it.label() == label }))
                }
                Text(tr(AppStrings.a_response_confirms_gatt_delivery_not_business_success_5c1cd1), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (pendingMode != null && operation != null) AlertDialog(
        onDismissRequest = { pendingMode = null },
        title = { Text(if (pendingMode == EncodingMode.PAYLOAD) tr(AppStrings.use_a_predefined_payload_format_9500cb) else tr(AppStrings.restore_single_value_encoding_f6ebbd)) },
        text = { Text(if (pendingMode == EncodingMode.PAYLOAD)
            tr(AppStrings.switching_clears_the_single_value_type_byte_order_offse_bfdb1a)
        else tr(AppStrings.switching_removes_format_and_field_references_and_resto_e22683)) },
        confirmButton = { TextButton(onClick = {
            when (pendingMode) {
                EncodingMode.PAYLOAD -> if (compatibleFormats.isNotEmpty()) onChange(operation.withPayload(PayloadReference("")))
                EncodingMode.INLINE -> onChange(BleOperationBinding(operation.serviceUuid, operation.characteristicUuid, codecs.first(), operation.writeMode))
                null -> Unit
            }
            pendingMode = null
        }, enabled = pendingMode != EncodingMode.PAYLOAD || compatibleFormats.isNotEmpty()) { Text(tr(AppStrings.clear_and_switch_e109ee)) } },
        dismissButton = { TextButton(onClick = { pendingMode = null }) { Text(tr(AppStrings.keep_current_configuration_0cfaa1)) } },
    )
}

@Composable
private fun PayloadBindingFields(
    title: String,
    kind: OperationKind,
    capabilityType: CapabilityType,
    operation: BleOperationBinding,
    formats: List<PayloadFormat>,
    onChange: (BleOperationBinding) -> Unit,
) {
    val reference = operation.payload ?: return
    val available = compatiblePayloadFormats(formats, capabilityType)
    val format = formats.singleOrNull { it.id == reference.formatId }
    PanelPicker(tr(AppStrings.payload_format_2c4fc2, title), available.map { it.id to "${it.label} · ${it.encoding.name}" }, reference.formatId) { id ->
        val selected = available.first { it.id == id }
        onChange(operation.withPayload(PayloadReference(id,
            if (capabilityType == CapabilityType.ACTION) null else selected.fields.firstOrNull { it.compatibleWith(capabilityType) }?.id)))
    }
    if (format == null) Text(tr(AppStrings.define_a_payload_format_first_then_select_it_here_1cbcb1), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    else if (capabilityType == CapabilityType.ACTION) {
        Text(tr(AppStrings.pressing_the_button_serializes_the_entire_payload_using_19ed3d), color = Muted, style = MaterialTheme.typography.bodySmall)
        val missing = format.fields.filter { it.defaultValue == null }
        if (missing.isNotEmpty()) Text(tr(AppStrings.missing_defaults_96fe55, missing.joinToString { it.id }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else {
        val fields = format.fields.filter { it.compatibleWith(capabilityType) }
        PanelPicker(tr(AppStrings.widget_field_97bc41, title), fields.map { it.id to "${it.id} · ${it.codec.name}" }, reference.fieldId.orEmpty()) {
            onChange(operation.withPayload(reference.copy(fieldId = it)))
        }
        if (fields.none { it.id == reference.fieldId }) Text(tr(AppStrings.select_a_field_matching_the_widget_type_073d42), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (kind == OperationKind.WRITE) {
            Text(tr(AppStrings.widget_input_fills_this_field_other_fields_use_their_de_4ab8fb), color = Muted, style = MaterialTheme.typography.bodySmall)
            val missing = format.fields.filter { it.id != reference.fieldId && it.defaultValue == null }
            if (missing.isNotEmpty()) Text(tr(AppStrings.other_fields_missing_defaults_4537e3, missing.joinToString { it.id }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else Text(tr(AppStrings.incoming_payloads_are_validated_and_decoded_before_the_5d9993), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun PayloadField.compatibleWith(type: CapabilityType): Boolean = when (type) {
    CapabilityType.BOOLEAN -> codec == CodecType.BOOLEAN
    CapabilityType.NUMBER -> codec in numericPanelCodecs
    CapabilityType.ACTION -> false
    CapabilityType.TEXT -> codec == CodecType.UTF8
}

private fun compatiblePayloadFormats(formats: List<PayloadFormat>, type: CapabilityType): List<PayloadFormat> =
    formats.filter { format ->
        formats.count { it.id == format.id } == 1 && validatePayloadFormats(listOf(format)).isEmpty() &&
            (type == CapabilityType.ACTION || format.fields.any { it.compatibleWith(type) })
    }

/** Selecting a shared format clears all inline encoding options; only the BLE address/mode survive. */
private fun BleOperationBinding.withPayload(reference: PayloadReference): BleOperationBinding =
    BleOperationBinding(serviceUuid = serviceUuid, characteristicUuid = characteristicUuid, writeMode = writeMode, payload = reference)

private fun BleOperationBinding.withCodec(selected: CodecType, isWrite: Boolean): BleOperationBinding = when (selected) {
    CodecType.HEX -> BleOperationBinding(serviceUuid, characteristicUuid, selected, writeMode)
    CodecType.BOOLEAN -> copy(codec = selected, byteOrder = PanelByteOrder.LITTLE_ENDIAN,
        writeMode = if (isWrite) writeMode else PanelWriteMode.WITH_RESPONSE,
        readOffset = if (isWrite) 0 else readOffset, scale = 1.0, bias = 0.0,
        writePrefixHex = if (isWrite) writePrefixHex else "", writeSuffixHex = if (isWrite) writeSuffixHex else "",
        falseHex = if (codec == CodecType.BOOLEAN) falseHex else "00", trueHex = if (codec == CodecType.BOOLEAN) trueHex else "01")
    else -> copy(codec = selected,
        writeMode = if (isWrite) writeMode else PanelWriteMode.WITH_RESPONSE,
        byteOrder = if (selected == CodecType.UINT8 || selected == CodecType.INT8) PanelByteOrder.LITTLE_ENDIAN else byteOrder,
        readOffset = if (isWrite) 0 else readOffset,
        scale = if (codec in numericPanelCodecs) scale else 1.0, bias = if (codec in numericPanelCodecs) bias else 0.0,
        writePrefixHex = if (isWrite) writePrefixHex else "", writeSuffixHex = if (isWrite) writeSuffixHex else "",
        falseHex = "00", trueHex = "01")
}

private val bindingUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private fun PanelWriteMode.label() = if (this == PanelWriteMode.WITH_RESPONSE) tr(AppStrings.write_with_response_7b158f) else tr(AppStrings.write_without_response_e8c7de)
private fun shortUuid(uuid: String) = if (uuid.startsWith("0000") && uuid.length == 36) uuid.substring(4, 8).uppercase() else uuid
private fun propertyNames(characteristic: BleCharacteristic) = buildList {
    if (BleProperty.READ in characteristic.properties) add(tr(AppStrings.read_d001ff))
    if (BleProperty.WRITE in characteristic.properties || BleProperty.WRITE_WITHOUT_RESPONSE in characteristic.properties) add(tr(AppStrings.write_144d2c))
    if (BleProperty.NOTIFY in characteristic.properties || BleProperty.INDICATE in characteristic.properties) add(tr(AppStrings.notify_7a66c0))
}.joinToString(" / ")

private fun CodecType.bindingLabel() = when (this) {
    CodecType.UINT8 -> tr(AppStrings.uint8_unsigned_8_bit_integer_ffb1d7)
    CodecType.INT8 -> tr(AppStrings.int8_signed_8_bit_integer_71bb63)
    CodecType.UINT16 -> tr(AppStrings.uint16_unsigned_16_bit_integer_74acf0)
    CodecType.INT16 -> tr(AppStrings.int16_signed_16_bit_integer_beab3b)
    CodecType.UINT32 -> tr(AppStrings.uint32_unsigned_32_bit_integer_b6b1d4)
    CodecType.INT32 -> tr(AppStrings.int32_signed_32_bit_integer_daefba)
    CodecType.FLOAT32 -> tr(AppStrings.float32_32_bit_floating_point_e75c5e)
    CodecType.BOOLEAN -> tr(AppStrings.boolean_custom_true_false_bytes_1c826c)
    CodecType.UTF8 -> "UTF-8"
    CodecType.HEX -> tr(AppStrings.hex_fixed_complete_payload_d22253)
}
