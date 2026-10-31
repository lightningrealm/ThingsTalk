package com.lr.thingstalk.domain.panel

import kotlinx.serialization.Serializable

/** Local editor checkpoint only; never a portable configuration or a device command. */
@Serializable
data class PanelDraftRecord(
    val baseline: PanelDefinition,
    val definition: PanelDefinition,
    val jsonText: String = "",
    val jsonEdited: Boolean = false,
    val selectedWidgetId: String? = null,
)

interface PanelDraftRepository {
    suspend fun load(deviceId: String): PanelDraftRecord?
    suspend fun save(deviceId: String, draft: PanelDraftRecord)
    suspend fun delete(deviceId: String)
}
