package com.lr.thingstalk.presentation.device

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
            require(platformCapabilities().ble || parsed.transport == PanelTransport.MQTT) { "Web 仅支持 MQTT 设备配置" }
            require(target == null || parsed.transport != PanelTransport.MQTT) { "纯 MQTT 配置无需关联蓝牙，请从设备列表的导入入口创建" }
            definition = parsed
            name = parsed.title
            error = null
            workspace.clearError()
        } catch (failure: Exception) { error = "无法创建设备：${failure.message ?: "配置格式无效"}" }
    }, onFeedback = { error = it })
    OutlinedButton(onClick = { error = null; files.importFile() }, enabled = !files.busy && !status.busy,
        modifier = Modifier.fillMaxWidth()) { Text(if (target == null) "从配置文件创建设备" else "从配置创建并关联") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    definition?.let { panel ->
        AlertDialog(onDismissRequest = { if (!status.busy) definition = null },
            title = { Text("从配置创建设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("已识别：${if (panel.transport == PanelTransport.DUAL) "BLE + MQTT 双连接" else panel.transport.name}")
                    Text("${panel.widgets.size} 个组件 · ${panel.setupPanels.size} 个设置表单", color = Muted)
                    OutlinedTextField(name, { name = it }, label = { Text("设备名称") }, singleLine = true, enabled = !status.busy)
                    target?.let { Text("关联蓝牙：${it.name ?: it.id}", color = Muted) }
                    Text("连接类型由配置决定。创建后仍需配置连接；不会自动连接或发送设备命令。", color = Muted)
                    panel.setupImportNotice().takeIf { it.isNotBlank() }?.let { Text(it, color = Muted) }
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !status.busy && name.trim().length in 1..80, onClick = {
                workspace.createFromPanel(name, panel, target) { id -> definition = null; onCreated(id) }
            }) { Text(if (status.busy) "正在创建…" else "创建设备") } },
            dismissButton = { TextButton(enabled = !status.busy, onClick = { definition = null }) { Text("取消") } })
    }
}
