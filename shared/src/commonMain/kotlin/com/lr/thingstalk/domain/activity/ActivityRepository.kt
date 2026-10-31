package com.lr.thingstalk.domain.activity

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable data class DeviceEvent(
    val id: String, val deviceId: String, val deviceName: String, val transport: String,
    val title: String, val detail: String, val timestamp: Long,
    val warning: Boolean = false, val read: Boolean = false, val acknowledged: Boolean = false,
    val count: Int = 1, val recordId: String? = null,
)
data class CommunicationRecord(
    val id: String, val deviceId: String, val deviceName: String, val transport: String,
    val operation: String, val address: String, val timestamp: Long, val detail: String,
    val payload: String?, val byteCount: Int,
)
@Serializable data class PanelTemplate(val id: String, val title: String, val json: String)
@Serializable data class ActivityArchive(
    val version: Int = 1, val events: List<DeviceEvent> = emptyList(),
    val templates: List<PanelTemplate> = emptyList(), val retentionDays: Int = 30,
    val recordConnectionEvents: Boolean = true,
)
data class ReadUpdateResult(val changed: Int, val unread: Int, val error: String? = null)

interface ActivityRepository {
    val archive: StateFlow<ActivityArchive>
    val records: StateFlow<List<CommunicationRecord>>
    val error: StateFlow<String?>
    val saving: StateFlow<Boolean>
    val capturePayloads: StateFlow<Boolean>
    fun record(deviceId: String, deviceName: String, transport: String, operation: String,
        address: String = "", detail: String = "", payload: String? = null, byteCount: Int = 0): String
    fun event(deviceId: String, deviceName: String, transport: String, title: String, detail: String,
        warning: Boolean = false, recordId: String? = null)
    fun markRead(id: String, read: Boolean = true): ReadUpdateResult
    fun markAllRead(): ReadUpdateResult
    fun acknowledge(id: String)
    fun clearEvents()
    fun clearRecords()
    fun setRetention(days: Int)
    fun setConnectionEvents(enabled: Boolean)
    fun setCapturePayloads(enabled: Boolean)
    fun saveTemplate(title: String, json: String)
    fun deleteTemplate(id: String)
    fun retrySave()
}
