package com.lr.thingstalk.presentation.device

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.device.UserDevice
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted

@Composable
fun DeviceListPage(viewModel: DeviceWorkspaceViewModel, onOpen: (String) -> Unit, onScan: () -> Unit, bleAvailable: Boolean = true, initiallyCreating: Boolean = false) {
    val devices by viewModel.devices.collectAsState()
    val status by viewModel.status.collectAsState()
    var transport by remember { mutableStateOf(if (bleAvailable) "BLE" else "MQTT") }
    var creating by remember { mutableStateOf(initiallyCreating) }
    var editing by remember { mutableStateOf<UserDevice?>(null) }
    var deleting by remember { mutableStateOf<UserDevice?>(null) }
    var name by remember { mutableStateOf("") }
    Page("YOUR DEVICES", tr(AppStrings.your_devices_defined_by_you_cbdeee), tr(AppStrings.create_a_device_then_add_the_widgets_it_needs_1ff782)) {
        Panel {
            Tag(tr(AppStrings.custom_devices_2373dc, devices.size))
            Primary(tr(AppStrings.create_device_manually_17435f), !status.busy) { name = ""; creating = true; viewModel.clearError() }
            ImportDeviceAction(viewModel, onOpen)
            if (bleAvailable) OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(tr(AppStrings.scan_nearby_ble_devices_7388fd)) }
        }
        status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (devices.isEmpty()) {
            Panel {
                Text(tr(AppStrings.start_with_a_blank_panel_c6aa3d), style = MaterialTheme.typography.titleMedium)
                Text(tr(AppStrings.any_device_type_sensors_lights_or_homemade_peripherals_f70d92), color = Muted)
                Text(tr(AppStrings.devices_and_panels_are_stored_locally_choose_ble_or_mqt_4fd050), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        devices.forEach { device ->
            key(device.id) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Item(device.name, tr(AppStrings.widgets_67224e, device.panel.widgets.size, if (device.panel.transport == com.lr.thingstalk.domain.panel.PanelTransport.DUAL) "BLE + MQTT" else if (device.panel.transport == com.lr.thingstalk.domain.panel.PanelTransport.MQTT) "MQTT" else device.bleTarget?.let { tr(AppStrings.linked_to_52b1e5, it.name ?: tr(AppStrings.ble_peripheral_ab04a3)) } ?: tr(AppStrings.no_peripheral_linked_25035a))) { onOpen(device.id) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { name = device.name; editing = device; viewModel.clearError() }, enabled = !status.busy) { Text(tr(AppStrings.rename_1cd80f)) }
                        TextButton(onClick = { deleting = device }, enabled = !status.busy) { Text(tr(AppStrings.delete_3755f5), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (creating || editing != null) AlertDialog(
        onDismissRequest = { if (!status.busy) { creating = false; editing = null } },
        title = { Text(if (creating) tr(AppStrings.create_device_manually_17435f) else tr(AppStrings.device_name_4433eb)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (creating) Text(tr(AppStrings.no_configuration_file_needed_choose_a_connection_type_t_078aeb), color = Muted)
                if (creating && bleAvailable) Choices(listOf("BLE", "MQTT", "BLE + MQTT"), transport) { transport = it }
                OutlinedTextField(name, { name = it }, label = { Text(tr(AppStrings.device_name_4433eb)) }, singleLine = true, enabled = !status.busy)
                status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim().length <= 80 && !status.busy, onClick = {
                if (creating) {
                    val created: (String) -> Unit = { id -> creating = false; onOpen(id) }
                    when (transport) { "MQTT" -> viewModel.createMqtt(name, created); "BLE + MQTT" -> viewModel.createDual(name, created); else -> viewModel.create(name, onCreated = created) }
                }
                else { editing?.let { viewModel.rename(it.id, name) }; editing = null }
            }) { Text(if (creating) tr(AppStrings.create_blank_device_dfbd13) else tr(AppStrings.save_fadf24)) }
        },
        dismissButton = { TextButton(onClick = { creating = false; editing = null }, enabled = !status.busy) { Text(tr(AppStrings.cancel_4d0b46)) } },
    )
    deleting?.let { device ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text(tr(AppStrings.delete_69cde0, device.name)) },
            text = { Text(tr(AppStrings.deletes_the_locally_saved_device_widgets_and_ble_link_t_830295)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(device.id); deleting = null }) { Text(tr(AppStrings.delete_3755f5), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr(AppStrings.cancel_4d0b46)) } },
        )
    }
}
