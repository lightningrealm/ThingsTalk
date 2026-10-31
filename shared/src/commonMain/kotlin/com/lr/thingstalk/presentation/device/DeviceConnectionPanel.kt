package com.lr.thingstalk.presentation.device

import com.lr.thingstalk.presentation.i18n.*
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
            !enabled(channel) -> tr(AppStrings.disabled_8bb38e)
            channel in state.connectingTransports || (!dual && state.busy && !state.ready) -> tr(AppStrings.connecting_699ade)
            channel in state.connectedTransports || (!dual && state.ready) -> tr(AppStrings.connected_65fe35)
            else -> tr(AppStrings.disconnected_f2f3e9)
        }
        TransportTabs(selected, { if (channelPanel) panel.selectChannel(it) else settingsTab = it }, status(PanelTransport.BLE), status(PanelTransport.MQTT))
        Text(if (channelPanel) tr(AppStrings.channel_widgets_below_use_this_channel_s_bindings_b64e5f, selected.name) else tr(AppStrings.connection_settings_widget_routing_is_defined_by_the_pa_39ac13), color = Muted, style = MaterialTheme.typography.bodySmall)
        @Composable fun inactive(channel: PanelTransport) {
            Panel {
                Text(tr(AppStrings.is_not_enabled_4d7ae3, channel.name), style = MaterialTheme.typography.titleMedium)
                Text(tr(AppStrings.enabling_adds_ble_and_mqtt_support_while_preserving_wid_8a6148), color = Muted)
                Primary(tr(AppStrings.enable_upgrade_to_dual_connection_a54e35, channel.name), !workspaceStatus.busy) { workspace.promoteToDual(device.id) }
                workspaceStatus.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
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
            Text(tr(AppStrings.no_ble_peripheral_linked_yet_7eb6d7), style = MaterialTheme.typography.titleMedium)
            Text(tr(AppStrings.you_can_add_widgets_first_link_and_connect_a_peripheral_3e7a7d), color = Muted)
            Primary(tr(AppStrings.choose_ble_peripheral_cf4296), !status.busy, onScan)
        } else {
            Text(target.name ?: tr(AppStrings.unnamed_ble_peripheral_39b9cd), style = MaterialTheme.typography.titleMedium)
            if (showDeveloperTools) Text(target.id, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(if (ready) tr(AppStrings.connected_services_2df8bb, state.services.size) else tr(AppStrings.disconnected_f2f3e9), color = Muted)
            if (!access.canConnect) {
                Text(diagnosticText(access.status), color = Muted)
                access.actionLabel?.let { label -> Primary(diagnosticText(label)) { access.requestAccess() } }
            }
            Primary(if (ready) tr(AppStrings.disconnect_eb7246) else if (busy && !state.saving) tr(AppStrings.connecting_or_communicating_120446) else tr(AppStrings.connect_peripheral_b95c34), !busy && (ready || access.canConnect)) {
                workspace.scan.stop()
                if (ready) workspace.disconnectBle(device.id) else workspace.connect(device.id)
            }
            Text(tr(AppStrings.tries_the_saved_device_identifier_first_rescan_if_the_d_d77501), style = MaterialTheme.typography.bodySmall, color = Muted)
            Row {
                TextButton(onClick = onScan, enabled = !busy) { Text(tr(AppStrings.rescan_change_peripheral_94872a)) }
                TextButton(onClick = { workspace.associate(device.id, null) }, enabled = !busy && !status.busy) { Text(tr(AppStrings.unlink_25a139)) }
            }
        }
        if (showDeveloperTools) {
            HorizontalDivider()
            Item(tr(AppStrings.services_and_characteristics_36f76b), tr(AppStrings.browse_gatt_read_write_hex_and_receive_notifications_cb98ad), onClick = onDebug)
            Item(tr(AppStrings.traffic_log_c1c823), tr(AppStrings.view_this_device_s_panel_and_debugger_traffic_8266cf), onClick = onLogs)
        }
        status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
    }
}
