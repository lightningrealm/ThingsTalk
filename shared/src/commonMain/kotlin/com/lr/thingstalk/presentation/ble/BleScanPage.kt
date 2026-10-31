package com.lr.thingstalk.presentation.ble

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.ble.BleScanDevice
import com.lr.thingstalk.domain.device.BleDeviceTarget
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.device.ImportDeviceAction
import com.lr.thingstalk.presentation.theme.Muted

@Composable
fun BleScanPage(scan: BleScanViewModel, workspace: DeviceWorkspaceViewModel, deviceId: String?, onAssociated: (String) -> Unit, onCreateManual: () -> Unit = {}) {
    val state by scan.state.collectAsState()
    val devices by workspace.devices.collectAsState()
    val status by workspace.status.collectAsState()
    val visibleDevices = state.visibleDevices
    val access = rememberBleScanAccess()
    var selected by remember { mutableStateOf<BleScanDevice?>(null) }
    var newName by remember { mutableStateOf("") }
    val bindingDevice = devices.firstOrNull { it.id == deviceId }
    DisposableEffect(scan) { onDispose { scan.stop() } }
    LaunchedEffect(access.canScan) { if (!access.canScan) scan.stop() }
    Page("BLUETOOTH DISCOVERY", if (bindingDevice == null) tr(AppStrings.discover_nearby_devices_6465bc) else tr(AppStrings.link_32e0d9, bindingDevice.name), tr(AppStrings.scan_real_ble_advertisements_and_link_a_peripheral_to_y_153bbe)) {
        Panel {
            Tag(tr(AppStrings.ble_scan_27c7c6))
            Text(diagnosticText(access.status), color = Muted)
            if (access.canScan) Primary(if (state.scanning) tr(AppStrings.stop_scanning_60f221) else tr(AppStrings.scan_for_15_seconds_c6bc06)) {
                if (state.scanning) scan.stop() else scan.start()
            } else access.actionLabel?.let { label -> Primary(diagnosticText(label)) { access.requestAccess() } }
            Text(tr(AppStrings.only_advertising_ble_peripherals_are_found_selecting_on_07ba7d), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (deviceId == null) Panel {
            Text(tr(AppStrings.create_device_70a8d8), style = MaterialTheme.typography.titleMedium)
            Primary(tr(AppStrings.create_device_manually_17435f), !status.busy, onCreateManual)
            ImportDeviceAction(workspace, onAssociated)
        }
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
        BleScanFilters(state.filter, scan::setFilter, scan::clearFilter)
        Section(if (state.filter.isActive) tr(AppStrings.showing_peripherals_found_9fb291, visibleDevices.size, state.devices.size) else tr(AppStrings.peripherals_found_741f93, state.devices.size))
        if (visibleDevices.isEmpty()) Text(
            when {
                state.filter.serviceUuidError != null -> tr(AppStrings.correct_the_service_uuid_or_clear_the_filters_8cb46c)
                state.devices.isNotEmpty() -> if (state.scanning) tr(AppStrings.scanning_but_no_devices_match_yet_try_fewer_filters_043d96) else tr(AppStrings.no_matching_devices_try_fewer_filters_9defd2)
                state.scanning -> tr(AppStrings.listening_for_nearby_advertisements_a47795)
                state.hasScanned -> tr(AppStrings.no_peripherals_found_enable_advertising_and_move_the_de_9aa26f)
                else -> tr(AppStrings.start_scanning_to_see_nearby_devices_here_06790e)
            },
            color = Muted,
        )
        if (state.devices.size >= 200) Text(tr(AppStrings.up_to_200_peripherals_per_scan_filters_apply_only_to_di_ab7df7), color = Muted, style = MaterialTheme.typography.bodySmall)
        visibleDevices.forEach { found ->
            key(found.id) {
                Item(found.name?.takeIf { it.isNotBlank() } ?: tr(AppStrings.unnamed_ble_peripheral_39b9cd), tr(AppStrings.dbm_n_advertised_services_965e7a, found.rssi, found.id, found.serviceUuids.size), "bluetooth") {
                    scan.stop(); selected = found; newName = found.name?.takeIf { it.isNotBlank() } ?: tr(AppStrings.my_ble_device_d06dde); workspace.clearError()
                }
            }
        }
    }
    selected?.let { found ->
        val target = BleDeviceTarget(found.id, found.name)
        AlertDialog(
            onDismissRequest = { if (!status.busy) selected = null },
            title = { Text(bindingDevice?.let { tr(AppStrings.link_to_f94972, it.name) } ?: tr(AppStrings.add_to_my_devices_a28905)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(found.name ?: tr(AppStrings.unnamed_ble_peripheral_39b9cd))
                    Text(found.id, style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (bindingDevice == null) {
                        ImportDeviceAction(workspace, { id -> selected = null; onAssociated(id) }, target)
                        OutlinedTextField(newName, { newName = it }, label = { Text(tr(AppStrings.new_device_name_d42dbb)) }, singleLine = true, enabled = !status.busy)
                        if (devices.isNotEmpty()) {
                            Text(tr(AppStrings.or_link_to_an_existing_device_9ee092), style = MaterialTheme.typography.labelLarge)
                            // The list scrolls inside the dialog when the user's catalog grows.
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 180.dp)) {
                                items(devices.size) { index ->
                                    val existing = devices[index]
                                    TextButton(enabled = !status.busy, onClick = {
                                        workspace.associate(existing.id, target) { selected = null; onAssociated(existing.id) }
                                    }) { Text(existing.name) }
                                }
                            }
                        }
                    } else if (bindingDevice.bleTarget != null) Text(tr(AppStrings.this_replaces_the_current_peripheral_link_check_the_exi_5ae3f7), color = Muted)
                    status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !status.busy && (bindingDevice != null || newName.trim().length in 1..80), onClick = {
                    if (bindingDevice != null) workspace.associate(bindingDevice.id, target) { selected = null; onAssociated(bindingDevice.id) }
                    else workspace.create(newName, target) { id -> selected = null; onAssociated(id) }
                }) { Text(if (bindingDevice == null) tr(AppStrings.create_and_link_f42aaa) else tr(AppStrings.link_peripheral_519ee8)) }
            },
            dismissButton = { TextButton(enabled = !status.busy, onClick = { selected = null }) { Text(tr(AppStrings.cancel_4d0b46)) } },
        )
    }
}
