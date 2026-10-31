package com.lr.thingstalk.data.panel

import com.lr.thingstalk.domain.panel.PanelDefinition
import com.lr.thingstalk.domain.panel.PanelDefinitionCodec
import com.lr.thingstalk.domain.panel.validatePanel
import kotlinx.serialization.encodeToString
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
        val root = json.parseToJsonElement(source).jsonObject
        return (if (root["schemaVersion"]?.jsonPrimitive?.intOrNull in 6..7)
            json.decodeFromJsonElement<ChannelPanelJson>(root).definition()
        else json.decodeFromJsonElement<PanelDefinition>(root)).also(::requireValid)
    }

    private fun requireValid(definition: PanelDefinition) {
        val errors = validatePanel(definition)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
