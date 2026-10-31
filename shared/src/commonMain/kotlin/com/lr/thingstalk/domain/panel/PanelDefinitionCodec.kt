package com.lr.thingstalk.domain.panel

/** Serialization boundary; implementations validate imported and exported definitions. */
interface PanelDefinitionCodec {
    fun encode(definition: PanelDefinition): String
    fun decode(source: String): PanelDefinition
}
