package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.*
import kotlinx.serialization.json.*

/** Structured wire payloads. Configuration JSON and device JSON are separate serialization layers. */
internal object PanelPayloadCodec {
    private val json = Json
    private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun decode(definition: PanelDefinition, capability: PanelCapability, operation: BleOperationBinding, bytes: ByteArray): String {
        return decode(definition, capability, requireNotNull(operation.payload), bytes)
    }

    fun decode(definition: PanelDefinition, capability: PanelCapability, reference: PayloadReference, bytes: ByteArray): String {
        val format = definition.payloadFormats.single { it.id == reference.formatId }
        val values = decodeFields(format, bytes)
        val selected = requireNotNull(values[reference.fieldId]) { "报文中没有绑定字段" }
        checkCapability(capability, selected)
        return selected
    }

    fun encode(definition: PanelDefinition, capability: PanelCapability, operation: BleOperationBinding, value: String): ByteArray {
        return encode(definition, capability, requireNotNull(operation.payload), value)
    }

    fun encode(definition: PanelDefinition, capability: PanelCapability, reference: PayloadReference, value: String): ByteArray {
        val format = definition.payloadFormats.single { it.id == reference.formatId }
        if (capability.type != CapabilityType.ACTION) checkCapability(capability, value)
        val values = format.fields.associate { field ->
            val selected = if (capability.type != CapabilityType.ACTION && reference.fieldId == field.id) value
                else requireNotNull(field.defaultValue) { "字段 ${field.id} 缺少写入默认值" }
            field.id to selected
        }
        return encodeFields(format, values)
    }

    /** Used by both real characteristic operations and the offline protocol inspector. */
    fun decodeFields(format: PayloadFormat, bytes: ByteArray): Map<String, String> {
        require(bytes.size in 1..512) { "接收报文需要 1–512 字节" }
        return when (format.encoding) {
            PayloadEncoding.BINARY -> decodeBinary(format, bytes)
            PayloadEncoding.JSON -> decodeJson(format, bytes)
        }
    }

    fun encodeFields(format: PayloadFormat, values: Map<String, String>): ByteArray {
        require(values.keys == format.fields.map { it.id }.toSet()) { "写入值必须包含所有声明字段，且不能增加字段" }
        format.fields.forEach { field ->
            payloadValueProblem(field, values.getValue(field.id))?.let { throw IllegalArgumentException("字段 ${field.id}：$it") }
        }
        val bytes = when (format.encoding) {
            PayloadEncoding.BINARY -> hex(format.prefixHex) + format.fields.fold(byteArrayOf()) { result, field ->
                result + PanelValueCodec.encode(2, field.capability(), field.operation(), values.getValue(field.id))
            } + hex(format.suffixHex)
            PayloadEncoding.JSON -> buildJsonObject {
                format.fields.forEach { field ->
                    val text = values.getValue(field.id)
                    put(field.id, when (field.codec) {
                        CodecType.BOOLEAN -> JsonPrimitive(text == "true")
                        CodecType.FLOAT32 -> JsonPrimitive(text.toDouble())
                        else -> JsonPrimitive(text.toDouble().toLong())
                    })
                }
            }.toString().encodeToByteArray()
        }
        require(bytes.size in 1..512) { "完整写入报文需要 1–512 字节" }
        return bytes
    }

    private fun decodeBinary(format: PayloadFormat, bytes: ByteArray): Map<String, String> {
        val prefix = hex(format.prefixHex)
        val suffix = hex(format.suffixHex)
        val expectedSize = prefix.size + format.fields.sumOf { it.byteWidth() } + suffix.size
        require(bytes.size == expectedSize) { "报文长度不匹配：需要 $expectedSize 字节，实际 ${bytes.size} 字节" }
        require(bytes.copyOfRange(0, prefix.size).contentEquals(prefix)) { "报文帧头与配置不匹配" }
        require(bytes.copyOfRange(bytes.size - suffix.size, bytes.size).contentEquals(suffix)) { "报文帧尾与配置不匹配" }
        var offset = prefix.size
        return format.fields.associate { field ->
            val value = PanelValueCodec.decode(2, field.capability(), field.operation().copy(readOffset = offset), bytes)
            offset += field.byteWidth()
            field.id to value
        }
    }

    private fun decodeJson(format: PayloadFormat, bytes: ByteArray): Map<String, String> {
        val source = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw IllegalArgumentException("JSON 报文必须使用有效的 UTF-8 编码") }
        val obj = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("JSON 报文必须为一个对象")
        rejectDuplicateKeys(source)
        require(obj.keys == format.fields.map { it.id }.toSet()) { "JSON 报文字段与配置不一致，不能缺少或增加字段" }
        return format.fields.associate { field ->
            val primitive = obj[field.id] as? JsonPrimitive
                ?: throw IllegalArgumentException("字段 ${field.id} 必须为数值或布尔值")
            require(!primitive.isString && primitive != JsonNull) { "字段 ${field.id} 不接受字符串或 null" }
            val value = primitive.content
            if (field.codec == CodecType.BOOLEAN) require(value in listOf("true", "false")) { "字段 ${field.id} 必须为 JSON 布尔值" }
            else require(jsonNumber.matches(value)) { "字段 ${field.id} 必须为 JSON 数值" }
            payloadValueProblem(field, value)?.let { throw IllegalArgumentException("字段 ${field.id}：$it") }
            field.id to value
        }
    }

    // JsonObject retains the last duplicate key. Reject ambiguous input before selecting any value.
    private fun rejectDuplicateKeys(source: String) {
        val keys = mutableSetOf<String>()
        var depth = 0
        var expectsKey = false
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '{', '[' -> { depth++; if (depth == 1) expectsKey = true; index++ }
                '}', ']' -> { depth--; index++ }
                ',' -> { if (depth == 1) expectsKey = true; index++ }
                '"' -> {
                    val start = index++
                    while (index < source.length) {
                        if (source[index] == '\\') index += 2
                        else if (source[index++] == '"') break
                    }
                    if (depth == 1 && expectsKey) {
                        val key = json.parseToJsonElement(source.substring(start, index)).jsonPrimitive.content
                        require(keys.add(key)) { "JSON 报文字段重复：$key" }
                        expectsKey = false
                    }
                }
                else -> index++
            }
        }
    }

    private fun checkCapability(capability: PanelCapability, value: String) {
        when (capability.type) {
            CapabilityType.BOOLEAN -> require(value == "true" || value == "false") { "组件需要布尔值" }
            CapabilityType.NUMBER -> {
                val number = value.toDoubleOrNull()
                require(number != null && number.isFinite() && number >= (capability.minimum ?: 0.0) && number <= (capability.maximum ?: 255.0)) { "数值超出 ${capability.label} 的允许范围" }
            }
            CapabilityType.ACTION -> error("动作不读取状态")
        }
    }

    private fun PayloadField.capability() = PanelCapability(id, id,
        if (codec == CodecType.BOOLEAN) CapabilityType.BOOLEAN else CapabilityType.NUMBER,
        minimum = if (codec == CodecType.BOOLEAN) null else -Double.MAX_VALUE,
        maximum = if (codec == CodecType.BOOLEAN) null else Double.MAX_VALUE)

    private fun PayloadField.operation() = BleOperationBinding("", "", codec,
        byteOrder = byteOrder, scale = scale, bias = bias, falseHex = falseHex, trueHex = trueHex)

    private fun hex(value: String): ByteArray = value.compactPanelHex().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
