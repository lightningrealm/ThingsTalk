package com.lr.thingstalk.presentation.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lr.thingstalk.domain.panel.PanelCapability
import com.lr.thingstalk.domain.panel.PanelDefinition
import com.lr.thingstalk.domain.panel.PanelWriteMode
import com.lr.thingstalk.presentation.components.Primary
import com.lr.thingstalk.presentation.theme.Muted
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/** Normalize only display-level binary arithmetic noise; transport conversion still validates the value. */
internal fun panelNumberText(value: Double): String {
    if (!value.isFinite() || value == 0.0) return value.toString().removeSuffix(".0")
    val magnitude = floor(log10(abs(value)))
    for (digits in 1..16) {
        val factor = 10.0.pow(digits - 1 - magnitude)
        if (!factor.isFinite() || !(value * factor).isFinite() || factor == 0.0) continue
        val candidate = round(value * factor) / factor
        if (abs(candidate - value) <= abs(value) * 8.881784197001252e-16) return candidate.toString().removeSuffix(".0")
    }
    return value.toString().removeSuffix(".0")
}

/** Older editors stored an unused write mode on reads/notifications; it must not block v2 editing. */
internal fun editablePanelDefinition(definition: PanelDefinition): PanelDefinition = definition.copy(
    schemaVersion = maxOf(2, definition.schemaVersion),
    bindings = definition.bindings.map { binding -> binding.copy(
        read = binding.read?.copy(writeMode = PanelWriteMode.WITH_RESPONSE),
        notify = binding.notify?.copy(writeMode = PanelWriteMode.WITH_RESPONSE),
    ) },
)

/** The slider transports only a normalized position; the actual value retains Double precision. */
internal fun panelSliderValue(capability: PanelCapability, fraction: Float): Double {
    val minimum = capability.minimum ?: 0.0
    val maximum = capability.maximum ?: 255.0
    val count = round((maximum - minimum) / capability.step)
    val index = round(fraction.coerceIn(0f, 1f).toDouble() * count)
    val raw = minimum + index * capability.step
    // Remove binary arithmetic tails using the declared decimal grid, without rounding to integers.
    fun decimals(number: Double): Int {
        val parts = number.toString().lowercase().split('e')
        val fractionDigits = parts[0].substringAfter('.', "").length
        return (fractionDigits - (parts.getOrNull(1)?.toIntOrNull() ?: 0)).coerceAtLeast(0)
    }
    val factor = 10.0.pow(maxOf(decimals(minimum), decimals(capability.step)))
    val rounded = if (factor.isFinite() && (raw * factor).isFinite()) round(raw * factor) / factor else raw
    return rounded.coerceIn(minimum, maximum)
}

@Composable
internal fun PanelNumberSlider(label: String, capability: PanelCapability, value: String?, enabled: Boolean, onWrite: (String) -> Unit) {
    val minimum = capability.minimum ?: 0.0
    val maximum = capability.maximum ?: 255.0
    val actual = value?.toDoubleOrNull()
    var position by remember(capability) { mutableFloatStateOf(
        actual?.let { ((it - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat() } ?: 0f,
    ) }
    var edited by remember(capability) { mutableStateOf(false) }
    fun useDeviceValue() {
        position = actual?.let { ((it - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat() } ?: 0f
        edited = false
    }
    LaunchedEffect(value, capability) { if (!edited) useDeviceValue() }
    val selected = panelSliderValue(capability, position)
    Text(label, style = MaterialTheme.typography.titleMedium)
    Text(when { edited -> "${panelNumberText(selected)} ${capability.unit}"; actual != null -> "${panelNumberText(actual)} ${capability.unit}"; else -> "—" }, style = MaterialTheme.typography.headlineLarge)
    Slider(position, { position = it; edited = true }, enabled = enabled, valueRange = 0f..1f,
        modifier = Modifier.semantics { contentDescription = label },
        onValueChangeFinished = { onWrite(panelNumberText(panelSliderValue(capability, position))) })
    Text("${panelNumberText(minimum)}–${panelNumberText(maximum)} · 步长 ${panelNumberText(capability.step)}", color = Muted, style = MaterialTheme.typography.labelSmall)
    if (edited) Text("当前为设置值；设备回值：${value ?: "未知"}。", color = Muted, style = MaterialTheme.typography.bodySmall)
    else if (value == null) Text("设备状态未知，拖动后提交设置值。", color = Muted, style = MaterialTheme.typography.bodySmall)
    if (edited && actual != null) TextButton(onClick = { useDeviceValue() }) { Text("使用设备回值") }
}

@Composable
internal fun PanelNumberInput(label: String, capability: PanelCapability, value: String?, enabled: Boolean, onWrite: (String) -> Unit) {
    var text by remember(capability) { mutableStateOf(value.orEmpty()) }
    var edited by remember(capability) { mutableStateOf(false) }
    LaunchedEffect(value, capability) { if (!edited) text = value.orEmpty() }
    val minimum = capability.minimum ?: 0.0
    val maximum = capability.maximum ?: 255.0
    val parsed = text.toDoubleOrNull()
    val valid = parsed != null && parsed.isFinite() && parsed in minimum..maximum && text == text.trim()
    Text(label, style = MaterialTheme.typography.titleMedium)
    Text("设备回值：${value ?: "未知"} ${capability.unit}", color = Muted)
    OutlinedTextField(text, { text = it; edited = true }, label = { Text("设置值") }, singleLine = true,
        enabled = enabled, isError = text.isNotEmpty() && !valid, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("允许范围 ${panelNumberText(minimum)}–${panelNumberText(maximum)} ${capability.unit}") })
    Primary("写入设置值", enabled && valid) { onWrite(text) }
    if (edited && value != null) TextButton(onClick = { text = value; edited = false }) { Text("使用设备回值") }
    Text("提交后等待读取或通知确认。整数编码拒绝非整数原始值；FLOAT32 按 32 位浮点精度编码。", color = Muted, style = MaterialTheme.typography.bodySmall)
}
