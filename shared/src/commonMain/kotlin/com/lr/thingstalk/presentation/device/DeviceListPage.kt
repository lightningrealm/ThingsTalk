package com.lr.thingstalk.presentation.device

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
    Page("YOUR DEVICES", "你的设备，由你定义。", "创建设备，再为它添加适合的组件。") {
        Panel {
            Tag("${devices.size} 台自定义设备")
            Primary("手动创建设备", !status.busy) { name = ""; creating = true; viewModel.clearError() }
            ImportDeviceAction(viewModel, onOpen)
            if (bleAvailable) OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("扫描附近的蓝牙设备") }
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (devices.isEmpty()) {
            Panel {
                Text("从一张空白面板开始", style = MaterialTheme.typography.titleMedium)
                Text("不限定设备类型。温度传感器、灯光或自制外设，都可以用状态卡、开关、滑块和按钮来组合。", color = Muted)
                Text("设备与面板保存在本机。选择 BLE 或 MQTT，为组件配置收发操作。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        devices.forEach { device ->
            key(device.id) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Item(device.name, "${device.panel.widgets.size} 个组件 · ${if (device.panel.transport == com.lr.thingstalk.domain.panel.PanelTransport.DUAL) "BLE + MQTT" else if (device.panel.transport == com.lr.thingstalk.domain.panel.PanelTransport.MQTT) "MQTT" else device.bleTarget?.let { "已关联 ${it.name ?: "BLE 外设"}" } ?: "未关联外设"}") { onOpen(device.id) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { name = device.name; editing = device; viewModel.clearError() }, enabled = !status.busy) { Text("重命名") }
                        TextButton(onClick = { deleting = device }, enabled = !status.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (creating || editing != null) AlertDialog(
        onDismissRequest = { if (!status.busy) { creating = false; editing = null } },
        title = { Text(if (creating) "手动创建设备" else "设备名称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (creating) Text("无需配置文件。选择连接类型，创建后自行添加组件与绑定。", color = Muted)
                if (creating && bleAvailable) Choices(listOf("BLE", "MQTT", "BLE + MQTT"), transport) { transport = it }
                OutlinedTextField(name, { name = it }, label = { Text("设备名称") }, singleLine = true, enabled = !status.busy)
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim().length <= 80 && !status.busy, onClick = {
                if (creating) {
                    val created: (String) -> Unit = { id -> creating = false; onOpen(id) }
                    when (transport) { "MQTT" -> viewModel.createMqtt(name, created); "BLE + MQTT" -> viewModel.createDual(name, created); else -> viewModel.create(name, onCreated = created) }
                }
                else { editing?.let { viewModel.rename(it.id, name) }; editing = null }
            }) { Text(if (creating) "创建空白设备" else "保存") }
        },
        dismissButton = { TextButton(onClick = { creating = false; editing = null }, enabled = !status.busy) { Text("取消") } },
    )
    deleting?.let { device ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除 ${device.name}？") },
            text = { Text("将删除本机保存的设备、组件和蓝牙关联。此操作不会改变外设本身。") },
            confirmButton = { TextButton(onClick = { viewModel.delete(device.id); deleting = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
