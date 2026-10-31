package com.lr.thingstalk.presentation.panel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted
import kotlin.time.Instant

@Composable
fun ConfigurableDevicePage(
    viewModel: PanelViewModel,
    onEdit: () -> Unit,
    onDescription: (() -> Unit)? = null,
    transportContent: (@Composable () -> Unit)? = null,
    setupSession: (SetupPanel) -> SetupPanelSession? = { null },
) {
    val definition by remember(viewModel) { viewModel.displayDefinitions() }.collectAsState(viewModel.state.value.definition)
    Page("PERSONAL DEVICE PANEL", definition.title, "让设备，按你的方式呈现。") {
        transportContent?.invoke()
        ConfiguredSetupPanels(viewModel, setupSession)
        Section("控制面板")
        PanelHeader(viewModel, onEdit)
        if (definition.widgets.isEmpty()) Panel {
            Text("从空白开始", style = MaterialTheme.typography.titleLarge)
            Text("在开发者设置中添加组件，或导入设备的面板配置文件。", color = Muted)
            Primary("配置设备面板", action = onEdit)
        }
        definition.widgets.forEach { widget ->
            key(widget.id) {
                val capability = definition.capabilities.firstOrNull { it.id == widget.capabilityId }
                val binding = definition.bindings.firstOrNull { it.capabilityId == widget.capabilityId }
                if (capability != null) BoundPanelWidget(viewModel, widget, capability, binding)
            }
        }
        if (onDescription != null) Item("能力描述", "查看属性、动作与编解码配置") { onDescription() }
    }
}

@Composable
private fun PanelHeader(viewModel: PanelViewModel, onEdit: () -> Unit) {
    val state by remember(viewModel) { viewModel.chromeStates() }.collectAsState(viewModel.state.value.chrome())
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Tag(if (state.ready) "已连接" else "离线")
            TextButton(onClick = onEdit) { Text("开发者设置") }
        }
        Text("连接设备后即可查看状态和执行操作。", style = MaterialTheme.typography.bodySmall, color = Muted)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.feedback?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BoundPanelWidget(viewModel: PanelViewModel, widget: PanelWidget, capability: PanelCapability, binding: PanelBinding?) {
    val state by remember(viewModel, capability.id) { viewModel.widgetStates(capability.id) }
        .collectAsState(viewModel.state.value.widget(capability.id))
    PanelWidgetView(widget, capability, binding, state.value, state.updatedAt, state.notification,
        state.enabled, state.readEnabled, state.source, state.busy, state.message, state.error,
        onRead = { viewModel.read(capability.id) }, onWrite = { viewModel.write(capability.id, it) },
        onRetryNotifications = { viewModel.retryNotifications(capability.id) })
}

@Composable
private fun PanelWidgetView(
    widget: PanelWidget,
    capability: PanelCapability,
    binding: PanelBinding?,
    value: String?,
    valueUpdatedAt: Long?,
    notification: PanelNotificationState?,
    enabled: Boolean,
    readEnabled: Boolean,
    source: String?,
    busy: Boolean,
    message: String?,
    error: String?,
    onRead: () -> Unit,
    onWrite: (String) -> Unit,
    onRetryNotifications: () -> Unit,
) {
    Panel {
        source?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall) }
        when (widget.type) {
            WidgetType.VALUE -> {
                Text(widget.label, style = MaterialTheme.typography.titleMedium)
                Text(if (value == null) "—" else displayValue(value, capability), style = MaterialTheme.typography.headlineLarge)
            }
            WidgetType.SWITCH -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(widget.label, style = MaterialTheme.typography.titleMedium)
                        Text(value?.let { if (it == "true") "已开启" else "已关闭" } ?: "状态未知", color = Muted)
                    }
                    Switch(value == "true", { onWrite(it.toString()) }, enabled = enabled && binding?.canWrite == true, modifier = Modifier.semantics { contentDescription = widget.label })
                }
            }
            WidgetType.SLIDER -> PanelNumberSlider(widget.label, capability, value, enabled && binding?.canWrite == true, onWrite)
            WidgetType.NUMBER_INPUT -> PanelNumberInput(widget.label, capability, value, enabled && binding?.canWrite == true, onWrite)
            WidgetType.BUTTON -> Primary(widget.label, enabled && binding?.canWrite == true) { onWrite(binding?.actionValue.orEmpty()) }
        }
        // Reserve one local status line so short commands do not move the following cards.
        Text(if (busy) "正在执行 / 等待前一操作…" else error ?: message.orEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (error != null) MaterialTheme.colorScheme.error else Muted,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (binding == null) "尚未绑定操作" else if (binding.canObserve) notificationStatusLabel(notification?.status ?: PanelNotificationStatus.STOPPED)
                else if (!enabled) "当前不可操作" else capability.id, color = Muted, style = MaterialTheme.typography.labelSmall)
            if (binding?.canRead == true) TextButton(onClick = onRead, enabled = readEnabled) { Text("读取") }
        }
        valueUpdatedAt?.let {
            Text("最后有效数据 · ${Instant.fromEpochMilliseconds(it).toString().replace("T", " ").replace("Z", " UTC")}", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (binding?.canObserve == true && notification != null) {
            if (notification.invalidPayloadCount > 0) Text("本次连接已忽略 ${notification.invalidPayloadCount} 条无效报文", color = Muted, style = MaterialTheme.typography.labelSmall)
            notification.lastDecodeError?.let {
                Text("最近报文解析失败：$it。有效值保持不变。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            notification.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (notification.status in setOf(PanelNotificationStatus.FAILED, PanelNotificationStatus.STOPPED) && readEnabled) {
                OutlinedButton(onClick = onRetryNotifications, modifier = Modifier.semantics { contentDescription = "重新订阅 ${widget.label}" }) { Text("重新订阅") }
            }
        }
    }
}

private fun notificationStatusLabel(status: PanelNotificationStatus): String = when (status) {
    PanelNotificationStatus.STOPPED -> "通知已停止"
    PanelNotificationStatus.STARTING -> "正在订阅通知"
    PanelNotificationStatus.WAITING -> "已订阅 · 等待有效数据"
    PanelNotificationStatus.RECEIVING -> "通知接收中"
    PanelNotificationStatus.FAILED -> "通知订阅失败"
}

private fun displayValue(value: String, capability: PanelCapability): String =
    if (capability.type == CapabilityType.BOOLEAN) { if (value == "true") "开启" else "关闭" }
    else "${value.toDoubleOrNull()?.let(::panelNumberText) ?: value} ${capability.unit}".trim()

@Composable
private fun ConfiguredSetupPanels(viewModel: PanelViewModel, session: (SetupPanel) -> SetupPanelSession?) {
    val state by remember(viewModel) { viewModel.connectionStates() }.collectAsState(viewModel.state.value.connectionChrome())
    if (state.definition.transport == PanelTransport.DUAL && state.activeChannel != PanelTransport.BLE) return
    state.definition.setupPanels.forEach { definition ->
        key(definition.id) { SetupPanelView(definition, session(definition)) }
    }
}
