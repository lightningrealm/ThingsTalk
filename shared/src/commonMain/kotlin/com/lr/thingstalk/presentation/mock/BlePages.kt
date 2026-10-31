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
import kotlinx.coroutines.delay

@Composable
fun BlePages(route: Route, session: MockSession, go: (Route) -> Unit, message: (String) -> Unit) {
    when (route.screen) {
        Screen.NEARBY -> Page("NEARBY / BLUETOOTH", "发现身边的设备。", "安全地靠近，轻松地连接。") {
            Panel { Tag("模拟蓝牙环境"); Text("准备好探索了吗？", style = MaterialTheme.typography.headlineSmall); Text("本演示不会调用系统蓝牙，也不会请求真实权限。", color = Muted); Primary(if (session.scanning) "停止扫描" else "开始模拟扫描") { session.scanning = !session.scanning } }
            if (session.scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在发现附近的模拟设备…") }
            if (session.scanFinished) {
                Section("发现 2 台设备")
                Item("ThinsTalk Cabinet", "−48 dBm · 信号良好 · ESP32-C3", "bluetooth") { go(Route(Screen.BLE, "cabinet")) }
                Item("ThinsTalk Light", "−62 dBm · 信号良好 · ESP32-C3", "light") { go(Route(Screen.BLE, "lamp")) }
            } else Text("扫描结果会显示在这里", color = Muted)
            Section("连接前检查")
            Panel { KeyValue("蓝牙", "模拟已开启"); KeyValue("附近设备权限", "演示无需授权"); KeyValue("连接数量", "仅支持一个活动连接") }
        }
        Screen.BLE -> Page("BLE WORKSPACE", if (route.id == "lamp") "ThinsTalk Light" else "ThinsTalk Cabinet", "GATT 调试工作台 · 模拟外设") {
            Panel { Tag(if (session.connected && session.connectedDevice == route.id) "已连接（模拟）" else "未连接"); KeyValue("信号", "−48 dBm"); KeyValue("ATT MTU", "23 bytes"); KeyValue("单次写入上限", "20 bytes"); Primary(if (session.connected && session.connectedDevice == route.id) "断开模拟连接" else "连接模拟设备") { if (session.connected && session.connectedDevice == route.id) session.disconnect() else { session.observing = false; session.connectedDevice = route.id; session.connected = true; session.log("连接成功，已发现服务") } } }
            Item("服务与特征", "2 个服务 · 3 个特征 · 2 个描述符", "bluetooth") { if (session.connected && session.connectedDevice == route.id) go(Route(Screen.SERVICES, route.id)) else message("请先连接模拟设备") }
            Item("通信日志", "读写与通知的完整记录", "events") { go(Route(Screen.LOGS)) }
        }
        Screen.SERVICES -> Page("GATT EXPLORER", "服务与特征", "模拟服务树 · UUID 使用标准 Bluetooth Base UUID 展开") {
            Panel { Tag("180F · Battery Service"); Item("2A19 · Battery Level", "Read · Notify   /   描述符 2902") { go(Route(Screen.CHARACTERISTIC, "battery")) } }
            Panel { Tag("FFE0 · Device Service"); Item("FFE1 · Command", "Write · Write without response") { go(Route(Screen.CHARACTERISTIC, "command")) }; Item("FFE2 · Report", "Read · Notify   /   描述符 2902") { go(Route(Screen.CHARACTERISTIC, "report")) } }
        }
        Screen.CHARACTERISTIC -> Page("CHARACTERISTIC", when (route.id) { "command" -> "Command"; "battery" -> "Battery Level"; else -> "Report" }, "模拟 GATT 操作，不会发送到真实外设") {
            val writable = route.id == "command"
            val uuid = when (route.id) { "command" -> "ffe1"; "battery" -> "2a19"; else -> "ffe2" }
            Panel { Text("0000$uuid-0000-1000-8000-00805f9b34fb", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace); Tag(if (writable) "WRITE / WRITE NO RESPONSE" else "READ / NOTIFY"); KeyValue("连接状态", if (session.connected) "模拟已连接" else "未连接") }
            if (writable) {
                var input by remember { mutableStateOf("01 00 FF") }; var mode by remember { mutableStateOf("有响应") }
                Panel {
                    OutlinedTextField(input, { input = it }, label = { Text("HEX 数据") }, modifier = Modifier.fillMaxWidth())
                    Choices(listOf("有响应", "无响应"), mode) { mode = it }
                    Primary("写入特征", session.connected) {
                        val bytes = input.trim().split(Regex("\\s+"))
                        if (bytes.size > 20 || bytes.any { !it.matches(Regex("[0-9a-fA-F]{2}")) }) message("请输入 1–20 字节 HEX，例如 01 00 FF")
                        else { session.log("TX $mode ${input.uppercase()}"); message("模拟写入完成 · ${bytes.size} bytes") }
                    }
                }
            } else {
                Panel { Text(session.lastValue, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall); Primary("读取特征", session.connected) { session.lastValue = if (route.id == "battery") "64" else "01 18 34"; session.log("RX read ${session.lastValue}"); message("已读取模拟数据") }; Toggle("接收通知", "已接收 ${session.notificationCount} 条模拟数据", session.observing && session.notificationTarget == route.id) { if (session.connected) { session.notificationTarget = route.id; session.observing = it } else message("请先连接模拟设备") } }
                Panel { Section("描述符"); Text("2902 · Client Characteristic Configuration"); KeyValue("模拟值", if (session.observing) "01 00 · 已启用" else "00 00 · 已关闭"); Text("通知开关管理 CCCD，无需手动写入。", color = Muted) }
            }
            Item("查看通信日志", "按接收时间倒序排列", "events") { go(Route(Screen.LOGS)) }
        }
        Screen.LOGS -> Page("COMMUNICATION LOG", "每一次收发，都可追溯。", "仅当前演示会话 · 最多保留 100 条") {
            var filter by remember { mutableStateOf("全部") }
            Choices(listOf("全部", "RX", "TX"), filter) { filter = it }
            OutlinedButton(onClick = { session.logs.clear(); message("日志已清空") }) { Text("清空日志") }
            Panel { val logs = session.logs.filter { filter == "全部" || filter in it }; if (logs.isEmpty()) Text("暂无日志", color = Muted); logs.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        }
        else -> Unit
    }
}
