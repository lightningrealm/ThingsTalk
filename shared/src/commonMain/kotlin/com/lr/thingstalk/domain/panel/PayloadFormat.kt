package com.lr.thingstalk.domain.panel

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/** A portable payload schema. Device identity and BLE addresses live outside the schema. */
@Serializable
data class PayloadFormat(
    val id: String,
    val label: String,
    val encoding: PayloadEncoding = PayloadEncoding.BINARY,
    val fields: List<PayloadField> = emptyList(),
    val prefixHex: String = "",
    val suffixHex: String = "",
)

@Serializable
enum class PayloadEncoding { BINARY, JSON }

/** Binary fields are packed in list order. JSON fields use id as a top-level property name. */
@Serializable
data class PayloadField(
    val id: String,
    val codec: CodecType = CodecType.UINT8,
    val byteOrder: PanelByteOrder = PanelByteOrder.LITTLE_ENDIAN,
    val scale: Double = 1.0,
    val bias: Double = 0.0,
    val falseHex: String = "00",
    val trueHex: String = "01",
    val defaultValue: String? = null,
)

/** A property binds one field; a fixed action binds the entire format using explicit defaults. */
@Serializable
data class PayloadReference(val formatId: String, val fieldId: String? = null)

private val payloadIdentifier = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
private val payloadNumber = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

fun validatePayloadFormats(formats: List<PayloadFormat>): List<String> = buildList {
    fun issue(condition: Boolean, message: String) { if (condition) add(message) }
    issue(formats.size > 32, "报文格式不能超过 32 个")
    issue(formats.map { it.id }.distinct().size != formats.size, "报文格式 ID 不能重复")
    formats.forEach { format ->
        val title = "报文 ${format.id}"
        issue(!payloadIdentifier.matches(format.id), "$title 的 ID 格式无效")
        issue(format.label.isBlank() || format.label.length > 80, "$title 的名称需为 1–80 个字符")
        issue(format.fields.isEmpty() || format.fields.size > 32, "$title 需要 1–32 个字段")
        issue(format.fields.map { it.id }.distinct().size != format.fields.size, "$title 的字段 ID 不能重复")
        issue(!isPanelHex(format.prefixHex.compactPanelHex(), 0, 512) || !isPanelHex(format.suffixHex.compactPanelHex(), 0, 512), "$title 的帧头 / 帧尾必须是完整 HEX 字节")
        if (format.encoding == PayloadEncoding.JSON) issue(format.prefixHex.isNotEmpty() || format.suffixHex.isNotEmpty(), "$title 的 JSON 格式不使用二进制帧头 / 帧尾")
        format.fields.forEach { field ->
            val name = "$title / ${field.id}"
            issue(!payloadIdentifier.matches(field.id), "$name 的字段 ID 格式无效")
            issue(field.codec == CodecType.HEX, "$name 不支持 HEX 字段")
            if (field.codec == CodecType.UTF8) {
                issue(field.scale != 1.0 || field.bias != 0.0 || field.byteOrder != PanelByteOrder.LITTLE_ENDIAN, "文本字段不使用数值换算或字节序")
                issue(format.encoding == PayloadEncoding.BINARY && format.fields.size != 1, "二进制 UTF-8 报文只能有一个文本字段，可设置帧头和帧尾")
            }
            issue(!field.scale.isFinite() || field.scale == 0.0 || !field.bias.isFinite(), "$name 的倍率必须非零且换算参数必须有限")
            if (field.codec == CodecType.BOOLEAN) {
                val no = field.falseHex.compactPanelHex()
                val yes = field.trueHex.compactPanelHex()
                issue(!isPanelHex(no, 1, 32) || !isPanelHex(yes, 1, 32) || no.length != yes.length || no.equals(yes, true), "$name 的布尔编码须为不同且等长的 1–32 字节")
                issue(field.scale != 1.0 || field.bias != 0.0 || field.byteOrder != PanelByteOrder.LITTLE_ENDIAN, "$name 的布尔字段不能使用数值换算或字节序")
            } else issue(field.falseHex != "00" || field.trueHex != "01", "$name 的数值字段不能指定布尔编码")
            if (format.encoding == PayloadEncoding.JSON) {
                issue(field.byteOrder != PanelByteOrder.LITTLE_ENDIAN || field.scale != 1.0 || field.bias != 0.0 || field.falseHex != "00" || field.trueHex != "01", "$name 的 JSON 值直接使用工程值，二进制参数须为默认值")
            }
            field.defaultValue?.let { value ->
                payloadValueProblem(field, value)?.let { add("$name 的默认值无效：$it") }
            }
        }
        if (format.encoding == PayloadEncoding.BINARY) {
            val size = format.prefixHex.compactPanelHex().length / 2L + format.suffixHex.compactPanelHex().length / 2L + format.fields.sumOf { it.byteWidth().toLong() }
            issue((if (format.fields.any { it.codec == CodecType.UTF8 }) size + 1 else size) !in 1L..512L, "$title 的完整二进制报文需要 1–512 字节")
        }
    }
}

