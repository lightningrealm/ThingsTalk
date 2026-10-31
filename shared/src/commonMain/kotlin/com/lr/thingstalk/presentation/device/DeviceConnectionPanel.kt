package com.lr.thingstalk.presentation.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.device.UserDevice
import com.lr.thingstalk.domain.panel.PanelTransport
import com.lr.thingstalk.platformCapabilities
import com.lr.thingstalk.presentation.ble.rememberBleScanAccess
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.panel.PanelViewModel
import com.lr.thingstalk.presentation.panel.connectionStates
import com.lr.thingstalk.presentation.panel.connectionChrome
import com.lr.thingstalk.presentation.theme.Muted

@Composable
fun DeviceConnectionPanel(
    device: UserDevice, panel: PanelViewModel, workspace: DeviceWorkspaceViewModel,
    onScan: () -> Unit, onDebug: () -> Unit, onLogs: () -> Unit,
    showDeveloperTools: Boolean = true,
    onMqttProfiles: () -> Unit = {},
) {
    val dual = device.panel.transport == PanelTransport.DUAL
    if (platformCapabilities().ble) {
        val state by remember(panel) { panel.connectionStates() }.collectAsState(panel.state.value.connectionChrome())
        val workspaceStatus by workspace.status.collectAsState()
        var settingsTab by rememberSaveable(device.id) { mutableStateOf(
            when {
                device.panel.transport == PanelTransport.MQTT -> PanelTransport.MQTT
                dual && device.bleTarget == null && device.panel.bindings.any { it.publish != null || it.subscribe != null } -> PanelTransport.MQTT
                else -> PanelTransport.BLE
            }
        ) }
        val channelPanel = state.definition.schemaVersion >= 6 && dual
        val selected = if (channelPanel) state.activeChannel ?: requireNotNull(state.definition.defaultChannel) else settingsTab
        fun enabled(channel: PanelTransport) = dual || device.panel.transport == channel
        fun status(channel: PanelTransport) = when {
            !enabled(channel) -> "未启用"
            channel in state.connectingTransports || (!dual && state.busy && !state.ready) -> "连接中"
            channel in state.connectedTransports || (!dual && state.ready) -> "已连接"
            else -> "未连接"
        }
        TransportTabs(selected, { if (channelPanel) panel.selectChannel(it) else settingsTab = it }, status(PanelTransport.BLE), status(PanelTransport.MQTT))
        Text(if (channelPanel) "${selected.name} 通道 · 下方组件使用此通道的绑定" else "连接设置 · 组件通信通道由面板配置决定", color = Muted, style = MaterialTheme.typography.bodySmall)
        @Composable fun inactive(channel: PanelTransport) {
            Panel {
                Text("尚未启用 ${channel.name}", style = MaterialTheme.typography.titleMedium)
                Text("启用后，此设备可同时配置 BLE 与 MQTT，现有组件与绑定会保留。升级会断开当前连接，完成后请重新连接。", color = Muted)
                Primary("启用 ${channel.name} · 升级为双连接", !workspaceStatus.busy) { workspace.promoteToDual(device.id) }
                workspaceStatus.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        TransportTabContent(selected,
            ble = {
                if (enabled(PanelTransport.BLE)) BleDeviceConnectionPanel(device, panel, workspace, onScan, onDebug, onLogs, showDeveloperTools)
                else inactive(PanelTransport.BLE)
            },
            mqtt = {
                if (enabled(PanelTransport.MQTT)) MqttDeviceConnectionPanel(device, panel, workspace, onMqttProfiles)
                else inactive(PanelTransport.MQTT)
            })
    } else {
        MqttDeviceConnectionPanel(device, panel, workspace, onMqttProfiles)
    }
}

@Composable
private fun BleDeviceConnectionPanel(
    device: UserDevice, panel: PanelViewModel, workspace: DeviceWorkspaceViewModel,
    onScan: () -> Unit, onDebug: () -> Unit, onLogs: () -> Unit, showDeveloperTools: Boolean,
) {
    val dual = device.panel.transport == PanelTransport.DUAL
    val state by remember(panel) { panel.connectionStates() }.collectAsState(panel.state.value.connectionChrome())
    val ready = if (dual) PanelTransport.BLE in state.connectedTransports else state.ready
    val busy = state.saving || if (dual) PanelTransport.BLE in state.connectingTransports else state.busy
    val status by workspace.status.collectAsState()
    val access = rememberBleScanAccess(forConnection = true)
    LaunchedEffect(device.id, access.canConnect, access.isDetermined) {
        if (access.isDetermined && !access.canConnect) workspace.disconnectBle(device.id)
    }
    Panel {
        Tag(device.name)
        val target = device.bleTarget
        if (target == null) {
            Text("还未关联蓝牙外设", style = MaterialTheme.typography.titleMedium)
            Text("可以先添加组件。关联并连接外设后，再从发现的服务和特征中绑定操作。", color = Muted)
            Primary("选择蓝牙外设", !status.busy, onScan)
        } else {
            Text(target.name ?: "未命名 BLE 外设", style = MaterialTheme.typography.titleMedium)
            if (showDeveloperTools) Text(target.id, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(if (ready) "已连接 · ${state.services.size} 个服务" else "未连接", color = Muted)
            if (!access.canConnect) {
                Text(access.status, color = Muted)
                access.actionLabel?.let { label -> Primary(label) { access.requestAccess() } }
            }
            Primary(if (ready) "断开连接" else if (busy && !state.saving) "正在连接或通信…" else "连接外设", !busy && (ready || access.canConnect)) {
                workspace.scan.stop()
                if (ready) workspace.disconnectBle(device.id) else workspace.connect(device.id)
            }
            Text("优先尝试已保存的设备标识；系统找不到设备或连接超时时，可重新扫描关联。连接后读取并订阅已配置的特征，写入由控件触发。", style = MaterialTheme.typography.bodySmall, color = Muted)
            Row {
                TextButton(onClick = onScan, enabled = !busy) { Text("重新扫描 / 更换外设") }
                TextButton(onClick = { workspace.associate(device.id, null) }, enabled = !busy && !status.busy) { Text("解除关联") }
            }
        }
        if (showDeveloperTools) {
            HorizontalDivider()
            Item("服务与特征", "浏览 GATT 结构、HEX 读写与通知", onClick = onDebug)
            Item("收发日志", "查看此设备面板和调试操作的记录", onClick = onLogs)
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
