package com.lr.thingstalk.presentation.mqtt

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.domain.mqtt.*
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.theme.Muted
import kotlin.uuid.Uuid

val LocalMqttProfiles = staticCompositionLocalOf<MqttProfilesViewModel> { error("MQTT profile provider missing") }

@Composable
fun MqttProfilePicker(owner: String, enabled: Boolean, onManage: () -> Unit) {
    val vm = LocalMqttProfiles.current
    val archive by vm.profiles.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val selected = archive.selectedProfile(owner)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && loaded && !busy && archive.profiles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: if (archive.profiles.isEmpty()) "还没有连接配置" else "选择连接配置")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            archive.profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = { expanded = false; vm.select(owner, profile.id) })
            }
        }
    }
    selected?.let {
        Text(it.endpoint, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(if (it.requiresPassword && it.password.isEmpty()) "需补充本次会话密码" else if (it.username.isNotEmpty() || it.password.isNotEmpty()) "已配置身份验证" else "匿名连接", color = Muted)
    }
    TextButton(onClick = onManage, enabled = !busy) { Text("管理连接配置") }
    if (!loaded && !busy) TextButton(onClick = vm::reload) { Text("重新读取配置") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun MqttProfilesPage(owner: String, onEdit: (String) -> Unit) {
    val vm = LocalMqttProfiles.current
    val archive by vm.profiles.collectAsState()
    val busy by vm.busy.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val error by vm.error.collectAsState()
    var deleting by remember { mutableStateOf<MqttProfile?>(null) }
    Page("MQTT CONNECTIONS", "连接配置", "工作台与自定义设备共用，选择后再明确发起连接。") {
        Primary("新增连接配置", loaded && !busy) { onEdit("") }
        if (!loaded) {
            Text(if (busy) "正在读取连接配置…" else "配置暂不可用，原数据已保留", color = Muted)
            TextButton(onClick = vm::reload, enabled = !busy) { Text("重新读取") }
        }
        archive.profiles.forEach { profile ->
            Panel {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(profile.endpoint, color = Muted)
                if (archive.selected[owner] == profile.id) Tag("当前选用")
                Row {
                    TextButton(onClick = { vm.select(owner, profile.id) }, enabled = !busy) { Text("选用") }
                    TextButton(onClick = { onEdit(profile.id) }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = { deleting = profile }, enabled = !busy) { Text("删除") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("修改或删除配置不会改变已经建立的连接，下次连接时才使用新配置。", color = Muted)
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${profile.name}？") },
            text = { Text("将清除这份本机配置及其选用记录，不删除设备面板，也不会断开现有连接。") },
            confirmButton = { TextButton(onClick = { vm.delete(profile.id); deleting = null }, enabled = !busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
fun MqttProfileEditorPage(owner: String, profileId: String, initialEndpoint: String, onSaved: () -> Unit) {
    val vm = LocalMqttProfiles.current
    val archive by vm.profiles.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    if (!loaded) { Page("MQTT CONFIG", "连接配置", "") { Text("请先读取连接配置"); TextButton(onClick = vm::reload, enabled = !busy) { Text("重新读取") } }; return }
    val existing = archive.profiles.firstOrNull { it.id == profileId }
    if (profileId.isNotEmpty() && existing == null) { Page("MQTT CONFIG", "配置已删除", "返回配置列表重新选择。") {}; return }
    var draft by remember(owner, profileId) { mutableStateOf(existing ?: MqttProfile(Uuid.random().toString(), "", initialEndpoint)) }
    var discard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { discard = true }, enabled = !busy) { Text("还原修改") }
                TextButton(onClick = { vm.save(draft, owner, onSaved) }, enabled = !busy && draft.name.isNotBlank() && draft.endpoint.isNotBlank()) { Text(if (busy) "保存中…" else "保存并选用") }
            }
        }
        Box(Modifier.weight(1f)) {
            Page("MQTT CONFIG", if (profileId.isEmpty()) "新增连接配置" else "编辑连接配置", "支持 MQTT 5 · WebSocket / WSS") {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Panel {
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("配置名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.endpoint, { draft = draft.copy(endpoint = it) }, label = { Text("服务器地址") }, placeholder = { Text("wss://broker.example.com:8084/mqtt") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text("地址包含端口与路径。当前不支持 mqtt://、原生 TCP、URL 查询参数或自定义客户端证书。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("自动生成 Client ID")
                        Switch(draft.automaticClientId, { draft = draft.copy(automaticClientId = it) }, enabled = !busy)
                    }
                    if (!draft.automaticClientId) OutlinedTextField(draft.clientId, { draft = draft.copy(clientId = it) }, label = { Text("固定 Client ID") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text(if (draft.automaticClientId) "每次连接生成独立 ID，多台设备共用配置时避免互相挤下线。" else "固定 ID 必须符合服务器要求；同一配置用于多个并发连接时可能互相挤下线。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Panel {
                    Text("身份验证（可选）", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text("用户名") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.password, { draft = draft.copy(password = it, requiresPassword = it.isNotEmpty()) }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (draft.requiresPassword && draft.password.isEmpty()) Text("这份配置需要密码，请补充；如已改为无密码认证，请明确清除。", color = Muted)
                    TextButton(onClick = { draft = draft.copy(password = "", requiresPassword = false) }, enabled = !busy) { Text("清除密码") }
                    Text(if (vm.passwordsPersisted) "凭据保存在本机受保护存储中，不写入面板导出文件。" else "浏览器仅保存普通配置，密码只保留在本次会话；刷新后需在这里补充。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("使用干净会话，不自动重连或补发消息。保存仅更新配置，不立即连接。离开编辑页会丢弃未保存修改。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("还原未保存修改？") },
        confirmButton = { TextButton(onClick = { draft = existing ?: MqttProfile(draft.id, "", initialEndpoint); discard = false }) { Text("还原") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("取消") } })
}
