package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.Choices
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun PanelBindingFormatFields(
    title: String,
    operation: BleOperationBinding,
    isWrite: Boolean,
    onChange: (BleOperationBinding) -> Unit,
) {
    if (operation.codec == CodecType.HEX) return
    // Invalid text stays in the field while its invalid sentinel remains in the draft.
    // The parent keys this editor on JSON import so externally loaded values replace local text.
    var offset by remember { mutableStateOf(if (operation.readOffset >= 0) operation.readOffset.toString() else "") }
    var scale by remember { mutableStateOf(operation.scale.fieldText()) }
    var bias by remember { mutableStateOf(operation.bias.fieldText()) }
    var showFrame by remember { mutableStateOf(operation.writePrefixHex.isNotEmpty() || operation.writeSuffixHex.isNotEmpty()) }

    if (operation.codec in setOf(CodecType.UINT16, CodecType.INT16, CodecType.UINT32, CodecType.INT32, CodecType.FLOAT32)) {
        Text(tr(AppStrings.byte_order_a15ac8), color = Muted, style = MaterialTheme.typography.labelMedium)
        Choices(listOf("choice:little_endian_least_significant_byte_first_b5faae", "choice:big_endian_most_significant_byte_first_5dac30"),
            if (operation.byteOrder == PanelByteOrder.LITTLE_ENDIAN) "choice:little_endian_least_significant_byte_first_b5faae" else "choice:big_endian_most_significant_byte_first_5dac30") {
            onChange(operation.copy(byteOrder = if (it == "choice:little_endian_least_significant_byte_first_b5faae") PanelByteOrder.LITTLE_ENDIAN else PanelByteOrder.BIG_ENDIAN))
        }
    }
    if (!isWrite) {
        OutlinedTextField(offset, {
            offset = it
            onChange(operation.copy(readOffset = it.toIntOrNull() ?: -1))
        }, label = { Text(tr(AppStrings.byte_offset_6cefa1, title)) },
            isError = offset.toIntOrNull()?.let { it < 0 } != false,
            supportingText = { Text(tr(AppStrings.zero_based_skip_this_many_bytes_then_decode_the_selecte_cdc097)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (operation.codec == CodecType.BOOLEAN) {
        val falseValue = operation.falseHex.filterNot { it.isWhitespace() }
        val trueValue = operation.trueHex.filterNot { it.isWhitespace() }
        val validPair = falseValue.length == trueValue.length && !falseValue.equals(trueValue, ignoreCase = true)
        OutlinedTextField(operation.falseHex, { onChange(operation.copy(falseHex = it)) },
            label = { Text(tr(AppStrings.off_false_hex_cd513f, title)) },
            isError = !validBindingHex(operation.falseHex, 1, 32) || !validPair,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(operation.trueHex, { onChange(operation.copy(trueHex = it)) },
            label = { Text(tr(AppStrings.on_true_hex_ccdd5e, title)) },
            isError = !validBindingHex(operation.trueHex, 1, 32) || !validPair,
            supportingText = { Text(tr(AppStrings.use_different_equal_length_byte_sequences_of_1_32_bytes_891a10)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    } else if (operation.codec != CodecType.UTF8) {
        Text(tr(AppStrings.displayed_value_raw_value_scale_offset_9561a2), color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(scale, {
            scale = it
            onChange(operation.copy(scale = it.toDoubleOrNull() ?: Double.NaN))
        }, label = { Text(tr(AppStrings.scale_364db6, title)) },
            isError = scale.toDoubleOrNull()?.let { it.isFinite() && it != 0.0 } != true,
            supportingText = { Text(tr(AppStrings.use_a_finite_nonzero_number_for_example_raw_235_with_sc_f297dd)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bias, {
            bias = it
            onChange(operation.copy(bias = it.toDoubleOrNull() ?: Double.NaN))
        }, label = { Text(tr(AppStrings.offset_968ddf, title)) },
            isError = bias.toDoubleOrNull()?.isFinite() != true,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (isWrite) Text(tr(AppStrings.writes_use_raw_input_offset_scale_out_of_range_values_a_28eb00),
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (isWrite) {
        TextButton(onClick = { showFrame = !showFrame }) { Text(if (showFrame) tr(AppStrings.hide_write_framing_5b52a8) else tr(AppStrings.set_write_prefix_and_suffix_optional_b32a62)) }
        if (showFrame) {
            Text(tr(AppStrings.write_payload_prefix_encoded_value_suffix_429636), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(operation.writePrefixHex, { onChange(operation.copy(writePrefixHex = it)) },
                label = { Text(tr(AppStrings.hex_prefix_77f49e, title)) },
                isError = !validBindingHex(operation.writePrefixHex, 0, 512),
                supportingText = { Text(tr(AppStrings.for_example_aa_01_optional_8e64d2)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(operation.writeSuffixHex, { onChange(operation.copy(writeSuffixHex = it)) },
                label = { Text(tr(AppStrings.hex_suffix_dba84d, title)) },
                isError = !validBindingHex(operation.writeSuffixHex, 0, 512),
                supportingText = { Text(tr(AppStrings.for_example_ff_optional_no_automatic_checksum_chunking_ef2638)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun Double.fieldText(): String = if (isFinite()) toString() else ""

internal fun validBindingHex(text: String, minBytes: Int, maxBytes: Int): Boolean {
    val compact = text.filterNot { it.isWhitespace() }
    return compact.length in (minBytes * 2)..(maxBytes * 2) && compact.length % 2 == 0 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
