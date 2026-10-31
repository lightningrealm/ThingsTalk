package com.lr.thingstalk.presentation.mock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.navigation.*
import com.lr.thingstalk.presentation.theme.*
import kotlinx.coroutines.delay

@Composable
fun DevicePages(route: Route, session: MockSession, go: (Route) -> Unit, message: (String) -> Unit) {
    val lamp = route.id == "lamp"
    when (route.screen) {
        Screen.DEVICES -> Page("YOUR CONNECTED SPACE", "与设备，轻声对话。", "发现状态，掌控细节。") {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Tag("演示空间"); Text("02 / DEVICES", color = Muted, style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("2", style = MaterialTheme.typography.displaySmall); Text("在线设备", color = Muted) }
                    Column { Text(if (session.eventHandled) "0" else "1", style = MaterialTheme.typography.displaySmall); Text("待处理事件", color = Muted) }
                    Column { Text("稳定", style = MaterialTheme.typography.headlineSmall); Text("模拟连接", color = Muted) }
                }
            }
            Section("我的设备", "命令记录") { go(Route(Screen.COMMANDS)) }
            Choices(listOf("正常", "加载", "空列表", "错误"), session.listMode) { session.listMode = it }
            when (session.listMode) {
                "加载" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在获取模拟设备…", color = Muted) }
                "空列表" -> Panel { Text("这里还很安静"); Text("添加你的第一台设备，开始探索。", color = Muted); Primary("发现附近设备") { go(Route(Screen.NEARBY)) } }
                "错误" -> Panel { Text("暂时无法加载设备"); Text("这是模拟网络错误，你可以尝试重新加载。", color = Muted); Primary("重新加载") { session.listMode = "正常" } }
                else -> {
                    Item("书房储物柜", "在线 · Wi-Fi   /   24.6°C · 52% RH") { go(Route(Screen.DEVICE, "cabinet")) }
                    Item("桌面氛围灯", "在线 · Wi-Fi   /   ${if (session.lightOn) "已开启" else "已关闭"} · ${session.brightness.toInt()}%", "light") { go(Route(Screen.DEVICE, "lamp")) }
                }
            }
            Item("发现更多可能", "通过蓝牙发现附近的设备", "bluetooth") { go(Route(Screen.NEARBY)) }
            Section("空间动态", "全部消息") { go(Route(Screen.EVENTS)) }
            Item("${if (session.eventHandled) "已处理" else "待处理"} · 湿度曾超过阈值", "书房储物柜 · 今日 09:41", "events") { go(Route(Screen.EVENT, "humidity")) }
        }
        Screen.DEVICE -> Page("DEVICE / ${if (lamp) "02" else "01"}", if (lamp) "桌面氛围灯" else "书房储物柜", "在线 · 最近同步于刚刚（模拟）") {
            Panel {
                Tag(if (lamp) "LIGHT · V1" else "CABINET · V1")
                if (lamp) {
                    Toggle("灯光电源", "控制设备开关", session.lightOn) { session.lightOn = it; session.command("${if (it) "开启" else "关闭"}灯光"); message("已更新模拟灯光") }
                    Text("亮度  ${session.brightness.toInt()}%", style = MaterialTheme.typography.headlineSmall)
                    Slider(session.brightness, { session.brightness = it }, valueRange = 0f..100f, onValueChangeFinished = { session.command("设置亮度 ${session.brightness.toInt()}%"); message("已提交模拟亮度命令") })
                    Choices(listOf("阅读", "柔和", "夜灯"), session.lightMode) { session.lightMode = it; message("已切换至${it}模式（模拟）") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("24.6°", style = MaterialTheme.typography.displaySmall); Text("温度 · °C", color = Muted) }
                        Column { Text("52%", style = MaterialTheme.typography.displaySmall); Text("相对湿度", color = Muted) }
                    }
                    HorizontalDivider(color = Line)
                    KeyValue("柜门", "已关闭")
                    KeyValue("告警阈值", "${session.threshold}% RH")
                    var threshold by remember { mutableStateOf(session.threshold) }
                    OutlinedTextField(threshold, { threshold = it }, label = { Text("湿度阈值（30–90%）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedButton(onClick = { val value = threshold.toIntOrNull(); if (value == null || value !in 30..90) message("请输入 30–90 之间的整数") else { session.threshold = value.toString(); session.command("设置湿度阈值"); message("已保存模拟阈值，尚未写入真实设备") } }) { Text("保存阈值") }
                }
            }
            Primary("${if (lamp) "闪灯定位" else "读取一次采样"} · 模拟命令") { val id = session.command(if (lamp) "闪灯定位" else "读取一次采样"); go(Route(Screen.COMMAND, id)) }
            if (!lamp) Panel { Section("今日湿度", "查看趋势") { go(Route(Screen.HISTORY, route.id)) }; Trend() }
            Item("能力描述", "属性 · 动作 · 事件") { go(Route(Screen.DESCRIPTION, route.id)) }
            Item("事件与订阅", "配置告警规则和通知偏好", "events") { go(Route(Screen.SUBSCRIPTION, route.id)) }
            Item("场景预设", if (lamp) "灯光控制" else session.appliedPreset) { go(Route(Screen.PRESETS)) }
        }
        Screen.DESCRIPTION -> Page("DEVICE SCHEMA", "能力描述", "${if (lamp) "light.v1" else "cabinet.v1"} · schemaVersion 1.0 · 模拟协议") {
            Panel { Text("属性", fontWeight = FontWeight.SemiBold); if (lamp) { KeyValue("power", "布尔 · 可读写"); KeyValue("brightness", "整数 · 0–100 · 可读写"); KeyValue("mode", "枚举 · 阅读 / 柔和 / 夜灯") } else { KeyValue("temperature", "数值 · °C · 只读"); KeyValue("humidity", "数值 · % RH · 只读"); KeyValue("doorOpen", "布尔 · 只读"); KeyValue("threshold", "整数 · 30–90 · 可读写") } }
            Panel { Text("动作", fontWeight = FontWeight.SemiBold); KeyValue(if (lamp) "identify" else "sample", if (lamp) "闪灯定位" else "获取一次采样") }
            Panel { Text("事件", fontWeight = FontWeight.SemiBold); KeyValue(if (lamp) "fault" else "humidityHigh", if (lamp) "设备故障" else "湿度持续超限"); KeyValue("deviceId", if (lamp) "demo-lamp-01" else "demo-cabinet-01") }
        }
        Screen.HISTORY -> Page("INSIGHTS", "每一点变化，都有迹可循。", "书房储物柜 · 模拟历史数据") {
            var range by remember { mutableStateOf("今天") }
            Choices(listOf("今天", "7 天", "30 天"), range) { range = it }
            Panel { Text("湿度趋势 / $range"); Trend(); KeyValue("平均", "51.8% RH"); KeyValue("最高", "68.2% RH"); KeyValue("最低", "45.0% RH") }
            Text("演示曲线：切换时间范围仅展示布局，不查询历史服务。", color = Muted)
        }
        Screen.COMMANDS -> Page("COMMAND CENTER", "每条指令，有始有终。", "受理不等于执行成功。这里记录完整的命令旅程。") {
            session.commands.forEach { command -> Item(command.name, "${command.id} · ${command.status}") { go(Route(Screen.COMMAND, command.id)) } }
        }
        Screen.COMMAND -> {
            val command = session.commands.find { it.id == route.id }
            Page("COMMAND TRACE", command?.name ?: "命令已过期", route.id) {
                if (command == null) Text("演示会话已重置，请重新提交模拟命令。") else {
                    Panel { Tag(command.status); KeyValue("来源", "本地模拟"); KeyValue("关联标识", command.id); KeyValue("幂等键", "demo-${command.id}"); Text("01  已受理\n\n02  ${if (command.status == "排队中") "等待发送" else "已发送至设备"}\n\n03  ${command.status}") }
                    if (command.status == "排队中" || command.status == "已发送") {
                        Toggle("模拟超时", "用于体验结果未知的情况", session.nextCommandFails) { session.nextCommandFails = it }
                        Primary("推进模拟执行") { session.advanceCommand(command.id) }
                    }
                    if (command.status == "执行超时") Text("超时表示结果未知，请查询设备状态；不会自动重试有副作用的命令。", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        else -> Unit
    }
}
