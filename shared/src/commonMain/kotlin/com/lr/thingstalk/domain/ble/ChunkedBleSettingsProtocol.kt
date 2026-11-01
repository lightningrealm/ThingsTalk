package com.lr.thingstalk.domain.ble

/** Client-defined V2 transaction format, independent of network/AI field semantics. */
object ChunkedBleSettingsProtocol {
    fun packet(op: Int, request: Int, field: Int = 0, offset: Int = 0, payload: ByteArray = byteArrayOf()): ByteArray {
        require(op in 0xB1..0xB4 && request in 0..65535 && field in 0..6 && offset in 0..65535 && payload.size <= 14)
        return byteArrayOf(op.toByte(), request.toByte(), (request ushr 8).toByte(), field.toByte(), offset.toByte(), (offset ushr 8).toByte()) + payload
    }

    fun parse(bytes: ByteArray): SetupReceipt {
        require(bytes.size == 16 && bytes[0] == 0xD1.toByte() && bytes[1] == 2.toByte()) { "设备设置协议版本不兼容" }
        fun u(i: Int) = bytes[i].toInt() and 255
        require(u(2) and 0xFE == 0 && u(6) in 0..6 && u(7) in 0..6)
        require(u(14) and 0xFC == 0 && u(15) and 0xC0 == 0)
        require(u(5) == 0 || u(5) in 0xB1..0xB4)
        val state = SetupState(u(2) and 1 != 0, ids(u(14), 2), ids(u(15), 6))
        return SetupReceipt(u(3) or (u(4) shl 8), u(5), u(6), u(7), u(8) or (u(9) shl 8),
            (10..13).fold(0L) { result, i -> result or (u(i).toLong() shl ((i - 10) * 8)) }, state)
    }

    /** Legacy flags are interpreted only here; callers select V1 explicitly, never by sniffing. */
    fun parseLegacy(bytes: ByteArray, ai: Boolean): SetupReceipt {
        require(bytes.size == 14 && bytes[0] == 0xD1.toByte() && bytes[1] == 1.toByte())
        fun u(i: Int) = bytes[i].toInt() and 255
        require(u(2) and 128 == 0 && u(6) in 0..6)
        val flags = u(2)
        val state = SetupState(flags and 1 != 0,
            buildSet { if (flags and 8 != 0) add(1); if (!ai && flags and 16 != 0) add(2) },
            buildSet { if (flags and 32 != 0) add(2); if (!ai && flags and 64 != 0) add(5) })
        return SetupReceipt(u(3) or (u(4) shl 8), u(5), u(6), u(7), u(8) or (u(9) shl 8),
            (10..13).fold(0L) { result, i -> result or (u(i).toLong() shl ((i - 10) * 8)) }, state)
    }

    private fun ids(mask: Int, count: Int): Set<Int> = (1..count).filter { mask and (1 shl (it - 1)) != 0 }.toSet()
}

/** Bit n always refers to section/field ID n+1, regardless of settings type. */
data class SetupState(val unlocked: Boolean, val savedSectionIds: Set<Int>, val savedSecretFieldIds: Set<Int>)

/** Transaction metadata stays out of the form state; contains no credential values. */
data class SetupReceipt(val request: Int, val opcode: Int, val result: Int, val field: Int,
    val offset: Int, val revision: Long, val state: SetupState)
