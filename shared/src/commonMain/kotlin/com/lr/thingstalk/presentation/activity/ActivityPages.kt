package com.lr.thingstalk.presentation.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lr.thingstalk.domain.activity.*
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.navigation.*
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.panel.*
import com.lr.thingstalk.presentation.theme.Muted
import com.lr.thingstalk.getPlatform
import com.lr.thingstalk.platformCapabilities
import kotlin.time.Instant

private fun ReadUpdateResult.notice(): String = error ?: "已更新 $changed 条 · 操作后 $unread 条未读"

private fun timeLabel(value: Long) = Instant.fromEpochMilliseconds(value).toString().replace('T',' ').substringBefore('.').removeSuffix("Z") + " UTC"

@Composable
fun ActivityPages(route: Route, activity: ActivityRepository, workspace: DeviceWorkspaceViewModel,
    go: (Route) -> Unit, feedback: (String) -> Unit) {
    val archive by activity.archive.collectAsState()
    val records = if (route.screen in setOf(Screen.EVENT, Screen.ACTIVITY_RECORD, Screen.DATA_SETTINGS))
        activity.records.collectAsState().value else emptyList()
    val error by activity.error.collectAsState()
    val saving by activity.saving.collectAsState()
    val devices by workspace.devices.collectAsState()
    when (route.screen) {
        Screen.EVENTS -> {
            var view by remember { mutableStateOf(if (route.id == "traffic") "通信记录" else "设备事件") }
            var filter by remember { mutableStateOf("全部") }
            var transport by remember { mutableStateOf("全部") }
            var selectedDevice by remember { mutableStateOf("") }
            var query by remember { mutableStateOf("") }
            var limit by remember(view, filter, transport, selectedDevice, query) { mutableStateOf(50) }
            Page("ACTIVITY", "消息", "只记录实际发生的通信与事件；后台暂停期间不保证收取。") {
                Choices(listOf("设备事件", "通信记录"), view) { view = it }
                Choices(if (platformCapabilities().ble) listOf("全部","BLE","MQTT") else listOf("全部","MQTT"), transport) { transport = it }
                OutlinedTextField(query, { query = it }, label = { Text("搜索设备、事件或 topic / UUID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                var chooseDevice by remember { mutableStateOf(false) }
                TextButton(onClick = { chooseDevice = !chooseDevice }) { Text("设备：${devices.firstOrNull { it.id == selectedDevice }?.name ?: "全部"}") }
                if (chooseDevice) {
                    TextButton(onClick = { selectedDevice = ""; chooseDevice = false }) { Text("全部设备与工作台") }
                    devices.forEach { d -> TextButton(onClick = { selectedDevice = d.id; chooseDevice = false }) { Text(d.name) } }
                }
                if (view == "设备事件") {
                    Choices(listOf("全部", "未读", "待处理"), filter) { filter = it }
                    Text("${archive.events.count { !it.read }} 条未读 · 本地保留 ${archive.retentionDays} 天", color = Muted)
                    TextButton(onClick = { feedback(activity.markAllRead().notice()) }) { Text("全部标为已读") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val rows = archive.events.filter { e -> (transport == "全部" || e.transport == transport) && (selectedDevice.isEmpty() || e.deviceId == selectedDevice) &&
                        (filter == "全部" || filter == "未读" && !e.read || filter == "待处理" && e.warning && !e.acknowledged) &&
                        (e.title + e.deviceName + e.detail).contains(query, true) }
                    if (rows.isEmpty()) Panel { Text("暂无符合条件的事件"); Text("连接设备或进行操作后，真实事件会出现在这里。", color = Muted) }
                    rows.take(limit).forEach { e -> key(e.id) { Item((if (!e.read) "● " else "") + e.title,
                        "${e.deviceName} · ${e.transport} · ${timeLabel(e.timestamp)}${if (e.count > 1) " · ${e.count} 次" else ""}") {
                        go(Route(Screen.EVENT, e.id))
                    } } }
                    if (rows.size > limit) TextButton(onClick = { limit += 50 }) { Text("加载更多") }
                } else {
                    val records by activity.records.collectAsState()
                    Text("最近 ${records.size} 条 · 仅当前运行期间保留 · 原始负载默认隐藏", color = Muted)
                    val rows = records.filter { r -> (transport == "全部" || r.transport == transport) && (selectedDevice.isEmpty() || r.deviceId == selectedDevice) && (r.deviceName + r.operation + r.address).contains(query, true) }
                    if (rows.isEmpty()) Text("暂无通信记录", color = Muted)
                    rows.take(limit).forEach { r -> key(r.id) { Item("${r.transport} · ${r.operation}", "${r.deviceName} · ${r.address.take(80)} · ${timeLabel(r.timestamp)}") { go(Route(Screen.ACTIVITY_RECORD, r.id)) } } }
                    if (rows.size > limit) TextButton(onClick = { limit += 50 }) { Text("加载更多") }
                }
                Item("连接管理", "查看设备会话，主动连接或停止") { go(Route(Screen.SUBSCRIPTIONS)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = activity::retrySave) { Text("重试保存") } }
                if (saving) Text("本地更改正在保存…", color = Muted)
            }
        }
        Screen.EVENT -> {
            val e = archive.events.firstOrNull { it.id == route.id }
            // Mark on entry, including restored/direct routes. Do not key on read: users may
            // explicitly mark this event unread while staying on the detail page.
            var readNotice by remember(route.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(activity, e?.id) {
                e?.let { readNotice = activity.markRead(it.id).notice() }
            }
            Page("EVENT", e?.title ?: "事件已清理", "本地事件详情") {
                if (e != null) {
                    Panel {
                        KeyValue("设备", e.deviceName); KeyValue("来源", e.transport); KeyValue("最后发生", timeLabel(e.timestamp))
                        KeyValue("次数", e.count.toString()); Text(e.detail)
                        Text(if (e.read) "已读" else "未读", color = Muted)
                    }
                    TextButton(onClick = { readNotice = activity.markRead(e.id, !e.read).notice() }) { Text(if (e.read) "标为未读" else "标为已读") }
                    readNotice?.let { Text(it, color = Muted) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (saving) Text("已读更改正在保存到本机…", color = Muted)
                    if (e.warning) Primary(if (e.acknowledged) "已确认" else "确认已处理", !e.acknowledged) { activity.acknowledge(e.id) }
                    Text("确认只更新本机记录，不发送设备命令。", color = Muted)
                    val r = records.firstOrNull { it.id == e.recordId }
                    if (r != null) Item("关联通信记录", r.operation) { go(Route(Screen.ACTIVITY_RECORD, r.id)) }
                    else Text("原始通信记录未保留或已过期。", color = Muted)
                    if (devices.any { it.id == e.deviceId }) Item("打开设备", e.deviceName) { go(Route(Screen.DEVICE, e.deviceId)) }
                    else Text(if (e.deviceId.isEmpty()) "来源为 MQTT 工作台" else "设备已删除，保留历史名称。", color = Muted)
                }
            }
        }
        Screen.ACTIVITY_RECORD -> {
            val r = records.firstOrNull { it.id == route.id }
            Page("TRAFFIC DETAIL", r?.operation ?: "通信记录已过期", "诊断记录不等同于设备执行回执。") {
                r?.let {
                    Panel { KeyValue("设备", it.deviceName); KeyValue("传输", it.transport); KeyValue("时间", timeLabel(it.timestamp)); KeyValue("字节数", it.byteCount.toString())
                        SelectionContainer { Text(it.address) }; Text(it.detail) }
                    Panel { Text("HEX（最多预览 512 字节）"); SelectionContainer { Text(it.payload ?: "负载未采集或已脱敏。可在数据管理中临时开启后续记录。") } }
                    if (devices.any { d -> d.id == it.deviceId }) Item("打开设备", it.deviceName) { go(Route(Screen.DEVICE, it.deviceId)) }
                }
            }
        }
        Screen.MORE -> Page("YOUR WORKSPACE", "我的", "本地设备工作台 · 无需云端账号") {
            Panel { Text("${devices.size} 台设备 · ${archive.templates.size} 个面板模板"); Text("${archive.events.count { !it.read }} 条未读事件", color = Muted) }
            Item("连接管理", "切页保持前台连接，可随时停止") { go(Route(Screen.SUBSCRIPTIONS)) }
            Item("MQTT 连接配置", "地址、身份验证与 Client ID") { go(Route(Screen.MQTT_PROFILES)) }
            Item("面板模板", "真实面板配置的保存、导入与导出") { go(Route(Screen.PRESETS)) }
            Item("消息偏好", "连接事件记录与系统通知支持范围") { go(Route(Screen.NOTIFICATIONS)) }
            Item("通信记录", "BLE / MQTT 的真实收发记录") { go(Route(Screen.EVENTS, "traffic")) }
            Item("数据管理", "保留期限、诊断负载与清理") { go(Route(Screen.DATA_SETTINGS)) }
            Item("外观", "显示模式与主题色") { go(Route(Screen.SETTINGS)) }
            Item("开发者设置", "协议、组件、绑定与离线试算") { go(Route(Screen.DEVELOPER_SETTINGS)) }
            Item("关于与帮助", "平台、配置兼容性与使用说明") { go(Route(Screen.ABOUT)) }
        }
        Screen.SUBSCRIPTIONS, Screen.SUBSCRIPTION -> Page("CONNECTIONS", "连接管理", "前台切页继续收取，后台策略由平台决定。") {
            if (devices.isEmpty()) Text("还没有设备", color = Muted)
            devices.forEach { d ->
                val panel = workspace.panel(d.id)
                if (panel != null) {
                    val state by remember(panel) { panel.connectionStates() }.collectAsState(panel.state.value.connectionChrome())
                    val label = if (d.panel.transport == PanelTransport.DUAL) state.connectedTransports.joinToString { it.name }.ifEmpty { "未连接" } else if (state.ready) "${d.panel.transport} 已连接" else "未连接"
                    Panel { Text(d.name); Text(label, color = Muted)
                        Row { TextButton(onClick = { go(Route(Screen.DEVICE, d.id)) }) { Text("打开设备 / 连接") }
                            TextButton(enabled = state.ready || state.busy || state.connectingTransports.isNotEmpty(), onClick = { workspace.disconnect(d.id) }) { Text("停止连接") } }
                    }
                }
            }
            Item("MQTT 工作台", "独立调试会话，请在工作台连接或停止") { go(Route(Screen.MQTT)) }
            Text("尚未提供后台持续监测服务与阈值规则；这里管理真实连接。", color = Muted)
        }
        Screen.NOTIFICATIONS -> Page("MESSAGE PREFERENCES", "消息偏好", "本地事件记录已接入；系统通知尚未启用。") {
            Panel { Toggle("连接事件", "记录连接、主动停止与意外断开；错误与保存结果仍记录", archive.recordConnectionEvents, activity::setConnectionEvents) }
            Text("暂不接入 FCM、APNs 或厂商推送。当前不会向系统发送通知，也不会请求通知权限。", color = Muted)
            Text("设备阈值规则及后台常驻监测将在后续阶段提供。", color = Muted)
            Item("查看真实消息", "本地记录与未读状态") { go(Route(Screen.EVENTS)) }
        }
        Screen.DATA_SETTINGS -> {
            val capture by activity.capturePayloads.collectAsState()
            var confirm by remember { mutableStateOf<String?>(null) }
            Page("LOCAL DATA", "数据管理", "事件摘要持久保存；通信负载不写入本地档案。") {
                Panel {
                    Text("事件 ${archive.events.size}/1000 · 临时通信 ${records.size}/500 · 模板 ${archive.templates.size}/20")
                    Text("保留期限（缩短会清理过期事件）")
                    Choices(listOf("7 天", "30 天", "90 天"), "${archive.retentionDays} 天") { confirm = "retention:${it.substringBefore(' ')}" }
                    Toggle("临时采集通信负载", "可能包含隐私，仅当前运行有效；关闭后清除预览，设备配网数据始终隐藏", capture, activity::setCapturePayloads)
                }
                OutlinedButton(onClick = { confirm = "events" }) { Text("清空事件与未读记录") }
                OutlinedButton(onClick = { confirm = "traffic" }) { Text("清空临时通信记录") }
                Text("不会删除设备、连接凭据或面板模板。平台备份与浏览器清理可能影响本地数据。", color = Muted)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = activity::retrySave) { Text("重试保存") } }
                if (saving) Text("正在保存…", color = Muted)
            }
            confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认清理？") }, text = { Text("清理后的记录无法恢复；设备配置保持不变。") },
                confirmButton = { TextButton(onClick = { when { action == "events" -> activity.clearEvents(); action == "traffic" -> activity.clearRecords(); else -> activity.setRetention(action.substringAfter(':').toInt()) }; confirm = null }) { Text("确认") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
        }
        Screen.PRESETS, Screen.PRESET, Screen.TRANSFER -> TemplatePages(route, activity, workspace, go, feedback)
        Screen.API -> LaunchedEffect(Unit) { go(Route(Screen.MQTT_PROFILES)) }
        Screen.ABOUT -> Page("THINGSTALK", "关于与帮助", "协议驱动的本地设备工作台") {
            Panel { KeyValue("平台", getPlatform().name); KeyValue("构建", appBuildLabel()); KeyValue("消息模块", "20261031-read3"); KeyValue("配置", "支持 schemaVersion 1–7；设置面板扩展修订 20")
                KeyValue("连接", if (platformCapabilities().ble) "BLE / MQTT 5 · WS/WSS" else "MQTT 5 · WS/WSS") }
            Section("使用帮助")
            Text("MQTT 已连接只表示 Broker 接受登录。收不到设备状态时，请检查硬件在线状态、topic 中设备名和订阅权限。")
            Text("设备配网先准备访问，完成系统配对后读取。配置已保存不等于 Wi-Fi / MQTT 已连通。")
            Text("切换 App 内页面不再断开设备连接；后台仍可能暂停。没有服务器事件补收或远程推送保证。")
            Item("消息与诊断", "查看实际发生的错误及关联记录") { go(Route(Screen.EVENTS)) }
            Item("开源组件与许可证", "Kotlin / Compose / Ktor / Kable / ktor-mqtt") { go(Route(Screen.LICENSES)) }
        }
        Screen.LICENSES -> Page("OPEN SOURCE", "开源组件", "许可证原文与第三方声明以对应依赖版本的发行包为准。") {
            SelectionContainer { Text("Kotlin、Compose Multiplatform、Ktor、AndroidX：Apache-2.0\nKable：Apache-2.0\nktor-mqtt：Apache-2.0\n\nhttps://github.com/JetBrains/kotlin\nhttps://github.com/JetBrains/compose-multiplatform\nhttps://github.com/ktorio/ktor\nhttps://github.com/JuulLabs/kable\nhttps://github.com/ukemp/ktor-mqtt") }
        }
        else -> Unit
    }
}
