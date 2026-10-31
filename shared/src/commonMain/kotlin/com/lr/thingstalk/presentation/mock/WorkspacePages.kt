package com.lr.thingstalk.presentation.mock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.navigation.*
import com.lr.thingstalk.presentation.theme.*
import kotlinx.serialization.json.*

@Composable
fun WorkspacePages(route: Route, session: MockSession, go: (Route) -> Unit, message: (String) -> Unit) {
    when (route.screen) {
        Screen.EVENTS -> Page("INBOX", "重要的事，不会错过。", "设备事件先保存，再通知。当前展示模拟消息。") {
            var filter by remember { mutableStateOf("全部") }
            Choices(listOf("全部", "未读", "待处理"), filter) { filter = it }
            Section("今日", "订阅管理") { go(Route(Screen.SUBSCRIPTIONS)) }
            if (filter == "全部" || (filter == "未读" && !session.eventRead) || (filter == "待处理" && !session.eventHandled))
                Item("湿度持续超限", "书房储物柜 · 09:41 · ${if (session.eventHandled) "已处理" else "待处理"}", "events") { session.eventRead = true; go(Route(Screen.EVENT, "humidity")) }
            else Panel { Text("所有消息都已跟进"); Text("有新的设备动态时，会出现在这里。", color = Muted) }
            if (filter == "全部") Item("设备恢复在线", "桌面氛围灯 · 08:30 · 已读", "events") { go(Route(Screen.EVENT, "online")) }
            OutlinedButton(onClick = { message("模拟同步完成 · 游标 demo-002 · 无新增事件") }) { Text("同步缺失事件") }
        }
        Screen.EVENT -> Page("EVENT DETAIL", if (route.id == "online") "设备恢复在线" else "湿度持续超限", "今日 ${if (route.id == "online") "08:30" else "09:41"} · 演示事件") {
            val online = route.id == "online"
            Panel { Tag(if (online || session.eventHandled) "已处理" else "待处理"); Text(if (online) "桌面氛围灯已恢复连接。" else "储物柜湿度曾达到 68.2%，持续超过设定阈值 5 分钟。当前湿度已恢复至 52%。"); KeyValue("设备", if (online) "桌面氛围灯" else "书房储物柜"); KeyValue("事件 ID", "demo-${route.id}"); KeyValue("消息状态", if (online || session.eventRead) "已读" else "未读"); KeyValue("服务端保存", "模拟已保存") }
            if (!online) { Primary(if (session.eventHandled) "已确认处理" else "确认处理", !session.eventHandled) { session.eventHandled = true; session.eventRead = true; message("模拟事件已确认处理") }; OutlinedButton(onClick = { session.eventRead = !session.eventRead }) { Text(if (session.eventRead) "标记未读" else "标记已读") } }
            Item("查看演示设备", "查看此示例事件的设备面板") { go(Route(Screen.DEMO_DEVICE, if (online) "lamp" else "cabinet")) }
        }
        Screen.SUBSCRIPTIONS -> Page("SUBSCRIPTIONS", "只关心，值得关心的。", "业务订阅独立于设备连接存在。") {
            Item("储物柜告警", "湿度超限、柜门打开 · ${if (session.subscription) "已启用" else "已暂停"}", "events") { go(Route(Screen.SUBSCRIPTION, "cabinet")) }
            Primary("配置业务订阅") { go(Route(Screen.SUBSCRIPTION, "cabinet")) }
        }
        Screen.SUBSCRIPTION -> Page("SUBSCRIPTION", "告警订阅", "书房储物柜 · 模拟订阅配置") {
            Panel { Toggle("启用订阅", "模拟服务器持久保存", session.subscription) { session.subscription = it }; HorizontalDivider(color = Line); Toggle("湿度超限", "持续 5 分钟触发", session.humiditySubscription) { session.humiditySubscription = it }; Toggle("柜门打开", "每次开门生成事件", session.doorSubscription) { session.doorSubscription = it }; Toggle("系统通知", "演示不会向系统发送通知", session.pushEnabled) { session.pushEnabled = it } }
            Primary("保存模拟订阅") { message("订阅偏好已在当前演示中保存") }
            Text("告警规则由设备或服务端执行，不依赖手机后台保活。", color = Muted)
        }
        Screen.MORE -> Page("YOUR WORKSPACE", "让连接，回归简单。", "ThinsTalk · 协议驱动的设备工作台") {
            Panel { Tag("DEMO MODE"); Text("一处空间，无限可能。", style = MaterialTheme.typography.headlineSmall); Text("用同一种语言，理解不同的设备。", color = Muted) }
            Item("场景预设", "储物柜监测 · 灯光控制") { go(Route(Screen.PRESETS)) }
            Item("开发者设置", "定义设备报文、组件绑定与配置文件") { go(Route(Screen.DEVELOPER_SETTINGS)) }
            Item("服务连接", "API 地址与连接检查") { go(Route(Screen.API)) }
            Item("通知设置", "通道状态、偏好与模拟通知", "events") { go(Route(Screen.NOTIFICATIONS)) }
            Item("通信日志", "查看我的设备的真实 BLE 收发记录") { go(Route(Screen.BLE_LOG_DEVICES)) }
            Item("应用设置", "浅色、深色与主题配色", "more") { go(Route(Screen.SETTINGS)) }
            Item("关于 ThinsTalk", "版本 1.0 · 毕设预览", "more") { go(Route(Screen.ABOUT)) }
        }
        Screen.PRESETS -> Page("SCENE LIBRARY", "从能力，到场景。", "预设定义展示方式，设备决定实际能力。") {
            Item("储物柜监测", "温湿度 · 柜门 · 告警阈值") { go(Route(Screen.PRESET, "cabinet")) }
            Item("灯光控制", "开关 · 亮度 · 场景模式", "light") { go(Route(Screen.PRESET, "lamp")) }
            Item("导入与导出", "查看、校验预设 JSON") { go(Route(Screen.TRANSFER)) }
        }
        Screen.PRESET -> Page("SCENE PRESET", if (route.id == "lamp") "灯光控制" else "储物柜监测", "预设版本 1.0 · 内置模拟模板") {
            Panel { KeyValue("适用模型", if (route.id == "lamp") "light.v1" else "cabinet.v1"); KeyValue("控件", if (route.id == "lamp") "开关、滑块、枚举、按钮" else "数值、状态、输入、曲线"); KeyValue("兼容性", "与示例设备匹配"); Text("预设不会新增固件能力，也不会覆盖设备的只读和参数范围限制。", color = Muted) }
            Primary("应用到示例设备") { if (route.id != "lamp") session.appliedPreset = "储物柜监测"; message("已应用模拟预设"); go(Route(Screen.DEMO_DEVICE, route.id)) }
            OutlinedButton(onClick = { go(Route(Screen.TRANSFER, route.id)) }) { Text("查看导出内容") }
        }
        Screen.TRANSFER -> Page("PRESET EXCHANGE", "分享你的场景。", "粘贴 JSON 校验，或选择文本复制导出。仅演示支持的预设格式。") {
            val presetModel = if (route.id == "lamp") "light.v1" else "cabinet.v1"
            val presetName = if (route.id == "lamp") "灯光控制" else session.appliedPreset
            var json by remember(route.id) { mutableStateOf(buildJsonObject { put("schemaVersion", 1); put("name", presetName); put("model", presetModel) }.toString()) }
            OutlinedTextField(json, { json = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("预设 JSON") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Primary("校验并模拟导入") {
                val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                val version = (obj?.get("schemaVersion") as? JsonPrimitive)?.intOrNull
                val name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull
                val model = (obj?.get("model") as? JsonPrimitive)?.contentOrNull
                if (version != 1 || name.isNullOrBlank() || model !in listOf("cabinet.v1", "light.v1")) message("格式不匹配：需要版本 1、非空名称与 cabinet.v1 / light.v1 模型")
                else { if (model == "cabinet.v1") session.appliedPreset = name; message("模拟预设已导入：$name") }
            }
            OutlinedButton(onClick = { json = buildJsonObject { put("schemaVersion", 1); put("name", presetName); put("model", presetModel) }.toString(); message("导出内容已生成，可长按文本复制") }) { Text("生成当前预设 JSON") }
        }
        Screen.API -> Page("SERVICE CONNECTION", "连接你的服务。", "当前仅保存演示配置，不发起真实网络请求。") {
            var url by remember { mutableStateOf(session.apiUrl) }
            Panel { Tag("MOCK API"); OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true); KeyValue("鉴权", "未配置 · 演示无需凭证"); KeyValue("WebSocket", "模拟连接"); Primary("保存演示地址") { if (!url.startsWith("https://") || url.removePrefix("https://").isBlank()) message("请输入有效的 HTTPS 地址") else { session.apiUrl = url; message("已保存至当前演示会话") } }; OutlinedButton(onClick = { message("模拟检查完成 · 示例延迟 42ms（未访问服务器）") }) { Text("模拟连接检查") } }
        }
        Screen.NOTIFICATIONS -> Page("NOTIFICATION PREFERENCES", "适时提醒，不过度打扰。", "系统通知通道尚未接入，所有状态均为演示。") {
            Panel { Toggle("告警通知", "只接收重要设备事件", session.pushEnabled) { session.pushEnabled = it }; KeyValue("Android 推送", "待配置厂商通道 / FCM"); KeyValue("iOS 推送", "待配置 APNs"); KeyValue("安装实例 token", "未注册") }
            Primary("模拟点击告警通知") { session.eventRead = true; go(Route(Screen.EVENT, "humidity")) }
            Text("模拟跳转不会触发系统通知或请求通知权限。", color = Muted)
        }
        Screen.ABOUT -> Page("THINSTALK", "设备有话说。", "协议驱动的跨平台设备交互工具") {
            Panel { KeyValue("版本", "1.0 · Mock Preview"); KeyValue("平台", "Android / iOS"); KeyValue("界面", "Compose Multiplatform"); KeyValue("导航", "Navigation 3"); Text("通过属性、动作与事件，让不同设备拥有一致而清晰的交互方式。", color = Muted) }
            Text("本版本用于功能与视觉评审，不代表真实设备连接、服务器告警或推送服务已上线。", color = Muted)
        }
        else -> Unit
    }
}
