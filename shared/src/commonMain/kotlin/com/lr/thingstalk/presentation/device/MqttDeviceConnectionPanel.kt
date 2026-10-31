package com.lr.thingstalk.presentation.device

import com.lr.thingstalk.presentation.i18n.*
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
        Tag("MQTT 5 · ${if (ready) tr(AppStrings.connected_65fe35) else tr(AppStrings.disconnected_f2f3e9)}")
        MqttProfilePicker(owner, !ready && !connecting, onProfiles)
        if (ready) Text(tr(AppStrings.current_connection_fb9be9, device.mqttEndpoint), color = Muted, style = MaterialTheme.typography.bodySmall)
        if (archive.selectedProfile(owner) == null && device.mqttEndpoint.isNotEmpty()) Text(tr(AppStrings.existing_address_will_be_prefilled_in_a_new_profile_add_d7d95f, device.mqttEndpoint), color = Muted)
        Primary(if (ready || connecting) tr(AppStrings.disconnect_eb7246) else tr(AppStrings.connect_mqtt_defd16), !state.busy && (ready || connecting || archive.selectedProfile(owner) != null)) {
            if (ready || connecting) { workspace.disconnectMqtt(device.id); connecting = false }
            else profiles.connect(owner) { config -> connecting = true; workspace.connectMqtt(device.id, config) }
        }
        if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(tr(AppStrings.each_device_has_a_separate_connection_profile_changes_a_2b85af), color = Muted, style = MaterialTheme.typography.bodySmall)
        status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
    }
}
