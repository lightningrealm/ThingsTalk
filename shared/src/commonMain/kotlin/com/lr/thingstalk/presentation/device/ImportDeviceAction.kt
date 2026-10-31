package com.lr.thingstalk.presentation.device

import com.lr.thingstalk.presentation.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.domain.device.BleDeviceTarget
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.platformCapabilities
import com.lr.thingstalk.presentation.panel.rememberPanelFileTransfer
import com.lr.thingstalk.presentation.theme.Muted

/** Parse first: the file defines the transport; no empty device is created before validation. */
@Composable
fun ImportDeviceAction(workspace: DeviceWorkspaceViewModel, onCreated: (String) -> Unit,
    target: BleDeviceTarget? = null) {
    val status by workspace.status.collectAsState()
    var definition by remember { mutableStateOf<PanelDefinition?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val files = rememberPanelFileTransfer(onImport = { source ->
        try {
            val parsed = PanelJson.decode(source)
            require(platformCapabilities().ble || parsed.transport == PanelTransport.MQTT) { tr(AppStrings.web_supports_mqtt_device_configurations_only_3abd2c) }
            require(target == null || parsed.transport != PanelTransport.MQTT) { tr(AppStrings.mqtt_only_configurations_do_not_need_ble_create_one_usi_91c73c) }
            definition = parsed
            name = parsed.title
            error = null
            workspace.clearError()
        } catch (failure: Exception) { error = tr(AppStrings.could_not_create_device_d02fbe, failure.message ?: tr(AppStrings.invalid_configuration_format_7e9d24)) }
    }, onFeedback = { error = it })
    OutlinedButton(onClick = { error = null; files.importFile() }, enabled = !files.busy && !status.busy,
        modifier = Modifier.fillMaxWidth()) { Text(if (target == null) tr(AppStrings.create_device_from_configuration_0a4d7f) else tr(AppStrings.create_from_configuration_and_link_aa987a)) }
    error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    definition?.let { panel ->
        AlertDialog(onDismissRequest = { if (!status.busy) definition = null },
            title = { Text(tr(AppStrings.create_from_configuration_ffff88)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(AppStrings.detected_1fbdd8, if (panel.transport == PanelTransport.DUAL) tr(AppStrings.ble_mqtt_dual_connection_b44cfe) else panel.transport.name))
                    Text(tr(AppStrings.widgets_setup_forms_279516, panel.widgets.size, panel.setupPanels.size), color = Muted)
                    OutlinedTextField(name, { name = it }, label = { Text(tr(AppStrings.device_name_4433eb)) }, singleLine = true, enabled = !status.busy)
                    target?.let { Text(tr(AppStrings.ble_link_ea3ebb, it.name ?: it.id), color = Muted) }
                    Text(tr(AppStrings.connection_type_comes_from_the_file_configure_connectio_b123fb), color = Muted)
                    panel.setupImportNotice().takeIf { it.isNotBlank() }?.let { Text(diagnosticText(it), color = Muted) }
                    status.error?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !status.busy && name.trim().length in 1..80, onClick = {
                workspace.createFromPanel(name, panel, target) { id -> definition = null; onCreated(id) }
            }) { Text(if (status.busy) tr(AppStrings.creating_f5fd26) else tr(AppStrings.create_device_70a8d8)) } },
            dismissButton = { TextButton(enabled = !status.busy, onClick = { definition = null }) { Text(tr(AppStrings.cancel_4d0b46)) } })
    }
}
