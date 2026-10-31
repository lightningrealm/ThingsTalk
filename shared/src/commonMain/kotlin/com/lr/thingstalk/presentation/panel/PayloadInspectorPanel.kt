package com.lr.thingstalk.presentation.panel

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

/** An editor tool only: results never dispatch a device operation or change the panel draft. */
@Composable
fun PayloadInspectorPanel(definition: PanelDefinition, inspector: PayloadInspector) {
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    Section(tr(AppStrings.payload_inspector_2ffae9), if (expanded) tr(AppStrings.collapse_5d5815) else tr(AppStrings.expand_b0e248)) { expanded = !expanded }
    if (!expanded) return
    Panel {
        Tag(tr(AppStrings.offline_nothing_is_sent_14b074))
        Text(tr(AppStrings.decode_or_preview_bytes_using_the_current_draft_no_conn_222ca5), color = Muted)
        if (definition.payloadFormats.isEmpty()) {
            Text(tr(AppStrings.add_a_payload_format_above_to_start_4ff583), color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            val index = selectedIndex.coerceIn(0, definition.payloadFormats.lastIndex)
            val format = definition.payloadFormats[index]
            PanelPicker(tr(AppStrings.inspection_format_e32327), definition.payloadFormats.mapIndexed { i, item -> i.toString() to "${item.label.ifBlank { tr(AppStrings.unnamed_format_1fe1ee) }} · ${item.id} · ${item.encoding}" }, index.toString()) {
                selectedIndex = it.toInt()
            }
            // Editing the schema invalidates both inputs and results, including offsets/defaults.
            key(index, format) { PayloadInspectorContent(format, inspector) }
        }
    }
}

@Composable
private fun PayloadInspectorContent(format: PayloadFormat, inspector: PayloadInspector) {
    var mode by remember { mutableStateOf("choice:decode_payload_fdb236") }
    var input by remember { mutableStateOf(if (format.encoding == PayloadEncoding.JSON) PayloadInputEncoding.JSON_TEXT else PayloadInputEncoding.HEX) }
    var source by remember { mutableStateOf("") }
    var values by remember { mutableStateOf(format.fields.associate { it.id to it.defaultValue.orEmpty() }) }
    var result by remember { mutableStateOf<PayloadInspection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val formatErrors = remember(format) { validatePayloadFormats(listOf(format)) }
    fun changed() { result = null; error = null }
    Choices(listOf("choice:decode_payload_fdb236", "choice:encode_payload_b6dab1"), mode) {
        if (it != mode) { mode = it; changed() }
    }
    if (formatErrors.isNotEmpty()) {
        Text(tr(AppStrings.fix_the_payload_format_first_d409f5), color = MaterialTheme.colorScheme.error)
        formatErrors.forEach { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        return
    }
    if (mode == "choice:decode_payload_fdb236") {
        if (format.encoding == PayloadEncoding.JSON) {
            Choices(listOf("choice:json_text_fac550", "HEX"), if (input == PayloadInputEncoding.JSON_TEXT) "choice:json_text_fac550" else "HEX") {
                val selected = if (it == "HEX") PayloadInputEncoding.HEX else PayloadInputEncoding.JSON_TEXT
                if (selected != input) { input = selected; source = ""; changed() }
            }
        }
        OutlinedTextField(source, {
            changed()
            if (it.length <= MAX_INSPECTOR_SOURCE_CHARS) source = it
            else error = tr(AppStrings.input_must_not_exceed_characters_1972f0, MAX_INSPECTOR_SOURCE_CHARS)
        }, label = { Text(if (input == PayloadInputEncoding.HEX) tr(AppStrings.complete_payload_hex_4eb3d2) else tr(AppStrings.complete_json_payload_1ff64a)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp), minLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Text(if (input == PayloadInputEncoding.HEX) tr(AppStrings.all_bytes_separated_by_spaces_or_line_breaks_maximum_51_7dcf66) else tr(AppStrings.json_must_include_all_declared_fields_as_numbers_or_boo_455bfe), color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            result = null; error = null
            try { result = inspector.decode(format, source, input) }
            catch (failure: IllegalArgumentException) { error = failure.message ?: tr(AppStrings.could_not_decode_payload_c0e662) }
        }, enabled = source.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.decode_all_fields_925f6b)) }
    } else {
        Text(tr(AppStrings.enter_engineering_values_for_each_field_defaults_are_pr_50e14e), color = Muted, style = MaterialTheme.typography.bodySmall)
        format.fields.forEach { field ->
            if (field.codec == CodecType.BOOLEAN) {
                Text("${field.id} · BOOLEAN", style = MaterialTheme.typography.labelLarge)
                Choices(listOf("true", "false"), values[field.id].orEmpty()) {
                    values = values + (field.id to it); changed()
                }
                if (values[field.id].isNullOrEmpty()) Text(tr(AppStrings.select_a_boolean_value_2c4b5b), color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(values[field.id].orEmpty(), {
                    changed()
                    if (it.length <= MAX_INSPECTOR_VALUE_CHARS) values = values + (field.id to it)
                    else error = tr(AppStrings.field_value_must_not_exceed_characters_3a0501, MAX_INSPECTOR_VALUE_CHARS)
                }, label = { Text("${field.id} · ${field.codec}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedButton(onClick = {
            result = null; error = null
            try { result = inspector.encode(format, values) }
            catch (failure: IllegalArgumentException) { error = failure.message ?: tr(AppStrings.could_not_encode_payload_f313b9) }
        }, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.preview_complete_payload_bb00f5)) }
    }
    error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    result?.let { inspection ->
        HorizontalDivider()
        Text(if (mode == "choice:decode_payload_fdb236") tr(AppStrings.decoded_result_bytes_44a2d6, inspection.byteCount) else tr(AppStrings.outgoing_payload_preview_bytes_99b396, inspection.byteCount), style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(inspection.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        inspection.jsonText?.let { text ->
            Text("UTF-8 JSON", color = Muted, style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        inspection.fields.forEach { field ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyValue(field.id, field.value)
                Text(buildString {
                    append(field.codec.name)
                    if (field.byteOffset != null) append(tr(AppStrings.offset_bytes_8ecb41, field.byteOffset, field.byteCount))
                    else append(tr(AppStrings.json_key_9f7a8a, field.id))
                }, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text((if (format.encoding == PayloadEncoding.BINARY) tr(AppStrings.offsets_are_zero_based_and_include_the_header_5f32ea) else tr(AppStrings.json_fields_are_accessed_by_key_and_have_no_fixed_byte_1d7139)) +
            tr(AppStrings.preview_validates_payload_fields_only_widget_ranges_and_f217d3), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
