package com.lr.thingstalk.presentation.panel

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
    Section("协议试算器", if (expanded) "收起" else "展开") { expanded = !expanded }
    if (!expanded) return
    Panel {
        Tag("离线 · 不发送")
        Text("按当前草稿解析报文或预览发送字节，不需要连接设备。", color = Muted)
        if (definition.payloadFormats.isEmpty()) {
            Text("先在上方新增数据格式，再进行试算。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            val index = selectedIndex.coerceIn(0, definition.payloadFormats.lastIndex)
            val format = definition.payloadFormats[index]
            PanelPicker("试算格式", definition.payloadFormats.mapIndexed { i, item -> i.toString() to "${item.label.ifBlank { "未命名格式" }} · ${item.id} · ${item.encoding}" }, index.toString()) {
                selectedIndex = it.toInt()
            }
            // Editing the schema invalidates both inputs and results, including offsets/defaults.
            key(index, format) { PayloadInspectorContent(format, inspector) }
        }
    }
}

@Composable
private fun PayloadInspectorContent(format: PayloadFormat, inspector: PayloadInspector) {
    var mode by remember { mutableStateOf("解析报文") }
    var input by remember { mutableStateOf(if (format.encoding == PayloadEncoding.JSON) PayloadInputEncoding.JSON_TEXT else PayloadInputEncoding.HEX) }
    var source by remember { mutableStateOf("") }
    var values by remember { mutableStateOf(format.fields.associate { it.id to it.defaultValue.orEmpty() }) }
    var result by remember { mutableStateOf<PayloadInspection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val formatErrors = remember(format) { validatePayloadFormats(listOf(format)) }
    fun changed() { result = null; error = null }
    Choices(listOf("解析报文", "生成报文"), mode) {
        if (it != mode) { mode = it; changed() }
    }
    if (formatErrors.isNotEmpty()) {
        Text("请先修正数据格式：", color = MaterialTheme.colorScheme.error)
        formatErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        return
    }
    if (mode == "解析报文") {
        if (format.encoding == PayloadEncoding.JSON) {
            Choices(listOf("JSON 文本", "HEX"), if (input == PayloadInputEncoding.JSON_TEXT) "JSON 文本" else "HEX") {
                val selected = if (it == "HEX") PayloadInputEncoding.HEX else PayloadInputEncoding.JSON_TEXT
                if (selected != input) { input = selected; source = ""; changed() }
            }
        }
        OutlinedTextField(source, {
            changed()
            if (it.length <= MAX_INSPECTOR_SOURCE_CHARS) source = it
            else error = "输入不能超过 $MAX_INSPECTOR_SOURCE_CHARS 个字符"
        }, label = { Text(if (input == PayloadInputEncoding.HEX) "完整报文 HEX" else "完整 JSON 报文") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp), minLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Text(if (input == PayloadInputEncoding.HEX) "完整字节，可用空格或换行分隔。最大 512 字节。" else "JSON 须包含全部声明字段，使用数值或布尔值。UTF-8 编码后最大 512 字节。", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            result = null; error = null
            try { result = inspector.decode(format, source, input) }
            catch (failure: IllegalArgumentException) { error = failure.message ?: "无法解析报文" }
        }, enabled = source.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("解析全部字段") }
    } else {
        Text("填写每个字段的工程值，已有默认值会预填。此处试算不会修改格式的默认值。", color = Muted, style = MaterialTheme.typography.bodySmall)
        format.fields.forEach { field ->
            if (field.codec == CodecType.BOOLEAN) {
                Text("${field.id} · BOOLEAN", style = MaterialTheme.typography.labelLarge)
                Choices(listOf("true", "false"), values[field.id].orEmpty()) {
                    values = values + (field.id to it); changed()
                }
                if (values[field.id].isNullOrEmpty()) Text("请选择布尔值", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(values[field.id].orEmpty(), {
                    changed()
                    if (it.length <= MAX_INSPECTOR_VALUE_CHARS) values = values + (field.id to it)
                    else error = "字段值不能超过 $MAX_INSPECTOR_VALUE_CHARS 个字符"
                }, label = { Text("${field.id} · ${field.codec}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedButton(onClick = {
            result = null; error = null
            try { result = inspector.encode(format, values) }
            catch (failure: IllegalArgumentException) { error = failure.message ?: "无法生成报文" }
        }, modifier = Modifier.fillMaxWidth()) { Text("预览完整报文") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    result?.let { inspection ->
        HorizontalDivider()
        Text(if (mode == "解析报文") "解析结果 · ${inspection.byteCount} 字节" else "待发报文预览 · ${inspection.byteCount} 字节", style = MaterialTheme.typography.titleMedium)
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
                    if (field.byteOffset != null) append(" · 偏移 ${field.byteOffset} · ${field.byteCount} 字节")
                    else append(" · JSON 键 ${field.id}")
                }, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text((if (format.encoding == PayloadEncoding.BINARY) "偏移从 0 开始并包含帧头。" else "JSON 字段按键读取，没有固定字节偏移。") +
            "预览只校验报文字段；组件范围和设备实际写入长度会在执行操作时另行检查。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
