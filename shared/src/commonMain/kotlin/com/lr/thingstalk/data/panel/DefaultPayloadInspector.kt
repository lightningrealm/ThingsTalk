package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.*

/** Shares the exact codec used by BlePanelOperations; only presentation of results is added here. */
class DefaultPayloadInspector : PayloadInspector {
    override fun decode(format: PayloadFormat, source: String, input: PayloadInputEncoding): PayloadInspection {
        validate(format)
        require(source.length <= MAX_INSPECTOR_SOURCE_CHARS) { "输入不能超过 $MAX_INSPECTOR_SOURCE_CHARS 个字符" }
        val bytes = when (input) {
            PayloadInputEncoding.HEX -> {
                val hex = source.compactPanelHex()
                require(isPanelHex(hex, 1, 512)) { "请输入 1–512 字节的完整 HEX，可用空白分隔" }
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            PayloadInputEncoding.JSON_TEXT -> {
                require(format.encoding == PayloadEncoding.JSON) { "二进制格式请使用 HEX 输入" }
                try { source.encodeToByteArray(throwOnInvalidSequence = true) }
                catch (_: CharacterCodingException) { throw IllegalArgumentException("JSON 文本包含无效字符，无法编码为 UTF-8") }
            }
        }
        return inspect(format, bytes)
    }

    override fun encode(format: PayloadFormat, values: Map<String, String>): PayloadInspection {
        validate(format)
        require(values.size <= 32 && values.values.all { it.length <= MAX_INSPECTOR_VALUE_CHARS }) { "字段值不能超过 $MAX_INSPECTOR_VALUE_CHARS 个字符" }
        return inspect(format, PanelPayloadCodec.encodeFields(format, values))
    }

    private fun validate(format: PayloadFormat) {
        val errors = validatePayloadFormats(listOf(format))
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }

    private fun inspect(format: PayloadFormat, bytes: ByteArray): PayloadInspection {
        // Round-trip encoded frames too, so FLOAT32 results show the actual transmitted value.
        val values = PanelPayloadCodec.decodeFields(format, bytes)
        var offset = format.prefixHex.compactPanelHex().length / 2
        return PayloadInspection(
            hex = bytes.joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() },
            byteCount = bytes.size,
            jsonText = if (format.encoding == PayloadEncoding.JSON) bytes.decodeToString(throwOnInvalidSequence = true) else null,
            fields = format.fields.map { field ->
                val binary = format.encoding == PayloadEncoding.BINARY
                val width = if (field.codec == CodecType.UTF8) values.getValue(field.id).encodeToByteArray().size else field.byteWidth()
                InspectedPayloadField(field.id, field.codec, values.getValue(field.id),
                    byteOffset = if (binary) offset else null,
                    byteCount = if (binary) width else null,
                ).also { if (binary) offset += width }
            },
        )
    }
}
