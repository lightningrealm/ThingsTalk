package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

/** Edits reusable wire formats. Referenced identifiers stay stable until bindings are removed. */
@Composable
internal fun PayloadFormatsEditor(
    formats: List<PayloadFormat>,
    bindings: List<PanelBinding>,
    onChange: (List<PayloadFormat>) -> Unit,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    // List positions can be reused after structural edits; IDs themselves remain editable.
    var formatRevision by remember { mutableIntStateOf(0) }
    Panel {
        Text(tr(AppStrings.define_payloads_then_select_fields_for_widgets_one_payl_428280), color = Muted)
        Text(tr(AppStrings.binary_fields_are_packed_in_order_json_uses_flat_numeri_b3bb15), color = Muted, style = MaterialTheme.typography.bodySmall)
        formats.forEachIndexed { index, format ->
            TextButton(onClick = { selectedIndex = index }, modifier = Modifier.fillMaxWidth()) {
                Text(tr(AppStrings.fields_2686e3, if (index == selectedIndex) "● " else "", format.label.ifBlank { tr(AppStrings.unnamed_format_1fe1ee) }, format.id, format.fields.size))
            }
        }
        OutlinedButton(onClick = {
            var suffix = 1
            while (formats.any { it.id == "format-$suffix" }) suffix++
            selectedIndex = formats.size
            formatRevision++
            onChange(formats + PayloadFormat(id = "format-$suffix", label = tr(AppStrings.payload_format_bc8670, suffix), fields = listOf(PayloadField(id = "value"))))
        }, enabled = formats.size < 32, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.add_payload_format_81953b)) }
        if (formats.isEmpty()) Text(tr(AppStrings.widgets_can_still_use_single_value_encoding_add_a_forma_8b22e9), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    formats.getOrNull(selectedIndex)?.let { format ->
        key(selectedIndex, formatRevision) {
            PayloadFormatDetail(format, bindings,
                onChange = { changed -> onChange(formats.mapIndexed { index, current -> if (index == selectedIndex) changed else current }) },
                onRemove = {
                    onChange(formats.filterIndexed { index, _ -> index != selectedIndex })
                    selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                    formatRevision++
                },
            )
        }
    }
}

