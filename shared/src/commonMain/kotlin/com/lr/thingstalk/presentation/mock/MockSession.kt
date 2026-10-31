package com.lr.thingstalk.presentation.mock

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel

/** Presentation-only demo state. Never calls API/BLE or represents a real device operation. */
class MockSession : ViewModel() {
    var lightOn by mutableStateOf(true)
    var brightness by mutableStateOf(65f)
    var threshold by mutableStateOf("65")
    var lightMode by mutableStateOf("阅读")
    var connectedDevice by mutableStateOf("")
    var notificationTarget by mutableStateOf("report")
    var humiditySubscription by mutableStateOf(true)
    var doorSubscription by mutableStateOf(true)
    var connected by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var scanFinished by mutableStateOf(false)
    var observing by mutableStateOf(false)
    var notificationCount by mutableStateOf(0)
    var lastValue by mutableStateOf("01 18 32")
    var subscription by mutableStateOf(true)
    var pushEnabled by mutableStateOf(true)
    var eventRead by mutableStateOf(false)
    var eventHandled by mutableStateOf(false)
    var listMode by mutableStateOf("正常")
    var apiUrl by mutableStateOf("https://api.example.com")
    var appliedPreset by mutableStateOf("储物柜监测")
    var nextCommandFails by mutableStateOf(false)
    val commands = mutableStateListOf(MockCommand("CMD-001", "读取一次采样", "执行成功"))
    val logs = mutableStateListOf("[DEMO] 调试会话已准备")
    fun log(message: String) {
        logs.add(0, "[DEMO] $message")
        if (logs.size > 100) logs.removeAt(logs.lastIndex)
    }
    fun command(name: String): String {
        val id = "CMD-${(commands.size + 1).toString().padStart(3, '0')}"
        commands.add(0, MockCommand(id, name, "排队中"))
        return id
    }
    fun advanceCommand(id: String) {
        val index = commands.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = commands[index]
        val status = when (item.status) {
            "排队中" -> "已发送"
            "已发送" -> if (nextCommandFails) "执行超时" else "执行成功"
            else -> item.status
        }
        commands[index] = item.copy(status = status)
    }
    fun disconnect() { connected = false; observing = false; log("连接已断开") }
}

data class MockCommand(val id: String, val name: String, val status: String)
