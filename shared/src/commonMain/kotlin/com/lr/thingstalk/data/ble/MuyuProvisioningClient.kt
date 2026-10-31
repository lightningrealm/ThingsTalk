package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/** No credential persistence, traffic logging, reconnect or automatic replay. */
class MuyuProvisioningClient(private val connection: BleConnection) {
    private val mutex = Mutex()
    private var sequence = Random.nextInt(1, 65535)
    suspend fun status(): NetworkSetupStatus = mutex.withLock { withTimeout(5_000) { readStatus() } }
    private suspend fun readStatus() = MuyuProvisioning.parse(connection.read(MuyuProvisioning.status))
    suspend fun values(): NetworkSetupValues = mutex.withLock {
        withTimeout(30_000) {
            require(readStatus().unlocked) { "请先长按设备 UP 打开设置窗口" }
            suspend fun field(id: Int) = connection.read(MuyuProvisioning.fields.getValue(id)).decodeToString(throwOnInvalidSequence = true)
            NetworkSetupValues(field(1), field(3), field(4), field(6))
        }
    }
    suspend fun saveWifi(ssid: String, password: String?): NetworkSetupStatus {
        require(ssid.encodeToByteArray().size in 1..32 && '\u0000' !in ssid) { "Wi-Fi 名称需要 1–32 字节" }
        password?.let { require(it.isEmpty() || it.encodeToByteArray().size in 8..63 || (it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" })) { "Wi-Fi 密码长度无效" } }
        return save(1, buildMap { put(1, ssid); if (password != null) put(2, password) })
    }
    suspend fun saveMqtt(uri: String, username: String, password: String?, device: String): NetworkSetupStatus {
        require(uri.encodeToByteArray().size in 1..192 && listOf("ws://", "wss://", "mqtt://", "mqtts://").any(uri::startsWith)) { "请输入完整的 WS/WSS 或 MQTT 地址" }
        require(username.encodeToByteArray().size <= 64 && (password?.encodeToByteArray()?.size ?: 0) <= 128) { "账号或密码过长" }
        require(Regex("[A-Za-z0-9_-]{1,32}").matches(device)) { "设备名使用 1–32 位字母、数字、下划线或连字符" }
        return save(2, buildMap { put(3, uri); put(4, username); if (password != null) put(5, password); put(6, device) })
    }
    private suspend fun save(section: Int, fields: Map<Int, String>): NetworkSetupStatus = mutex.withLock {
        require(fields.values.none { '\u0000' in it }) { "字段不能包含 NUL" }
        withTimeout(90_000) {
            val initial = readStatus()
            require(initial.unlocked) { "请先长按设备 UP 打开设置窗口" }
            sequence = initial.request // Next ID cannot match the device's last acknowledgement.
            val size = minOf(14, connection.maximumWriteLength(BleWriteMode.WITH_RESPONSE) - 6)
            require(size > 0) { "当前 BLE 写入长度不足" }
            exchange(0xB1, section)
            for ((field, text) in fields) {
                val bytes = text.encodeToByteArray()
                try {
                    if (bytes.isEmpty()) exchange(0xB2, field)
                    var offset = 0
                    while (offset < bytes.size) {
                        val chunk = bytes.copyOfRange(offset, minOf(offset + size, bytes.size))
                        try { exchange(0xB2, field, offset, chunk) } finally { chunk.fill(0) }
                        offset += chunk.size
                    }
                } finally { bytes.fill(0) }
            }
            exchange(0xB3)
        }
    }
    private suspend fun exchange(op: Int, field: Int = 0, offset: Int = 0, payload: ByteArray = byteArrayOf()): NetworkSetupStatus {
        sequence = (sequence + 1) and 65535
        val expected = sequence
        val packet = MuyuProvisioning.packet(op, expected, field, offset, payload)
        try { withTimeout(30_000) { connection.write(MuyuProvisioning.command, packet, BleWriteMode.WITH_RESPONSE) } }
        finally { packet.fill(0) }
        return withTimeout(15_000) {
            var result: NetworkSetupStatus
            while (true) {
                result = readStatus()
                if (result.request == expected && result.opcode == op) break
                delay(75)
            }
            val expectedCode = if (op == 0xB3) 2 else 1
            check(result.result == expectedCode) { when (result.result) {
                3 -> "设备拒绝了配置，请检查地址、密码和字段长度"
                4 -> "设备设置窗口已关闭，请长按 UP 后重试"
                5 -> "配置会话已失效或被其他配置覆盖，请重新保存"
                6 -> "设备存储失败，配置未确认保存"
                else -> "设备未确认配置，请重新读取状态"
            } }
            check(result.field == field && result.offset == offset + if (op == 0xB2) payload.size else 0) { "设备配置回执不匹配" }
            result
        }
    }
}