@Composable
private fun PayloadFormatDetail(
    format: PayloadFormat,
    bindings: List<PanelBinding>,
    onChange: (PayloadFormat) -> Unit,
    onRemove: () -> Unit,
) {
    var selectedField by remember { mutableIntStateOf(0) }
    var fieldRevision by remember { mutableIntStateOf(0) }
    var confirmJson by remember { mutableStateOf(false) }
    val references = bindings.flatMap { listOfNotNull(it.read?.payload, it.write?.payload, it.notify?.payload, it.publish?.payload, it.subscribe?.payload) }
        .filter { it.formatId == format.id }
    val inUse = references.isNotEmpty()
    Panel {
        OutlinedTextField(format.label, { onChange(format.copy(label = it)) }, label = { Text(tr(AppStrings.format_name_30b666)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(format.id, { onChange(format.copy(id = it)) }, label = { Text(tr(AppStrings.format_id_ee5bf1)) },
            enabled = !inUse, isError = !payloadIdentifier.matches(format.id), singleLine = true,
            supportingText = { Text(if (inUse) tr(AppStrings.this_id_is_referenced_by_widgets_remove_the_references_44b5a2) else tr(AppStrings.start_with_a_letter_letters_digits_dots_underscores_and_4d04e9)) },
            modifier = Modifier.fillMaxWidth())
        Choices(listOf("choice:binary_78ff74", "choice:json_object_6b8049"), if (format.encoding == PayloadEncoding.BINARY) "choice:binary_78ff74" else "choice:json_object_6b8049") { selected ->
            if (selected == "choice:binary_78ff74") onChange(format.copy(encoding = PayloadEncoding.BINARY))
            else if (format.encoding != PayloadEncoding.JSON) {
                if (format.prefixHex.isNotEmpty() || format.suffixHex.isNotEmpty() || format.fields.any { !it.hasDefaultBinaryParameters() }) confirmJson = true
                else onChange(format.copy(encoding = PayloadEncoding.JSON))
            }
        }
        if (format.encoding == PayloadEncoding.BINARY) {
            Text(tr(AppStrings.payload_header_fields_in_list_order_trailer_reads_and_n_05e49b), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(format.prefixHex, { onChange(format.copy(prefixHex = it)) }, label = { Text(tr(AppStrings.header_hex_optional_99f396)) },
                isError = !validBindingHex(format.prefixHex, 0, 512), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(format.suffixHex, { onChange(format.copy(suffixHex = it)) }, label = { Text(tr(AppStrings.trailer_hex_optional_d81860)) },
                isError = !validBindingHex(format.suffixHex, 0, 512), singleLine = true, modifier = Modifier.fillMaxWidth())
        } else Text(tr(AppStrings.field_ids_become_json_keys_numbers_use_displayed_values_9d525b), color = Muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(tr(AppStrings.payload_fields_bff253), style = MaterialTheme.typography.titleMedium)
        format.fields.forEachIndexed { index, field ->
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { selectedField = index }, modifier = Modifier.weight(1f)) {
                    Text("${if (index == selectedField) "● " else ""}${index + 1}. ${field.id} · ${field.codec.name}")
                }
                TextButton(onClick = {
                    onChange(format.copy(fields = format.fields.toMutableList().apply { add(index - 1, removeAt(index)) }))
                    selectedField = index - 1
                    fieldRevision++
                }, enabled = index > 0) { Text(tr(AppStrings.move_up_8a0c83)) }
                TextButton(onClick = {
                    onChange(format.copy(fields = format.fields.toMutableList().apply { add(index + 1, removeAt(index)) }))
                    selectedField = index + 1
                    fieldRevision++
                }, enabled = index < format.fields.lastIndex) { Text(tr(AppStrings.move_down_05c46f)) }
            }
        }
        if (format.encoding == PayloadEncoding.BINARY) Text(tr(AppStrings.changing_field_order_changes_byte_positions_keep_it_con_9e0f66), color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            var suffix = 1
            while (format.fields.any { it.id == "field-$suffix" }) suffix++
            selectedField = format.fields.size
            fieldRevision++
            onChange(format.copy(fields = format.fields + PayloadField(id = "field-$suffix")))
        }, enabled = format.fields.size < 32) { Text(tr(AppStrings.add_field_1687c8)) }
        format.fields.getOrNull(selectedField)?.let { field ->
            val fieldUsed = references.any { it.fieldId == field.id }
            key(selectedField, fieldRevision, format.encoding) {
                PayloadFieldEditor(field, format.encoding, fieldUsed,
                    onChange = { changed -> onChange(format.copy(fields = format.fields.mapIndexed { index, current -> if (index == selectedField) changed else current })) },
                    onRemove = {
                        onChange(format.copy(fields = format.fields.filterIndexed { index, _ -> index != selectedField }))
                        selectedField = (selectedField - 1).coerceAtLeast(0)
                        fieldRevision++
                    },
                )
            }
        }
        TextButton(onClick = onRemove, enabled = !inUse) { Text(tr(AppStrings.delete_format_3a9450)) }
        if (inUse) Text(tr(AppStrings.this_format_is_used_by_operations_remove_their_referenc_87dc9e, references.size), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmJson) AlertDialog(onDismissRequest = { confirmJson = false }, title = { Text(tr(AppStrings.switch_to_json_df2b2c)) },
        text = { Text(tr(AppStrings.json_uses_field_names_and_direct_values_switching_clear_d21fe8)) },
        confirmButton = { TextButton(onClick = {
            onChange(format.copy(encoding = PayloadEncoding.JSON, prefixHex = "", suffixHex = "", fields = format.fields.map {
                it.copy(byteOrder = PanelByteOrder.LITTLE_ENDIAN, scale = 1.0, bias = 0.0, falseHex = "00", trueHex = "01")
            }))
            confirmJson = false
        }) { Text(tr(AppStrings.switch_format_96d553)) } },
        dismissButton = { TextButton(onClick = { confirmJson = false }) { Text(tr(AppStrings.cancel_4d0b46)) } },
    )
}

@Composable
private fun PayloadFieldEditor(
    field: PayloadField,
    encoding: PayloadEncoding,
    inUse: Boolean,
    onChange: (PayloadField) -> Unit,
    onRemove: () -> Unit,
) {
    HorizontalDivider()
    OutlinedTextField(field.id, { onChange(field.copy(id = it)) }, label = { Text(tr(AppStrings.field_id_204aa4)) },
        enabled = !inUse, isError = !payloadIdentifier.matches(field.id), singleLine = true,
        supportingText = { Text(if (inUse) tr(AppStrings.this_field_is_bound_to_a_widget_remove_references_befor_b7da21) else tr(AppStrings.json_uses_this_id_as_the_key_a9460a)) }, modifier = Modifier.fillMaxWidth())
    val types = numericPanelCodecs.toList() + CodecType.BOOLEAN
    PanelPicker(tr(AppStrings.field_data_type_632ef0), types.map { it.name to it.payloadTypeLabel() }, field.codec.name) { value ->
        val selected = CodecType.valueOf(value)
        onChange(field.copy(codec = selected,
            byteOrder = if (selected in setOf(CodecType.BOOLEAN, CodecType.UINT8, CodecType.INT8)) PanelByteOrder.LITTLE_ENDIAN else field.byteOrder,
            scale = if (selected == CodecType.BOOLEAN) 1.0 else field.scale,
            bias = if (selected == CodecType.BOOLEAN) 0.0 else field.bias,
            falseHex = if (selected == CodecType.BOOLEAN && field.codec == CodecType.BOOLEAN) field.falseHex else "00",
            trueHex = if (selected == CodecType.BOOLEAN && field.codec == CodecType.BOOLEAN) field.trueHex else "01"))
    }
    if (encoding == PayloadEncoding.BINARY) {
        if (field.codec in setOf(CodecType.UINT16, CodecType.INT16, CodecType.UINT32, CodecType.INT32, CodecType.FLOAT32)) {
            Choices(listOf("choice:little_endian_796b46", "choice:big_endian_57167f"), if (field.byteOrder == PanelByteOrder.LITTLE_ENDIAN) "choice:little_endian_796b46" else "choice:big_endian_57167f") {
                onChange(field.copy(byteOrder = if (it == "choice:little_endian_796b46") PanelByteOrder.LITTLE_ENDIAN else PanelByteOrder.BIG_ENDIAN))
            }
        }
        if (field.codec == CodecType.BOOLEAN) {
            OutlinedTextField(field.falseHex, { onChange(field.copy(falseHex = it)) }, label = { Text("False HEX") }, singleLine = true,
                isError = !validBindingHex(field.falseHex, 1, 32), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(field.trueHex, { onChange(field.copy(trueHex = it)) }, label = { Text("True HEX") }, singleLine = true,
                isError = !validBindingHex(field.trueHex, 1, 32), modifier = Modifier.fillMaxWidth())
            Text(tr(AppStrings.true_and_false_byte_sequences_must_have_equal_lengths_a_620492), color = Muted, style = MaterialTheme.typography.bodySmall)
        } else key(field.codec) {
            var scale by remember { mutableStateOf(field.scale.takeIf { it.isFinite() }?.toString().orEmpty()) }
            var bias by remember { mutableStateOf(field.bias.takeIf { it.isFinite() }?.toString().orEmpty()) }
            Text(tr(AppStrings.displayed_value_raw_value_scale_offset_writes_apply_the_8f9580), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(scale, {
                scale = it
                onChange(field.copy(scale = it.toDoubleOrNull() ?: Double.NaN))
            }, label = { Text(tr(AppStrings.scale_8482e1)) }, singleLine = true, isError = scale.toDoubleOrNull()?.let { it.isFinite() && it != 0.0 } != true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bias, {
                bias = it
                onChange(field.copy(bias = it.toDoubleOrNull() ?: Double.NaN))
            }, label = { Text(tr(AppStrings.offset_6f0fb1)) }, singleLine = true, isError = bias.toDoubleOrNull()?.isFinite() != true, modifier = Modifier.fillMaxWidth())
        }
    }
    OutlinedTextField(field.defaultValue.orEmpty(), { onChange(field.copy(defaultValue = it.takeIf(String::isNotBlank))) },
        label = { Text(tr(AppStrings.default_write_value_optional_8eb284)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(if (field.codec == CodecType.BOOLEAN) tr(AppStrings.enter_true_or_false_all_action_fields_and_other_fields_541fbc) else tr(AppStrings.enter_the_converted_value_all_action_fields_and_other_f_4bd7fe)) })
    TextButton(onClick = onRemove, enabled = !inUse) { Text(tr(AppStrings.delete_field_b89c9c)) }
}

private val payloadIdentifier = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
private fun PayloadField.hasDefaultBinaryParameters(): Boolean = byteOrder == PanelByteOrder.LITTLE_ENDIAN && scale == 1.0 && bias == 0.0 && falseHex == "00" && trueHex == "01"
private fun CodecType.payloadTypeLabel(): String = when (this) {
    CodecType.BOOLEAN -> tr(AppStrings.boolean_911f33)
    CodecType.FLOAT32 -> tr(AppStrings.float32_floating_point_297f86)
    else -> name
}
