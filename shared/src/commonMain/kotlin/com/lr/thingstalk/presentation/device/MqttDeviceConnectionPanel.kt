package com.lr.thingstalk.presentation.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.device.UserDevice
import com.lr.thingstalk.domain.panel.PanelTransport
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.mqtt.*
import com.lr.thingstalk.presentation.panel.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
internal fun MqttDeviceConnectionPanel(device: UserDevice, panel: PanelViewModel, workspace: DeviceWorkspaceViewModel, onProfiles: () -> Unit) {
    val state by remember(panel) { panel.connectionStates() }.collectAsState(panel.state.value.connectionChrome())
    val ready = if (device.panel.transport == PanelTransport.DUAL) PanelTransport.MQTT in state.connectedTransports else state.ready
    val status by workspace.status.collectAsState()
    val profiles = LocalMqttProfiles.current
    val archive by profiles.profiles.collectAsState()
    val owner = "device:${device.id}"
    var connecting by remember(device.id) { mutableStateOf(false) }
    LaunchedEffect(ready, status.error) { if (ready || status.error != null) connecting = false }
    Panel {
        Tag("MQTT 5 · ${if (ready) "已连接" else "未连接"}")
        MqttProfilePicker(owner, !ready && !connecting, onProfiles)
        if (ready) Text("当前连接：${device.mqttEndpoint}", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (archive.selectedProfile(owner) == null && device.mqttEndpoint.isNotEmpty()) Text("已有地址 ${device.mqttEndpoint}；新增连接配置时会带入，可补齐身份验证。", color = Muted)
        Primary(if (ready || connecting) "断开连接" else "连接 MQTT", !state.busy && (ready || connecting || archive.selectedProfile(owner) != null)) {
            if (ready || connecting) { workspace.disconnectMqtt(device.id); connecting = false }
            else profiles.connect(owner) { config -> connecting = true; workspace.connectMqtt(device.id, config) }
        }
        if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("每台设备使用独立连接。配置修改在下次连接生效；切页保持连接，应用进入后台时停止 MQTT 连接。", color = Muted, style = MaterialTheme.typography.bodySmall)
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
