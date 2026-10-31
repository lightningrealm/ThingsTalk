package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.PanelDefinition
import com.lr.thingstalk.domain.panel.PanelDefinitionCodec
import com.lr.thingstalk.domain.panel.validatePanel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Strict parsing ensures an imported file cannot silently lose operations or schema fields. */
object PanelJson : PanelDefinitionCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private const val MAX_JSON_LENGTH = 128 * 1024

    override fun encode(definition: PanelDefinition): String {
        requireValid(definition)
        return (if (definition.schemaVersion >= 6) json.encodeToString(definition.channelJson()) else json.encodeToString(definition)).also {
            require(it.length <= MAX_JSON_LENGTH) { "面板文件不能超过 128K 字符" }
        }
    }

    override fun decode(source: String): PanelDefinition {
        require(source.length <= MAX_JSON_LENGTH) { "面板文件不能超过 128K 字符" }
        try {
            val root = json.parseToJsonElement(source) as? JsonObject
                ?: throw IllegalArgumentException("面板配置必须是 JSON 对象，请选择 .thingstalk.json 文件")
            val reference = root["configuration"] as? JsonPrimitive
            require(!(reference?.isString == true &&
                (root["valid"] is JsonArray || root["invalid"] is JsonArray) &&
                "schemaVersion" !in root && "title" !in root)) {
                "该文件是协议测试向量，不是面板配置。请导入 configuration 指向的 .thingstalk.json 配置文件"
            }
            return (if ((root["schemaVersion"] as? JsonPrimitive)?.intOrNull in 6..7)
                json.decodeFromJsonElement<ChannelPanelJson>(root).definition()
            else json.decodeFromJsonElement<PanelDefinition>(root)).also(::requireValid)
        } catch (_: SerializationException) {
            // Serialization errors can include raw JSON (including accidental credentials).
            // Keep strict field validation, but do not expose the source in UI error messages.
            throw IllegalArgumentException("无法解析面板配置：JSON 语法、字段或字段类型不符合规范，请选择设备的 .thingstalk.json 配置文件")
        }
    }

    private fun requireValid(definition: PanelDefinition) {
        val errors = validatePanel(definition)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
