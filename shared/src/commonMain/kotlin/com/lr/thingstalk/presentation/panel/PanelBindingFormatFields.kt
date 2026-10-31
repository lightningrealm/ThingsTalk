package com.lr.thingstalk.presentation.panel

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
        Text("字节序", color = Muted, style = MaterialTheme.typography.labelMedium)
        Choices(listOf("小端 · 低位在前", "大端 · 高位在前"),
            if (operation.byteOrder == PanelByteOrder.LITTLE_ENDIAN) "小端 · 低位在前" else "大端 · 高位在前") {
            onChange(operation.copy(byteOrder = if (it == "小端 · 低位在前") PanelByteOrder.LITTLE_ENDIAN else PanelByteOrder.BIG_ENDIAN))
        }
    }
    if (!isWrite) {
        OutlinedTextField(offset, {
            offset = it
            onChange(operation.copy(readOffset = it.toIntOrNull() ?: -1))
        }, label = { Text("$title · 字节偏移") },
            isError = offset.toIntOrNull()?.let { it < 0 } != false,
            supportingText = { Text("从 0 开始，跳过报文开头的字节，再按所选类型解析。报文不足时会报告错误。") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (operation.codec == CodecType.BOOLEAN) {
        val falseValue = operation.falseHex.filterNot { it.isWhitespace() }
        val trueValue = operation.trueHex.filterNot { it.isWhitespace() }
        val validPair = falseValue.length == trueValue.length && !falseValue.equals(trueValue, ignoreCase = true)
        OutlinedTextField(operation.falseHex, { onChange(operation.copy(falseHex = it)) },
            label = { Text("$title · 关闭 / False HEX") },
            isError = !validBindingHex(operation.falseHex, 1, 32) || !validPair,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(operation.trueHex, { onChange(operation.copy(trueHex = it)) },
            label = { Text("$title · 开启 / True HEX") },
            isError = !validBindingHex(operation.trueHex, 1, 32) || !validPair,
            supportingText = { Text("两组字节需等长且不同，每组 1–32 字节，例如关闭 00、开启 01。收到未知值时会报告解析错误。") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    } else {
        Text("显示数值 = 原始值 × 倍率 + 偏置", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(scale, {
            scale = it
            onChange(operation.copy(scale = it.toDoubleOrNull() ?: Double.NaN))
        }, label = { Text("$title · 倍率") },
            isError = scale.toDoubleOrNull()?.let { it.isFinite() && it != 0.0 } != true,
            supportingText = { Text("需要有限且非零的数值；例如原始值 235、倍率 0.1，显示 23.5。") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bias, {
            bias = it
            onChange(operation.copy(bias = it.toDoubleOrNull() ?: Double.NaN))
        }, label = { Text("$title · 偏置") },
            isError = bias.toDoubleOrNull()?.isFinite() != true,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (isWrite) Text("写入时逆向换算：原始值 =（输入数值 − 偏置）÷ 倍率。超出编码范围或无法精确表示的整数会被拒绝。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (isWrite) {
        TextButton(onClick = { showFrame = !showFrame }) { Text(if (showFrame) "收起写入帧格式" else "设置写入帧前后缀（可选）") }
        if (showFrame) {
            Text("写入报文 = 前缀 + 编码后的值 + 后缀", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(operation.writePrefixHex, { onChange(operation.copy(writePrefixHex = it)) },
                label = { Text("$title · HEX 前缀") },
                isError = !validBindingHex(operation.writePrefixHex, 0, 512),
                supportingText = { Text("例如 AA 01；可留空。") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(operation.writeSuffixHex, { onChange(operation.copy(writeSuffixHex = it)) },
                label = { Text("$title · HEX 后缀") },
                isError = !validBindingHex(operation.writeSuffixHex, 0, 512),
                supportingText = { Text("例如 FF；可留空。不会自动计算校验和，也不会拆包或重试。") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun Double.fieldText(): String = if (isFinite()) toString() else ""

internal fun validBindingHex(text: String, minBytes: Int, maxBytes: Int): Boolean {
    val compact = text.filterNot { it.isWhitespace() }
    return compact.length in (minBytes * 2)..(maxBytes * 2) && compact.length % 2 == 0 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
