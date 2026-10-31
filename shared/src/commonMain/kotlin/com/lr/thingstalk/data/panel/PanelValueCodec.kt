package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.*
import kotlin.math.abs
import kotlin.math.round

/** A single field inside a BLE frame. Never truncates integers or silently splits writes. */
internal object PanelValueCodec {
    private val decimalNumber = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    fun decode(schemaVersion: Int, capability: PanelCapability, operation: BleOperationBinding, bytes: ByteArray): String {
        val width = if (operation.codec == CodecType.BOOLEAN) hex(operation.falseHex, 1, 32).size else operation.codec.byteWidth()
        require(width > 0) { "固定 HEX 动作不支持读取" }
        if (schemaVersion == 1) require(bytes.size == 1) { "v1 通知 / 读取必须恰好一个字节" }
        val offset = operation.readOffset
        require(offset >= 0 && offset <= bytes.size - width) { "报文长度不足，无法读取偏移 $offset 的 $width 字节数据" }
        val field = bytes.copyOfRange(offset, offset + width)
        if (operation.codec == CodecType.BOOLEAN) {
            return when {
                field.contentEquals(hex(operation.falseHex, 1, 32)) -> "false"
                field.contentEquals(hex(operation.trueHex, 1, 32)) -> "true"
                else -> throw IllegalArgumentException("布尔数据与配置的真值 / 假值不匹配")
            }
        }
        var unsigned = 0L
        field.indices.forEach { index ->
            val shift = (if (operation.byteOrder == PanelByteOrder.LITTLE_ENDIAN) index else field.lastIndex - index) * 8
            unsigned = unsigned or ((field[index].toLong() and 0xffL) shl shift)
        }
        val raw = when (operation.codec) {
            CodecType.INT8, CodecType.INT16, CodecType.INT32 -> {
                val bits = width * 8
                (if (unsigned >= (1L shl (bits - 1))) unsigned - (1L shl bits) else unsigned).toDouble()
            }
            CodecType.FLOAT32 -> Float.fromBits(unsigned.toInt()).toDouble()
            else -> unsigned.toDouble()
        }
        require(raw.isFinite()) { "设备返回了非有限数值" }
        val value = raw * operation.scale + operation.bias
        checkRange(value, capability)
        return format(value)
    }

    fun encode(schemaVersion: Int, capability: PanelCapability, operation: BleOperationBinding, value: String): ByteArray {
        val payload = when (operation.codec) {
            CodecType.UTF8 -> {
                panelTextProblem(value, capability.maxBytes)?.let { throw IllegalArgumentException(it) }
                value.encodeToByteArray(throwOnInvalidSequence = true)
            }
            CodecType.HEX -> hex(value, 1, 512)
            CodecType.BOOLEAN -> when (value) {
                "true" -> hex(operation.trueHex, 1, 32)
                "false" -> hex(operation.falseHex, 1, 32)
                else -> throw IllegalArgumentException("布尔值仅接受 true 或 false")
            }
            else -> {
                if (schemaVersion == 1) require(Regex("[0-9]{1,3}").matches(value)) { "UINT8 需要 0–255 整数" }
                if (schemaVersion != 1) require(decimalNumber.matches(value)) { "数值仅支持十进制或科学计数法，不能包含空白" }
                val engineering = requireNotNull(value.toDoubleOrNull()) { "请输入有效数值" }
                val nonzeroSignificand = value.substringBefore('e').substringBefore('E').any { it in '1'..'9' }
                require(engineering != 0.0 || !nonzeroSignificand) { "输入数值太小，无法可靠表示" }
                checkRange(engineering, capability)
                require(operation.scale.isFinite() && operation.scale != 0.0 && operation.bias.isFinite()) { "数值换算参数无效" }
                val delta = engineering - operation.bias
                val raw = delta / operation.scale
                require(raw.isFinite() && (raw != 0.0 || delta == 0.0)) { "反向换算后数值必须有限且不能下溢为零" }
                val bits = if (operation.codec == CodecType.FLOAT32) {
                    val number = raw.toFloat()
                    require(number.isFinite() && (number != 0f || raw == 0.0)) { "数值超出 FLOAT32 的可表示范围" }
                    require((number.toDouble() * operation.scale + operation.bias).isFinite()) { "FLOAT32 量化后的工程值不能溢出" }
                    number.toBits().toLong() and 0xffff_ffffL
                } else {
                    val rounded = round(raw)
                    // Allow only a few Double arithmetic ULPs from scale/bias inversion, never normal rounding.
                    val tolerance = abs(raw) * 4.0 * 2.220446049250313e-16
                    require((rounded != 0.0 || raw == 0.0) && abs(raw - rounded) <= tolerance) { "换算后不能用 ${operation.codec} 精确表示，请调整输入或比例" }
                    val (minimum, maximum) = when (operation.codec) {
                        CodecType.UINT8 -> 0.0 to 255.0
                        CodecType.INT8 -> -128.0 to 127.0
                        CodecType.UINT16 -> 0.0 to 65535.0
                        CodecType.INT16 -> -32768.0 to 32767.0
                        CodecType.UINT32 -> 0.0 to 4294967295.0
                        CodecType.INT32 -> -2147483648.0 to 2147483647.0
                        else -> error("非数值编码")
                    }
                    require(rounded in minimum..maximum) { "换算后数值超出 ${operation.codec} 范围" }
                    rounded.toLong()
                }
                val width = operation.codec.byteWidth()
                ByteArray(width) { index ->
                    val shift = (if (operation.byteOrder == PanelByteOrder.LITTLE_ENDIAN) index else width - 1 - index) * 8
                    ((bits ushr shift) and 0xffL).toByte()
                }
            }
        }
        val result = hex(operation.writePrefixHex, 0, 512) + payload + hex(operation.writeSuffixHex, 0, 512)
        require(result.size in 1..512) { "完整写入帧需要 1–512 字节" }
        return result
    }

    private fun hex(text: String, minimum: Int, maximum: Int): ByteArray {
        val compact = text.compactPanelHex()
        require(isPanelHex(compact, minimum, maximum)) { "HEX 需要 $minimum–$maximum 字节的完整数据" }
        return compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun checkRange(value: Double, capability: PanelCapability) {
        require(value.isFinite() && value >= (capability.minimum ?: 0.0) && value <= (capability.maximum ?: 255.0)) {
            "数值超出 ${capability.label} 的允许范围或不是有限数值"
        }
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0 && abs(value) <= 9_007_199_254_740_991.0) value.toLong().toString() else value.toString()
}
