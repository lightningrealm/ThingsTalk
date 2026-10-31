package com.lr.thingstalk.presentation.panel

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
        Text("先定义报文，再为组件选择字段。一个报文可以同时提供温度、湿度等多个值。", color = Muted)
        Text("二进制字段按顺序紧密排列；JSON 使用平铺的数值或布尔字段。配置不会自动修改设备固件。", color = Muted, style = MaterialTheme.typography.bodySmall)
        formats.forEachIndexed { index, format ->
            TextButton(onClick = { selectedIndex = index }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (index == selectedIndex) "● " else ""}${format.label.ifBlank { "未命名格式" }} · ${format.id} · ${format.fields.size} 字段")
            }
        }
        OutlinedButton(onClick = {
            var suffix = 1
            while (formats.any { it.id == "format-$suffix" }) suffix++
            selectedIndex = formats.size
            formatRevision++
            onChange(formats + PayloadFormat(id = "format-$suffix", label = "数据格式 $suffix", fields = listOf(PayloadField(id = "value"))))
        }, enabled = formats.size < 32, modifier = Modifier.fillMaxWidth()) { Text("新增数据格式") }
        if (formats.isEmpty()) Text("也可继续使用组件内的单值编码。需要共享报文结构时再添加格式。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
        OutlinedTextField(format.label, { onChange(format.copy(label = it)) }, label = { Text("格式名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(format.id, { onChange(format.copy(id = it)) }, label = { Text("格式 ID") },
            enabled = !inUse, isError = !payloadIdentifier.matches(format.id), singleLine = true,
            supportingText = { Text(if (inUse) "此 ID 已被组件引用。先解除相关绑定，才能更改 ID 或删除格式。" else "字母开头，可含字母、数字、点、下划线和连字符。") },
            modifier = Modifier.fillMaxWidth())
        Choices(listOf("二进制", "JSON 对象"), if (format.encoding == PayloadEncoding.BINARY) "二进制" else "JSON 对象") { selected ->
            if (selected == "二进制") onChange(format.copy(encoding = PayloadEncoding.BINARY))
            else if (format.encoding != PayloadEncoding.JSON) {
                if (format.prefixHex.isNotEmpty() || format.suffixHex.isNotEmpty() || format.fields.any { !it.hasDefaultBinaryParameters() }) confirmJson = true
                else onChange(format.copy(encoding = PayloadEncoding.JSON))
            }
        }
        if (format.encoding == PayloadEncoding.BINARY) {
            Text("报文 = 帧头 + 按列表顺序编码的字段 + 帧尾。读取和通知会校验帧头、帧尾与完整长度。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(format.prefixHex, { onChange(format.copy(prefixHex = it)) }, label = { Text("帧头 HEX（可留空）") },
                isError = !validBindingHex(format.prefixHex, 0, 512), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(format.suffixHex, { onChange(format.copy(suffixHex = it)) }, label = { Text("帧尾 HEX（可留空）") },
                isError = !validBindingHex(format.suffixHex, 0, 512), singleLine = true, modifier = Modifier.fillMaxWidth())
        } else Text("字段 ID 对应 JSON 的键。数值直接使用面板显示值，布尔使用 true / false；数值类型仍限制可表示范围。", color = Muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("报文字段", style = MaterialTheme.typography.titleMedium)
        format.fields.forEachIndexed { index, field ->
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { selectedField = index }, modifier = Modifier.weight(1f)) {
                    Text("${if (index == selectedField) "● " else ""}${index + 1}. ${field.id} · ${field.codec.name}")
                }
                TextButton(onClick = {
                    onChange(format.copy(fields = format.fields.toMutableList().apply { add(index - 1, removeAt(index)) }))
                    selectedField = index - 1
                    fieldRevision++
                }, enabled = index > 0) { Text("上移") }
                TextButton(onClick = {
                    onChange(format.copy(fields = format.fields.toMutableList().apply { add(index + 1, removeAt(index)) }))
                    selectedField = index + 1
                    fieldRevision++
                }, enabled = index < format.fields.lastIndex) { Text("下移") }
            }
        }
        if (format.encoding == PayloadEncoding.BINARY) Text("调整字段顺序会改变报文中的字节位置，请与设备协议保持一致。", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            var suffix = 1
            while (format.fields.any { it.id == "field-$suffix" }) suffix++
            selectedField = format.fields.size
            fieldRevision++
            onChange(format.copy(fields = format.fields + PayloadField(id = "field-$suffix")))
        }, enabled = format.fields.size < 32) { Text("新增字段") }
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
        TextButton(onClick = onRemove, enabled = !inUse) { Text("删除此格式") }
        if (inUse) Text("此格式用于 ${references.size} 项操作，删除前请解除引用。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmJson) AlertDialog(onDismissRequest = { confirmJson = false }, title = { Text("改为 JSON 格式？") },
        text = { Text("JSON 使用字段名和直接数值。切换会清空帧头、帧尾，重置字节序、倍率、偏置和真假字节编码；字段、类型和默认值保留。") },
        confirmButton = { TextButton(onClick = {
            onChange(format.copy(encoding = PayloadEncoding.JSON, prefixHex = "", suffixHex = "", fields = format.fields.map {
                it.copy(byteOrder = PanelByteOrder.LITTLE_ENDIAN, scale = 1.0, bias = 0.0, falseHex = "00", trueHex = "01")
            }))
            confirmJson = false
        }) { Text("切换格式") } },
        dismissButton = { TextButton(onClick = { confirmJson = false }) { Text("取消") } },
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
    OutlinedTextField(field.id, { onChange(field.copy(id = it)) }, label = { Text("字段 ID") },
        enabled = !inUse, isError = !payloadIdentifier.matches(field.id), singleLine = true,
        supportingText = { Text(if (inUse) "此字段已绑定组件；解除引用后可以更名或删除。" else "JSON 格式使用此 ID 作为键。") }, modifier = Modifier.fillMaxWidth())
    val types = numericPanelCodecs.toList() + CodecType.BOOLEAN
    PanelPicker("字段数据类型", types.map { it.name to it.payloadTypeLabel() }, field.codec.name) { value ->
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
            Choices(listOf("小端", "大端"), if (field.byteOrder == PanelByteOrder.LITTLE_ENDIAN) "小端" else "大端") {
                onChange(field.copy(byteOrder = if (it == "小端") PanelByteOrder.LITTLE_ENDIAN else PanelByteOrder.BIG_ENDIAN))
            }
        }
        if (field.codec == CodecType.BOOLEAN) {
            OutlinedTextField(field.falseHex, { onChange(field.copy(falseHex = it)) }, label = { Text("False HEX") }, singleLine = true,
                isError = !validBindingHex(field.falseHex, 1, 32), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(field.trueHex, { onChange(field.copy(trueHex = it)) }, label = { Text("True HEX") }, singleLine = true,
                isError = !validBindingHex(field.trueHex, 1, 32), modifier = Modifier.fillMaxWidth())
            Text("真假字节必须等长且不同。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else key(field.codec) {
            var scale by remember { mutableStateOf(field.scale.takeIf { it.isFinite() }?.toString().orEmpty()) }
            var bias by remember { mutableStateOf(field.bias.takeIf { it.isFinite() }?.toString().orEmpty()) }
            Text("显示值 = 原始值 × 倍率 + 偏置；写入时自动逆向换算。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(scale, {
                scale = it
                onChange(field.copy(scale = it.toDoubleOrNull() ?: Double.NaN))
            }, label = { Text("倍率") }, singleLine = true, isError = scale.toDoubleOrNull()?.let { it.isFinite() && it != 0.0 } != true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bias, {
                bias = it
                onChange(field.copy(bias = it.toDoubleOrNull() ?: Double.NaN))
            }, label = { Text("偏置") }, singleLine = true, isError = bias.toDoubleOrNull()?.isFinite() != true, modifier = Modifier.fillMaxWidth())
        }
    }
    OutlinedTextField(field.defaultValue.orEmpty(), { onChange(field.copy(defaultValue = it.takeIf(String::isNotBlank))) },
        label = { Text("写入默认值（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(if (field.codec == CodecType.BOOLEAN) "填写 true 或 false。动作报文的所有字段、属性写入的其他字段都需要默认值。" else "填写换算后的数值。动作报文的所有字段、属性写入的其他字段都需要默认值。") })
    TextButton(onClick = onRemove, enabled = !inUse) { Text("删除此字段") }
}

private val payloadIdentifier = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
private fun PayloadField.hasDefaultBinaryParameters(): Boolean = byteOrder == PanelByteOrder.LITTLE_ENDIAN && scale == 1.0 && bias == 0.0 && falseHex == "00" && trueHex == "01"
private fun CodecType.payloadTypeLabel(): String = when (this) {
    CodecType.BOOLEAN -> "Boolean · 布尔"
    CodecType.FLOAT32 -> "Float32 · 浮点数"
    else -> name
}