internal fun validatePayloadReference(
    definition: PanelDefinition,
    capability: PanelCapability?,
    operation: BleOperationBinding,
    kind: String,
): List<String> = buildList {
    val reference = operation.payload ?: return@buildList
    fun issue(condition: Boolean, message: String) { if (condition) add(message) }
    issue(definition.schemaVersion < 3, "命名报文格式需要 schemaVersion 3")
    issue(operation.codec != CodecType.UINT8 || operation.readOffset != 0 || operation.byteOrder != PanelByteOrder.LITTLE_ENDIAN || operation.scale != 1.0 || operation.bias != 0.0 || operation.writePrefixHex.isNotEmpty() || operation.writeSuffixHex.isNotEmpty() || operation.falseHex != "00" || operation.trueHex != "01", "引用报文格式时，操作内的旧编解码参数须保持默认值")
    issue(kind != "write" && operation.writeMode != PanelWriteMode.WITH_RESPONSE, "读取或通知不能定义写入模式")
    val format = definition.payloadFormats.singleOrNull { it.id == reference.formatId }
    issue(format == null, "引用不存在或不唯一的报文格式：${reference.formatId}")
    if (format == null) return@buildList
    if (capability?.type == CapabilityType.ACTION) {
        issue(kind != "write" || reference.fieldId != null, "报文动作仅支持写入完整报文，不选择单个字段")
    } else {
        val field = format.fields.singleOrNull { it.id == reference.fieldId }
        issue(field == null, "读取、通知和属性写入必须引用有效字段：${reference.fieldId.orEmpty()}")
        if (field != null && capability != null) {
            issue(when (capability.type) {
                CapabilityType.NUMBER -> field.codec !in numericPanelCodecs
                CapabilityType.BOOLEAN -> field.codec != CodecType.BOOLEAN
                CapabilityType.ACTION -> true
                CapabilityType.TEXT -> field.codec != CodecType.UTF8
            }, "报文字段类型与组件能力不匹配：${capability.id}")
        }
    }
    if (kind == "write") {
        format.fields.filterNot { capability?.type != CapabilityType.ACTION && it.id == reference.fieldId }.forEach {
            issue(it.defaultValue == null, "写入报文 ${format.id} 的字段 ${it.id} 需要显式默认值")
        }
    }
}

internal fun PayloadField.byteWidth(): Int = if (codec == CodecType.BOOLEAN) falseHex.compactPanelHex().length / 2 else codec.byteWidth()

/** Pure numeric validation is also used for schema defaults before any transport operation. */
internal fun payloadValueProblem(field: PayloadField, value: String): String? {
    if (field.codec == CodecType.UTF8) return panelTextProblem(value, 512)
    if (value.length > 128) return "值不能超过 128 个字符"
    if (field.codec == CodecType.BOOLEAN) return if (value in listOf("true", "false")) null else "布尔值仅接受 true 或 false"
    if (field.codec !in numericPanelCodecs) return "不支持此字段编码"
    if (!payloadNumber.matches(value)) return "数值需为十进制或科学计数法"
    val engineering = value.toDoubleOrNull() ?: return "数值格式无效"
    if (!engineering.isFinite()) return "数值必须有限"
    if (engineering == 0.0 && value.substringBefore('e').substringBefore('E').any { it in '1'..'9' }) return "数值下溢为零"
    if (!field.scale.isFinite() || field.scale == 0.0 || !field.bias.isFinite()) return "换算参数无效"
    val delta = engineering - field.bias
    val raw = delta / field.scale
    if (!raw.isFinite() || (raw == 0.0 && delta != 0.0)) return "换算后数值溢出或下溢"
    if (field.codec == CodecType.FLOAT32) {
        val number = raw.toFloat()
        return if (!number.isFinite() || (number == 0f && raw != 0.0) || !(number.toDouble() * field.scale + field.bias).isFinite()) "超出 FLOAT32 可表示范围" else null
    }
    val rounded = round(raw)
    val tolerance = abs(raw) * 4.0 * 2.220446049250313e-16
    if ((rounded == 0.0 && raw != 0.0) || abs(raw - rounded) > tolerance) return "不能用 ${field.codec} 精确表示"
    val (minimum, maximum) = when (field.codec) {
        CodecType.UINT8 -> 0.0 to 255.0
        CodecType.INT8 -> -128.0 to 127.0
        CodecType.UINT16 -> 0.0 to 65535.0
        CodecType.INT16 -> -32768.0 to 32767.0
        CodecType.UINT32 -> 0.0 to 4294967295.0
        CodecType.INT32 -> -2147483648.0 to 2147483647.0
        else -> return "不支持此字段编码"
    }
    return if (rounded !in minimum..maximum) "数值超出 ${field.codec} 范围" else null
}
