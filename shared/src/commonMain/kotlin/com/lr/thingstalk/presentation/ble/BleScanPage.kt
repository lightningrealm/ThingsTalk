package com.lr.thingstalk.presentation.ble

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
    Page("BLUETOOTH DISCOVERY", if (bindingDevice == null) "发现身边的设备。" else "关联 ${bindingDevice.name}", "读取真实 BLE 广播，选择后关联到你的设备面板。") {
        Panel {
            Tag("真实 BLE 扫描")
            Text(access.status, color = Muted)
            if (access.canScan) Primary(if (state.scanning) "停止扫描" else "开始扫描 · 15 秒") {
                if (state.scanning) scan.stop() else scan.start()
            } else access.actionLabel?.let { label -> Primary(label) { access.requestAccess() } }
            Text("仅扫描正在广播的 BLE 外设；选择结果只保存关联，连接由你在设备页发起。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (deviceId == null) Panel {
            Text("创建设备", style = MaterialTheme.typography.titleMedium)
            Primary("手动创建设备", !status.busy, onCreateManual)
            ImportDeviceAction(workspace, onAssociated)
        }
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BleScanFilters(state.filter, scan::setFilter, scan::clearFilter)
        Section(if (state.filter.isActive) "显示 ${visibleDevices.size} / 发现 ${state.devices.size} 台外设" else "发现 ${state.devices.size} 台外设")
        if (visibleDevices.isEmpty()) Text(
            when {
                state.filter.serviceUuidError != null -> "请修正服务 UUID，或清除筛选条件。"
                state.devices.isNotEmpty() -> if (state.scanning) "暂时没有符合条件的设备，仍在扫描。可放宽条件或清除筛选。" else "没有符合条件的设备。可放宽条件或清除筛选。"
                state.scanning -> "正在接收附近广播…"
                state.hasScanned -> "未发现外设。请让设备进入广播模式并靠近手机，再重试。"
                else -> "点击开始扫描，结果将显示在这里。"
            },
            color = Muted,
        )
        if (state.devices.size >= 200) Text("本轮最多保留 200 台外设；筛选仅作用于已发现的结果。", color = Muted, style = MaterialTheme.typography.bodySmall)
        visibleDevices.forEach { found ->
            key(found.id) {
                Item(found.name?.takeIf { it.isNotBlank() } ?: "未命名 BLE 外设", "${found.rssi} dBm · ${found.id}\n${found.serviceUuids.size} 个广播服务", "bluetooth") {
                    scan.stop(); selected = found; newName = found.name?.takeIf { it.isNotBlank() } ?: "我的蓝牙设备"; workspace.clearError()
                }
            }
        }
    }
    selected?.let { found ->
        val target = BleDeviceTarget(found.id, found.name)
        AlertDialog(
            onDismissRequest = { if (!status.busy) selected = null },
            title = { Text(bindingDevice?.let { "关联到 ${it.name}" } ?: "添加到我的设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(found.name ?: "未命名 BLE 外设")
                    Text(found.id, style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (bindingDevice == null) {
                        ImportDeviceAction(workspace, { id -> selected = null; onAssociated(id) }, target)
                        OutlinedTextField(newName, { newName = it }, label = { Text("新设备名称") }, singleLine = true, enabled = !status.busy)
                        if (devices.isNotEmpty()) {
                            Text("或关联到已有设备", style = MaterialTheme.typography.labelLarge)
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
                    } else if (bindingDevice.bleTarget != null) Text("将替换当前外设关联，请检查已有组件的特征绑定。", color = Muted)
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !status.busy && (bindingDevice != null || newName.trim().length in 1..80), onClick = {
                    if (bindingDevice != null) workspace.associate(bindingDevice.id, target) { selected = null; onAssociated(bindingDevice.id) }
                    else workspace.create(newName, target) { id -> selected = null; onAssociated(id) }
                }) { Text(if (bindingDevice == null) "创建并关联" else "关联外设") }
            },
            dismissButton = { TextButton(enabled = !status.busy, onClick = { selected = null }) { Text("取消") } },
        )
    }
}
