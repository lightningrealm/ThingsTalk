package com.lr.thingstalk.domain.ble

/** Versioned chunk/ack wire format; UUIDs and form fields are supplied by a setup panel. */
object ChunkedBleSettingsProtocol {
    fun packet(op: Int, request: Int, field: Int = 0, offset: Int = 0, payload: ByteArray = byteArrayOf()): ByteArray {
        require(op in 0xB1..0xB4 && request in 0..65535 && field in 0..6 && offset in 0..65535 && payload.size <= 14)
        return byteArrayOf(op.toByte(), request.toByte(), (request ushr 8).toByte(), field.toByte(), offset.toByte(), (offset ushr 8).toByte()) + payload
    }
    fun parse(bytes: ByteArray): NetworkSetupStatus {
        require(bytes.size == 14 && bytes[0] == 0xD1.toByte() && bytes[1] == 1.toByte()) { "设备配网协议版本不兼容" }
        fun u(i: Int) = bytes[i].toInt() and 255
        require(u(2) and 128 == 0 && u(6) in 0..6)
        return NetworkSetupStatus(u(2), u(3) or (u(4) shl 8), u(5), u(6), u(7), u(8) or (u(9) shl 8),
            (10..13).fold(0L) { result, i -> result or (u(i).toLong() shl ((i - 10) * 8)) })
    }
}

data class NetworkSetupStatus(val flags: Int, val request: Int, val opcode: Int, val result: Int, val field: Int, val offset: Int, val revision: Long) {
    val unlocked get() = flags and 1 != 0
    val wifiConnected get() = flags and 2 != 0
    val mqttConnected get() = flags and 4 != 0
    val wifiSaved get() = flags and 8 != 0
    val mqttSaved get() = flags and 16 != 0
    val wifiPasswordSaved get() = flags and 32 != 0
    val mqttPasswordSaved get() = flags and 64 != 0
}
