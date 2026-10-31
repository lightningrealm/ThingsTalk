package com.lr.thingstalk.presentation.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.lr.thingstalk.domain.activity.ActivityRepository
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.navigation.*
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.panel.rememberPanelFileTransfer
import com.lr.thingstalk.presentation.theme.Muted
import com.lr.thingstalk.platformCapabilities

@Composable
internal fun TemplatePages(route: Route, activity: ActivityRepository, workspace: DeviceWorkspaceViewModel,
    go: (Route) -> Unit, feedback: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val archive by activity.archive.collectAsState()
    val error by activity.error.collectAsState()
    val devices by workspace.devices.collectAsState()
    var source by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun importText(text: String) {
        try {
            val panel = PanelJson.decode(text)
            require(platformCapabilities().ble || panel.transport == PanelTransport.MQTT) { "Web 仅支持 MQTT 模板" }
            source = PanelJson.encode(panel); title = panel.title; localError = null
        } catch (_: Exception) { localError = "配置格式或平台类型不支持，请使用真实 ThingsTalk 面板文件。" }
    }
    val files = rememberPanelFileTransfer(::importText, feedback)
    val template = archive.templates.firstOrNull { it.id == route.id }
    if (route.screen == Screen.PRESET) {
        val definition = remember(template?.json) { template?.let { runCatching { PanelJson.decode(it.json) }.getOrNull() } }
        Page("PANEL TEMPLATE", template?.title ?: "模板不存在", "应用前先载入编辑草稿，保存后才改变设备面板。") {
            if (template != null && definition != null) {
                Panel { KeyValue("传输", definition.transport.name); KeyValue("格式版本", definition.schemaVersion.toString()); KeyValue("组件", definition.widgets.size.toString()); KeyValue("设置表单", definition.setupPanels.size.toString()); Text(definition.setupImportNotice(), color = Muted) }
                OutlinedButton(onClick = { files.exportFile(template.json, "panel-template.thingstalk.json") }, enabled = !files.busy) { Text("导出模板") }
                Section("应用到设备")
                val eligible = devices.filter { it.panel.transport == definition.transport || it.panel.transport == PanelTransport.DUAL }
                if (eligible.isEmpty()) Text("没有兼容设备；请先创建设备或升级为双连接。", color = Muted)
                eligible.forEach { d -> Item(d.name, "将替换编辑草稿，设备标识和连接凭据保持不变") { targetId = d.id } }
                OutlinedButton(onClick = { deleting = true }) { Text("删除此模板") }
                Section("配置预览")
                SelectionContainer { Text(template.json, style = MaterialTheme.typography.bodySmall) }
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        targetId?.let { id -> AlertDialog(onDismissRequest = { targetId = null }, title = { Text("载入模板草稿？") },
            text = { Text("将替换此设备当前编辑草稿，包括未保存修改。不会立即写入硬件；请在编辑页检查并保存。") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                val editor = workspace.editor(id)
                editor?.state?.first { !it.loading }
                if (editor == null || !editor.state.value.editable || definition == null || template == null) { localError = "编辑器尚未就绪或有待恢复草稿，请先打开该设备编辑页处理。" }
                else { editor.importDefinition(definition, template.json); go(Route(Screen.PANEL_EDITOR, id)) }
                }
                targetId = null
            }) { Text("载入并检查") } }, dismissButton = { TextButton(onClick = { targetId = null }) { Text("取消") } }) }
        if (deleting && template != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除模板？") }, text = { Text("不会修改已经应用此模板的设备。") },
            confirmButton = { TextButton(onClick = { activity.deleteTemplate(template.id); deleting = false; go(Route(Screen.PRESETS)) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    } else Page("PANEL LIBRARY", "面板模板", "模板只保存协议与组件定义，不包含运行时输入或连接凭据。") {
        archive.templates.forEach { t -> Item(t.title, "查看、导出或应用到兼容设备") { go(Route(Screen.PRESET, t.id)) } }
        if (archive.templates.isEmpty()) Text("还没有模板，可以从已有设备保存或导入配置文件。", color = Muted)
        Section("从设备保存模板")
        devices.forEach { d -> TextButton(onClick = { source = PanelJson.encode(d.panel); title = d.name; localError = null }) { Text("选用 ${d.name} 的已保存面板") } }
        Section("导入与保存")
        OutlinedButton(onClick = files.importFile, enabled = !files.busy) { Text("导入 .thingstalk.json") }
        OutlinedTextField(title, { title = it }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(source, { source = it }, label = { Text("完整面板 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
        Primary("校验并保存模板", !files.busy && source.isNotBlank() && title.isNotBlank()) {
            try {
                val panel = PanelJson.decode(source)
                require(platformCapabilities().ble || panel.transport == PanelTransport.MQTT)
                activity.saveTemplate(title.trim(), source)
                localError = null; source = ""; title = ""
            } catch (failure: Exception) { localError = "无法保存：请检查格式、平台类型、名称与模板数量。" }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("最多 20 个模板；单项 50,000 字符。较大配置仍可直接从设备编辑页导入。", color = Muted)
    }
}
